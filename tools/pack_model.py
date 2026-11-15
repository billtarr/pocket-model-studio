#!/usr/bin/env python3
"""Pocket Model Studio —— 模型包打包工具

模型包（.pm，本质是 zip store 模式）结构：

    manifest.json          # 必需，描述怎么跑这个模型
    weights/...            # 权重：gguf / safetensors / QNN context binary
    runtime/               # 可选，**这个模型专用的运行框架**
      ├── bin/             #   可执行文件（任意文件名，App 会补执行位）
      ├── lib/             #   .so 依赖
      ├── skel/            #   QNN skel 等纯数据
      └── VERSION          #   可选，一行描述（如 "llama.cpp b10144 / QNN 2.51"）
    webui/index.html       # 可选，内置前端

为什么运行框架随包：不同模型对执行器版本极其敏感（QNN skel 绑 soc_id、
llama.cpp 的 MTP 头绑 commit、视频模型又是另一套）。固定在 APK 里意味着换模型就要重发 App。
包里有 runtime/ 就优先用它；没有才回落到 App 内置运行时（见 RUNTIMES）。

用法：
    # 1) 建包
    python pack_model.py init ./my-model --kind llm --name "Gemma-4 E4B"
    # 2) 把运行框架收进来（可重复执行）
    python pack_model.py runtime ./my-model --from /d/llm-npu-vk/build-oldcl/bin --exe llama-server --version "llama.cpp b10144"
    # 3) 放权重、扫权重
    python pack_model.py scan ./my-model
    # 4) 校验并打包
    python pack_model.py check ./my-model
    python pack_model.py pack  ./my-model -o dist/gemma4-e4b-q8.pm
    python pack_model.py list  dist/gemma4-e4b-q8.pm
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import os
import shutil
import stat
import sys
import time
import zipfile
from pathlib import Path

# 单个 zip entry 的上限。Android 的 ZipInputStream 不支持 zip64，>4 GiB 的 entry
# 读不出来（表现为解压到一半结束、文件短一截），所以默认 2 GiB 就切。
DEFAULT_PART_SIZE = 2 * 1024 ** 3

# App 内置运行时（模型包没有 runtime/ 时才会用到；改一边要改 app 的 RuntimeSpec）
RUNTIMES = {
    "studio-llm": {"studio-llm"},
    "studio-llm-plain": {"studio-llm"},
    "llama-cpu-mtp": {"server", "completion", "mtp-bench", "kvbr"},
    "qnn-prefill": {"layerrun", "mk-kvb", "quantize"},
    "qnn-diffusion": {"chainrun"},
}
# 允许「完全随包、不依赖内置运行时」：manifest.runtime 留空

PARAM_TEMPLATES = {
    "llm": [
        {"key": "n_ctx", "label": "上下文长度", "type": "int", "default": 4096,
         "min": 512, "max": 32768, "unit": "tok"},
        {"key": "n_predict", "label": "最大生成长度", "type": "int", "default": 512,
         "min": 1, "max": 8192, "unit": "tok"},
        {"key": "threads", "label": "线程数", "type": "int", "default": 4,
         "min": 1, "max": 8, "help": "8 Gen 2 上 4 线程最优"},
        {"key": "n_max", "label": "投机 draft 数", "type": "enum",
         "default": "3", "options": ["0", "1", "3", "7"],
         "help": "必须是 4k-1（batch 4 对齐），0 = 关闭投机"},
        {"key": "temp", "label": "温度", "type": "float", "default": 0.7,
         "min": 0.0, "max": 2.0, "step": 0.05},
    ],
    "image": [
        {"key": "steps", "label": "采样步数", "type": "int", "default": 8,
         "min": 1, "max": 60},
        {"key": "size", "label": "分辨率", "type": "enum", "default": "1024",
         "options": ["512", "768", "1024"]},
        {"key": "cfg", "label": "CFG", "type": "float", "default": 1.0,
         "min": 0.0, "max": 15.0, "step": 0.1},
        {"key": "seed", "label": "随机种子", "type": "int", "default": -1,
         "help": "-1 表示随机"},
        {"key": "te", "label": "文本编码器后端", "type": "enum", "default": "cpu",
         "options": ["cpu", "npu"], "help": "8 Gen 2 必须 cpu"},
    ],
    "video": [
        {"key": "frames", "label": "帧数", "type": "int", "default": 16, "min": 1, "max": 64},
        {"key": "fps", "label": "帧率", "type": "int", "default": 8, "min": 1, "max": 30},
        {"key": "steps", "label": "采样步数", "type": "int", "default": 8, "min": 1, "max": 60},
        {"key": "size", "label": "分辨率", "type": "enum", "default": "512",
         "options": ["256", "512", "768"]},
    ],
}

ENTRY_TEMPLATES = {
    # ★ 推荐：自研服务层。MTP 真的生效（llama-server 的 MTP 实现接受率只有 1/3）
    "studio-llm": {
        "bin": "studio-llm",
        "file": "studio-llm",
        "args": ["-m", "{weight.main}", "-md", "{weight.drafter}", "-c", "{n_ctx}",
                 "-t", "{threads}", "--n-max", "{n_max}"],
        "portArg": "--port",
        "healthPath": "/health",
        "readyTimeoutSec": 120,
        "stopMode": "stdin-close",
    },
    # 同上但没有 drafter（不投机）
    "studio-llm-plain": {
        "bin": "studio-llm",
        "file": "studio-llm",
        "args": ["-m", "{weight.main}", "-c", "{n_ctx}", "-t", "{threads}", "--n-max", "0"],
        "portArg": "--port",
        "healthPath": "/health",
        "readyTimeoutSec": 120,
        "stopMode": "stdin-close",
    },
    "llama-cpu-mtp": {
        "bin": "server",
        "args": ["-m", "{weight.main}", "-c", "{n_ctx}", "-t", "{threads}", "-ngl", "0",
                 "-dev", "none", "--parallel", "1", "--temp", "{temp}", "-n", "{n_predict}"],
        "portArg": "--port",
        "healthPath": "/health",
        "readyTimeoutSec": 300,
        "stopMode": "stdin-close",
    },
    "qnn-prefill": {
        "bin": "layerrun",
        "args": ["--seq", "{n_ctx}", "--in", "{model}/chain"],
        "portArg": "--port",
        "healthPath": "/health",
        "readyTimeoutSec": 120,
        "stopMode": "sigterm",
    },
    "qnn-diffusion": {
        "bin": "chainrun",
        "args": ["{model}/segments", "--steps", "{steps}", "--out", "{model}/out.raw"],
        "portArg": "--port",
        "healthPath": "/health",
        "readyTimeoutSec": 180,
        "stopMode": "sigterm",
    },
}


def die(msg: str) -> None:
    print(f"✗ {msg}", file=sys.stderr)
    sys.exit(1)


def human(n: int) -> str:
    for u in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or u == "TB":
            return f"{n:.2f} {u}" if u != "B" else f"{n} B"
        n /= 1024
    return f"{n} B"


def is_elf(p: Path) -> bool:
    """ELF 魔数判断，避免把 shell 脚本或占位文件当二进制打进包"""
    try:
        with open(p, "rb") as f:
            return f.read(4) == b"\x7fELF"
    except OSError:
        return False


def cmd_runtime(args) -> None:
    """把运行框架收进 runtime/：可执行 → bin/，*.so → lib/，其余数据 → skel/。"""
    d = Path(args.dir)
    mf = load_manifest(d)
    src = Path(args.src)
    rt = d / "runtime"
    for sub in ("bin", "lib", "skel"):
        (rt / sub).mkdir(parents=True, exist_ok=True)

    # 1) 主二进制：显式指定的 exe，或 src 下同名文件
    exe_name = args.exe or mf.get("entry", {}).get("bin")
    exe_src = src / exe_name if not Path(exe_name).is_absolute() else Path(exe_name)
    if not exe_src.is_file():
        die(f"找不到可执行文件 {exe_src}")
    if not is_elf(exe_src):
        die(f"{exe_src} 不是 ELF 文件")
    dst_exe = rt / "bin" / exe_src.name
    shutil.copy2(exe_src, dst_exe)
    dst_exe.chmod(dst_exe.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    print(f"  bin/ {human(exe_src.stat().st_size):>10s}  {exe_src.name}")

    # 2) 其余 ELF：*.so → lib/，其它可执行（llama-quantize 之类）→ bin/
    lib_bytes = bin_bytes = 0
    skipped = []
    for f in sorted(src.iterdir()):
        if not f.is_file() or f.name == exe_src.name:
            continue
        if any(fnmatch.fnmatch(f.name, pat) for pat in (args.exclude or [])):
            skipped.append(f.name)
            continue
        if not is_elf(f):
            continue
        if ".so" in f.name:
            shutil.copy2(f, rt / "lib" / f.name)
            lib_bytes += f.stat().st_size
        else:
            dst = rt / "bin" / f.name
            shutil.copy2(f, dst)
            dst.chmod(dst.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
            bin_bytes += f.stat().st_size
            print(f"  bin/ {human(f.stat().st_size):>10s}  {f.name}")
    print(f"  lib/ {human(lib_bytes):>10s}  {len(list((rt / 'lib').iterdir()))} 个")
    if skipped:
        print(f"  跳过 {len(skipped)} 个：{', '.join(skipped)}")

    # 3) skel / 其它数据目录
    skel_bytes = 0
    if args.skel:
        sk = Path(args.skel)
        if sk.is_dir():
            dst = rt / "skel"
            for f in sk.rglob("*"):
                if f.is_file():
                    out = dst / f.relative_to(sk)
                    out.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(f, out)
                    skel_bytes += f.stat().st_size
            print(f"  skel/ {human(skel_bytes):>10s}  {len(list((rt / 'skel').rglob('*')))} 项")

    if args.version:
        (rt / "VERSION").write_text(args.version.strip() + "\n", encoding="utf-8")

    # 4) manifest：入口改成「文件名词」，并把 runtime 置空（不再依赖内置）
    mf.setdefault("entry", {})
    mf["entry"]["file"] = exe_src.name
    if not mf.get("runtime"):
        mf["runtime"] = ""
    (d / "manifest.json").write_text(json.dumps(mf, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    total = exe_src.stat().st_size + lib_bytes + bin_bytes + skel_bytes
    print(f"✓ 运行框架已收进 {rt}（共 {human(total)}），entry.file = {exe_src.name}")


def cmd_init(args) -> None:
    d = Path(args.dir)
    d.mkdir(parents=True, exist_ok=True)
    (d / "weights").mkdir(exist_ok=True)
    if args.webui:
        (d / "webui").mkdir(exist_ok=True)
        (d / "webui" / "index.html").write_text(
            "<!doctype html><meta charset=utf-8><title>webui</title>"
            "<h1>把前端静态文件放这里</h1><p>模型服务启动后可在 App 内打开。</p>\n",
            encoding="utf-8",
        )
    rt = "" if args.embed_runtime else args.runtime
    if rt and rt not in RUNTIMES:
        die(f"未知 runtime {rt}，可选：{', '.join(RUNTIMES)}（或 --embed-runtime 自带）")
    if args.embed_runtime:
        for sub in ("bin", "lib", "skel"):
            (d / "runtime" / sub).mkdir(parents=True, exist_ok=True)
    mf = {
        "schema": 1,
        "id": args.id or d.name,
        "name": args.name or d.name,
        "kind": args.kind,
        "runtime": rt,
        "version": args.version,
        "description": "",
        "weights": [],
        "params": PARAM_TEMPLATES.get(args.kind, []),
        "entry": ENTRY_TEMPLATES.get(args.entry or rt) or ENTRY_TEMPLATES["llama-cpu-mtp"],
        "requires": {"ramMb": 6000, "vaMb": 2304, "abi": "arm64-v8a"},
        "webui": "webui/index.html" if args.webui else None,
    }
    p = d / "manifest.json"
    p.write_text(json.dumps(mf, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"✓ 已生成 {p}")
    if args.embed_runtime:
        print("  运行框架随包：下一步用 pack_model.py runtime 把 bin/lib/skel 收进来")
    print("  然后：把权重放进 weights/，跑 pack_model.py scan 自动补 weights 列表")


def load_manifest(d: Path) -> dict:
    p = d / "manifest.json"
    if not p.exists():
        die(f"{d} 下没有 manifest.json（先跑 init）")
    return json.loads(p.read_text(encoding="utf-8"))


def cmd_scan(args) -> None:
    """扫描 weights/ 自动补齐 weights 列表（role 用文件名猜）"""
    d = Path(args.dir)
    mf = load_manifest(d)
    guess = {"gguf": "main", "safetensors": "main", "bin": "context", "onnx": "context", "raw": "extra"}
    weights = []
    for f in sorted((d / "weights").rglob("*")):
        if not f.is_file():
            continue
        role = guess.get(f.suffix.lstrip(".").lower(), "extra")
        low = f.name.lower()
        if "drafter" in low or "assistant" in low or "mtp" in low:
            role = "drafter"
        elif "vae" in low:
            role = "vae"
        elif "text_encoder" in low or low.startswith("te"):
            role = "te"
        weights.append({"path": str(f.relative_to(d)).replace("\\", "/"),
                        "role": role, "bytes": f.stat().st_size})
    mf["weights"] = weights
    (d / "manifest.json").write_text(json.dumps(mf, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"✓ 收录 {len(weights)} 个权重文件，共 {human(sum(w['bytes'] for w in weights))}")
    for w in weights:
        print(f"   {w['role']:9s} {human(w['bytes']):>10s}  {w['path']}")


def cmd_check(args) -> None:
    d = Path(args.dir)
    mf = load_manifest(d)
    problems: list[str] = []

    if mf.get("schema") != 1:
        problems.append(f"schema={mf.get('schema')}，当前只支持 1")
    for k in ("id", "name", "kind", "entry"):
        if not mf.get(k):
            problems.append(f"缺少字段 {k}")

    rt = mf.get("runtime") or ""
    rt_dir = d / "runtime"
    has_embedded = rt_dir.is_dir()
    entry = mf.get("entry", {})

    if has_embedded:
        bin_dir = rt_dir / "bin" if (rt_dir / "bin").is_dir() else rt_dir
        cand = [entry.get("file"), entry.get("bin")]
        exe = next((bin_dir / c for c in cand if c and (bin_dir / c).is_file()), None)
        if exe is None:
            problems.append(
                f"runtime/ 里找不到入口 {entry.get('file') or entry.get('bin')}（在 {bin_dir}）"
            )
        elif not is_elf(exe):
            problems.append(f"{exe.name} 不是 ELF 可执行文件（可能是脚本或占位文件）")
        n_lib = len(list((rt_dir / "lib").glob("*"))) if (rt_dir / "lib").is_dir() else 0
        n_skel = len(list((rt_dir / "skel").rglob("*"))) if (rt_dir / "skel").is_dir() else 0
    else:
        n_lib = n_skel = 0
        if not rt:
            problems.append("既没有 runtime/（随包运行框架），也没声明内置 runtime")
        elif rt not in RUNTIMES:
            problems.append(f"未知 runtime {rt}（可选：{', '.join(RUNTIMES)}）")
        elif entry.get("bin") not in RUNTIMES[rt]:
            problems.append(
                f"runtime {rt} 没有入口 {entry.get('bin')}（可选：{sorted(RUNTIMES[rt])}）"
            )

    weights = mf.get("weights") or []
    if not weights:
        problems.append("weights 为空（先跑 scan）")
    total = 0
    for w in weights:
        f = d / w["path"]
        if not f.exists():
            if not w.get("optional"):
                problems.append(f"权重缺失 {w['path']}")
            continue
        real = f.stat().st_size
        total += real
        if w.get("bytes") and abs(real - w["bytes"]) > 0:
            problems.append(f"大小不符 {w['path']}: manifest {w['bytes']} vs 实际 {real}")
    for p in mf.get("params") or []:
        if "key" not in p or "default" not in p:
            problems.append(f"参数定义不完整：{p}")

    rt_desc = (
        f"随包（{n_lib} lib / {n_skel} skel）" if has_embedded
        else (rt or "无")
    )
    print(f"模型 {mf.get('id')} · {mf.get('kind')} · 运行时 {rt_desc} · {len(weights)} 个权重 · {human(total)}")
    if problems:
        for p in problems:
            print(f"✗ {p}")
        sys.exit(1)
    print("✓ 校验通过")


def cmd_pack(args) -> None:
    d = Path(args.dir)
    cmd_check(argparse.Namespace(dir=str(d)))
    mf = load_manifest(d)
    out = Path(args.out or f"dist/{mf['id']}.pm")
    out.parent.mkdir(parents=True, exist_ok=True)

    files: list[Path] = [d / "manifest.json"]
    for w in mf.get("weights") or []:
        f = d / w["path"]
        if f.exists():
            files.append(f)
    if mf.get("webui"):
        webui_dir = d / Path(mf["webui"]).parent
        if webui_dir.exists():
            files += [p for p in webui_dir.rglob("*") if p.is_file()]
    rt_dir = d / "runtime"
    if rt_dir.is_dir():
        rt_files = [p for p in rt_dir.rglob("*") if p.is_file()]
        files += rt_files
        print(f"  运行框架 {len(rt_files)} 个文件 / {human(sum(p.stat().st_size for p in rt_files))}")

    # 写 zip 时保留执行位（App 用 ZipInputStream 解压不会还原权限，所以 App 侧也会 chmod；
    # 这里写 external_attr 是为了外部工具 unzip 后也能直接用）
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as z:
        for f in files:
            arc = str(f.relative_to(d)).replace("\\", "/")
            size = f.stat().st_size
            if size <= args.part_size:
                zi = zipfile.ZipInfo.from_file(f, arc)
                if arc.startswith("runtime/bin/"):
                    zi.external_attr = (0o100755 << 16)
                with z.open(zi, "w") as dst, open(f, "rb") as src:
                    shutil.copyfileobj(src, dst, 8 << 20)
                print(f"  + {human(size):>10s}  {arc}")
                continue

            # 单片超过上限：切成 <path>.partNN 顺序写入。App 侧边解压边追加，
            # 顺序一旦打乱就会得到错误拼接，所以这里必须严格按 part00/01/02 写。
            n = (size + args.part_size - 1) // args.part_size
            print(f"  ~ {human(size):>10s}  {arc}  -> {n} 片")
            with open(f, "rb") as src:
                for i in range(n):
                    left = min(args.part_size, size - i * args.part_size)
                    name = f"{arc}.part{i:02d}"
                    zi = zipfile.ZipInfo(name, date_time=time.localtime(f.stat().st_mtime)[:6])
                    zi.compress_type = zipfile.ZIP_STORED
                    zi.file_size = left
                    with z.open(zi, "w", force_zip64=False) as dst:
                        while left > 0:
                            buf = src.read(min(8 << 20, left))
                            if not buf:
                                die(f"{f} 提前 EOF")
                            dst.write(buf)
                            left -= len(buf)
                    print(f"    + {name}")
    print(f"✓ 打包完成 {out}（{human(out.stat().st_size)}，{len(files)} 个文件，store 模式不压缩）")


def cmd_list(args) -> None:
    with zipfile.ZipFile(args.package) as z:
        names = z.namelist()
        mf = json.loads(z.read("manifest.json")) if "manifest.json" in names else None
        if mf:
            print(f"{mf['name']} ({mf['id']}) · {mf['kind']} · runtime {mf['runtime']}")
        for i in z.infolist():
            print(f"  {human(i.file_size):>12s}  {i.filename}")


def main() -> None:
    ap = argparse.ArgumentParser(prog="pack_model", description="Pocket Model Studio 模型包工具")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("init", help="生成 manifest 模板与目录结构")
    p.add_argument("dir")
    p.add_argument("--kind", default="llm", choices=["llm", "image", "video"])
    p.add_argument("--runtime", default="llama-cpu-mtp",
                   help="内置运行时 id；配合 --embed-runtime 时忽略")
    p.add_argument("--embed-runtime", action="store_true",
                   help="模型包自带运行框架（runtime/），不依赖 App 内置")
    p.add_argument("--entry", default=None,
                   choices=["studio-llm", "studio-llm-plain", "llama-cpu-mtp",
                            "qnn-prefill", "qnn-diffusion"],
                   help="命令行模板；默认按 --runtime 推断。随包推荐 studio-llm")
    p.add_argument("--name", default=None)
    p.add_argument("--id", default=None)
    p.add_argument("--version", default="0.1.0")
    p.add_argument("--webui", action="store_true", help="同时建 webui/ 目录")
    p.set_defaults(func=cmd_init)

    p = sub.add_parser("scan", help="扫描 weights/ 并写回 manifest")
    p.add_argument("dir")
    p.set_defaults(func=cmd_scan)

    p = sub.add_parser("runtime", help="把运行框架收进模型包的 runtime/")
    p.add_argument("dir")
    p.add_argument("--from", dest="src", required=True, help="二进制所在目录（如 build-oldcl/bin）")
    p.add_argument("--exe", default=None, help="主二进制文件名，默认取 manifest.entry.bin")
    p.add_argument("--skel", default=None, help="skel/数据目录（如 QNN 的 hexagon-v73）")
    p.add_argument("--exclude", action="append", default=[],
                   help="按文件名/通配跳过，可重复。例如 --exclude 'libggml-htp-*' "
                        "（DSP applet 必须留在 APK jniLibs，见 README）")
    p.add_argument("--version", default=None, help="写进 runtime/VERSION 的一行描述")
    p.set_defaults(func=cmd_runtime)

    p = sub.add_parser("check", help="校验模型包完整性")
    p.add_argument("dir")
    p.set_defaults(func=cmd_check)

    p = sub.add_parser("pack", help="打包成 .pm")
    p.add_argument("dir")
    p.add_argument("-o", "--out", default=None)
    p.add_argument("--part-size", type=int, default=DEFAULT_PART_SIZE,
                   help="单个 entry 的上限（字节，默认 2 GiB）。超过就切成 <path>.partNN，"
                        "导入时 App 会按序合并 —— 因为 Android 的 ZipInputStream 不支持 zip64，"
                        "4 GiB 以上的单 entry 读不出来")
    p.set_defaults(func=cmd_pack)

    p = sub.add_parser("list", help="列出 .pm 内容")
    p.add_argument("package")
    p.set_defaults(func=cmd_list)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
