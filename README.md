# Pocket Model Studio

端侧模型服务管理器（Android）。把 **图片 / 视频 / 大语言模型** 三类本地推理服务
统一成「**一个模型包 + 一个开关 + 一个端口**」，思路对标 LM Studio，但跑在手机上（高通 NPU / CPU）。

包名 `dev.pocketmodel.studio`，名字是暂定的。

## 核心设计：运行框架跟着模型走

不同模型对执行器版本极其敏感 —— Qwen-Image 要绑 QNN skel 版本与 `soc_id`，
gemma4 要特定 commit 的 llama.cpp + MTP 头，视频模型又是另一套。

如果框架固定在 APK 里，**每换一个模型就要重发一次 App，且新旧模型无法共存**。
所以：

```
模型包（.pm = 明文 zip）
├── manifest.json     声明 kind / 权重 / 参数表单 / 启动命令 / 环境变量
├── weights/          gguf / safetensors
├── runtime/          可选：这个模型专用的运行框架（优先于 App 内置）
└── webui/            可选：这个模型专用的前端页面（优先于 assets 内置）
```

App 只负责**导入、监管进程、转发请求**，一行模型代码都不认识。

## 现在有什么

| 模块 | 状态 |
|---|---|
| Compose UI（服务 / 模型 / 设置三页） | ✅ |
| 模型包规范 `manifest.json`（schema 1） | ✅ |
| 模型导入（SAF 选 `.pm` → 校验 → 落地，带分阶段进度、可取消） | ✅ |
| 进程监管（exec / env 注入 / 日志 / 健康检查 / 优雅停止） | ✅ |
| 运行框架随包（`runtime/` 优先，App 内置兜底） | ✅ APK 不装任何原生库也能跑 |
| 参数表单（由 manifest 的 `params` 动态渲染） | ✅ |
| PC 端打包工具 `tools/pack_model.py` | ✅ |
| WebUI + 局域网网关 + OpenAI 兼容接口 | ✅ |
| 熄屏保活（WakeLock + 电池白名单入口） | ⚠️ 部分：CPU 不休眠，但 ColorOS 仍掐网络 |
| 多实例资源仲裁 / 模型包签名校验 | ❌ 未做 |

## 模型包下载

打好的包发在 ModelScope，下载后直接在 App 里「导入」即可 —— **包内自带运行框架**，不用配环境：

| 模型包 | 任务 | 大小 | 实测（8 Gen 2） |
|---|---|---|---|
| [**qwen-image-2.1-turbo-split**](https://modelscope.cn/models/billtarr/qwen-image-2.1-turbo-split) | 文生图 | 9.26 GB | 320²/8 步约 40 s、512² 约 57 s |
| [**qwen35-9b-split**](https://modelscope.cn/models/billtarr/qwen35-9b-split) | 文本生成 | 5.32 GB | 约 5.4~5.6 tok/s（双槽流水，≤2k 上下文） |

- **qwen-image-2.1-turbo-split**：7.1B DiT 按层切 3 段常驻链（0-11 / 11-22 / 22-32），
  段间共享内存 + eventfd 传隐状态，末段即 `sd-server`，对外提供 `/sdapi` 接口。
  文本编码器走 CPU，DiT 与 VAE 走 HTP0。
- **qwen35-9b-split**：32 层按 `11/24` 切三段，各持一份 NPU 会话，`lm_head` 放 DSP；
  支持 OpenAI 风格 `/v1/chat/completions` 与流式输出。

⚠️ **两个包都只支持骁龙 8 Gen 2（SM8550 / Hexagon v73）**。DSP applet 绑定 hexagon 架构版本，
8 Gen 3 及以上要另编对应 applet，本包不能直接用。

使用流程：先构建并安装 APK（见下节；**预编译包暂未发布**）→「模型」页「导入」选 `.pm` →
解压并补执行位 → 点「部署」→「服务」页开开关 → 卡片上出现 WebUI 与局域网地址。

> 另有 **gemma4-E4B 混推包**（NPU prefill + CPU decode，decode 约 11 t/s，7.96 GB）
> 在本机验证过，暂未发布。

## 构建

需要 JDK 17 + Android SDK（compileSdk 35、build-tools 34.0.0、NDK 非必需）。

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK 约 45 MB —— 几乎全是 `app/src/main/jniLibs` 里那 16 个 `.so`（未压缩 67 MB，其中
`libsd_server.so` 独占 40 MB）压出来的。App 侧 Kotlin 代码不到 1 MB。

## 做一个模型包

**打包目录放仓库外**，仓库里只留手写的 manifest：

```bash
cd tools
python pack_model.py init  /path/my-model --kind llm --runtime llama-cpu-mtp --name "我的模型"
cp model.gguf /path/my-model/weights/
python pack_model.py scan  /path/my-model     # 按文件名猜 role，补 weights 列表
python pack_model.py check /path/my-model     # 校验字段 / 文件 / 大小 / 入口
python pack_model.py pack  /path/my-model -o /path/packages/my-model.pm
```

把 `.pm` 传到手机，在 App 的「模型」页点「导入」。

- 打包用 **store 模式不压缩**：权重已压缩，deflate 只浪费 CPU。
- **单个 zip entry 必须 < 4 GiB**（Android `ZipInputStream` 不支持 zip64，超了静默截断）。
  `--part-size`（默认 2 GiB）会自动切成 `<path>.partNN`，导入时按序合并，manifest 里仍只写一个 path。

## 模型包规范（schema 1）

| 字段 | 说明 |
|---|---|
| `id` / `name` / `version` / `description` | 标识与展示 |
| `kind` | `llm` \| `image` \| `video` |
| `runtime` | App 内置运行时 id；**留空 = 必须随包携带 `runtime/`** |
| `weights[]` | `{path, role, bytes}`，`role` = `main` / `te` / `vae` / `drafter` / `context` / `ple` … |
| `params[]` | 参数表单：`{key, label, type, default, min, max, step, options, help}`，`type` = `int`/`float`/`bool`/`enum`/`string` |
| `entry` | 启动描述，见下 |
| `requires` | `{ramMb, vaMb, abi, minAvailableMb}`，导入与启动前的门槛 |
| `env` | 追加给子进程的环境变量，支持 `{model}` / `{filesDir}` 占位 |
| `webui` | 包内页面路径，`null` 表示用 App 内置 |
| `prewarm` | 启动前顺序读一遍权重灌页缓存（默认 `true`） |

`entry` 关键字段：

| 字段 | 说明 |
|---|---|
| `bin` | 包内 `runtime/bin/` 下的可执行文件名 |
| `args[]` | 参数数组，占位符：`{model}` 包根、`{weight.<role>}` 权重路径、`{filesDir}`、`{<param key>}` |
| `portArg` | 端口以什么形式传给程序（如 `--listen-port`） |
| `healthPath` | 健康检查：HTTP 路径；`"tcp"` 表示只做 TCP 探活 |
| `readyTimeoutSec` | 启动就绪超时 |
| `stopMode` | `sigterm` / `stdin-close`，**必须和运行框架真实行为匹配** |

手写实例见 `build/qwen-image-split/manifest.json`（三段切分链，是仓库内唯一事实源）。

## WebUI 与接口

`core/WebGateway.kt` 起一层网关（默认 `0.0.0.0:7860`，被占则顺延），把只监听
`127.0.0.1` 的后端暴露给浏览器与局域网：

| 路径 | 说明 |
|---|---|
| `GET /` | 内置 WebUI（`<modelDir>/webui/index.html` 优先，没有才用 `assets/webui/index.html`） |
| `GET /health` | 网关 + 后端状态，页面每 3 s 轮询 |
| `POST /api/completion`、`GET /api/props` | 同源反代到后端（绕开 CORS） |
| `GET /v1/models`、`POST /v1/chat/completions` | OpenAI 兼容层，可直接接第三方客户端 |

入口：服务卡片的「打开 WebUI」（运行中才出现）+ 顶栏地球图标。

⚠️ 模型包里一旦有 `webui/index.html`，就**永久盖住** assets 那份 —— 之后改 assets 对该模型无效。

## 接一个新的运行框架

```bash
python tools/pack_model.py runtime /path/my-model \
    --from /path/to/bin --exe llama-server \
    --exclude 'libggml-htp-*' --version "llama.cpp b10144"
```

它把 ELF 分到 `runtime/bin`（可执行）与 `runtime/lib`（`*.so`），并把 `entry.bin` 写成真实文件名。
启动时 `RuntimeResolver` 优先用包内的，`LD_LIBRARY_PATH` 自动指向 `runtime/lib` 与 nativeLibraryDir。

**三类文件不能放包里，必须进 APK 的 `jniLibs`**（这是 targetSdk 35 + DSP 的双重约束）：

| 必须进 `jniLibs` | 原因 | 症状 |
|---|---|---|
| 入口可执行文件（改名 `lib<name>.so`） | 私有目录 **禁止 exec**（SELinux `app_data_file`） | `EPERM (error=13)` |
| DSP applet（QNN skel、hexagon applet） | 由 adsprpc 守护进程打开，进不去 0700 私有目录 | QNN init 成功但 context create 失败 / `0x80000406` |
| `libOpenCL.so` | 不在公共 native 库白名单，`uses-native-library` 对它无效 | `library "libOpenCL.so" not found` |

包里那份入口二进制**不是废文件**：`RuntimeResolver` 会把它换成指向 nativeLibraryDir 的符号链接，
让「按相对路径调自己」的运行时也能跑。

想内置到 APK 也可以（不推荐，换框架版本就要重发 App）：二进制改名 `lib<name>.so` 放进
`jniLibs/arm64-v8a/`，`core/RuntimeSpec.kt` 的 `Runtimes.ALL` 加一条，模型 manifest 的
`runtime` 填这个 id 且不带 `runtime/`。

### 自定义服务不是 HTTP 怎么办

`llama-split` 的 `--serve` 是行协议（`PING`/`INFO`/`CHAT`/`TOKN`/`GEN`），不是 HTTP。
模型包声明 `entry.healthPath = "tcp"`，网关据此走 `core/SplitClient.kt` 自己说话，
`/api/completion` 与 `/v1/chat/completions` 照常可用。

它的多个 stage 是 `fork` 出来的，父进程没装 SIGTERM handler —— 只杀父进程会留下孤儿继续占 DSP。
`ServiceInstance.terminateChain()` 按「cmdline 含模型目录」找出整条链，先 TERM 子进程再 TERM 父进程，
**绝不用 `-9`**（会泄漏 fastrpc PD，只能重启设备）。

## 平台约束（都在代码里处理了，改代码前先知道）

- **`ADSP_LIBRARY_PATH` 只能有一个目录**（包内 `runtime/skel`）。多给一个，QNN 会改走显式
  `openSession(uri=...)`，DSP 侧按名字找不到 skel → `0x80000442`，而报错完全误导。
- **不要自带 `libcdsprpc.so` / `libvmmem.so` / `vendor.qti.hardware.dsp@1.0.so`**。
  副本会被优先加载，而它的 HIDL 依赖在 `(default)` namespace 里解析不了 → `loadRemoteSymbols failed 4000`。
  正确做法是让 `LD_LIBRARY_PATH` 带上 `/system/lib64:/vendor/lib64`，用系统那份。
- `<uses-native-library>` 只作用于 App 自身 classloader，**对 `execve` 出来的子进程无效**。
- **运行框架若在私有目录开 shm / 写日志，必须用 `env` 指到 `{filesDir}`**，
  默认 `/data/local/tmp/...` 对 App 不可写。
- `-ngl 0` 挡不住 OpenCL 后端，还要加 `-dev none`；`libomp.so` / `libOpenCL.so` /
  `libggml-opencl.so` 属于运行框架的依赖闭包，要随包补齐。
- ColorOS 熄屏会掐断应用网络（连 `127.0.0.1` 也断），且熄屏时 `adb install` 被静默拦截
  （`Failure [-99]`）—— 排查前先解锁屏幕。

## 目录

```
app/src/main/java/dev/pocketmodel/studio/
├── MainActivity.kt              入口，起前台 Service（含三个调试 extra）
├── core/
│   ├── ModelManifest.kt         模型包 schema（kotlinx.serialization）
│   ├── RuntimeSpec.kt           内置运行时定义 + 参数展开（ArgsBuilder）
│   ├── RuntimeResolver.kt       ★ 决定执行哪个 exe：随包 runtime/ 优先，内置兜底
│   ├── ExecProbe.kt             W^X 能力自检
│   ├── ModelRepository.kt       导入 / 分片合并 / 校验 / 删除 / 占用统计
│   ├── ServiceInstance.kt       ★ 进程监管：exec / env / 日志 / 健康检查 / 停止
│   ├── WebGateway.kt            局域网网关 + OpenAI 兼容层
│   ├── SplitClient.kt           行协议后端的客户端
│   └── ServiceHost.kt           ForegroundService + StudioCore 单例
└── ui/                          StudioApp / ModelsScreen / SettingsScreen / Theme

app/src/main/jniLibs/arm64-v8a/   ★ 入口二进制唯一来源（libsd_server / libqnn_dit / libchainrun
                                   + QNN 2.51 库、libOpenCL、libomp），16 个 .so 共 67 MB
app/src/main/assets/webui/        内置 WebUI（单文件、无外链、离线可用）
branding/                         logo 可编辑源文件（svg）+ 预览页，不进 APK
build/qwen-image-split/           模型包 staging 的手写源：manifest.json + runtime/VERSION
tools/pack_model.py               模型包工具（init / runtime / scan / check / pack / list）
```

调试入口（跳过 UI 自动化）：

```bash
adb shell am start -n dev.pocketmodel.studio/.MainActivity --ez exec_probe true   # W^X 自检
adb shell am start -n dev.pocketmodel.studio/.MainActivity --es start_model <id>  # 直接起服务
adb shell am start -n dev.pocketmodel.studio/.MainActivity --ez stop_all true     # 全停
```

## 仓库范围

本仓库只管**代码与包规范**，不管**权重与分发产物**（20 GB 量级）。

| 入库 | 说明 |
|---|---|
| `app/src/**` | Kotlin 源码、`AndroidManifest.xml`、`res/`、`assets/webui/` |
| `app/src/main/jniLibs/arm64-v8a/*.so` | 入口二进制的唯一来源（其中 40 MB 是 `libsd_server.so`） |
| `build/qwen-image-split/manifest.json`、`runtime/VERSION` | 手写源文件，不是生成物 |
| `tools/*.py` | 打包工具 |

不入库：权重（gguf / safetensors）、`runtime/` 副本、打好的 `.pm`、APK、`app/build/`、`.gradle/`。

**`libsd_server.so` 的来源**：`sd-split` 仓库的 `build-hex/bin/sd-server`，strip 后改名。
三段切分链的 C++ 代码在那边，**不要在本仓库改 C++**：

```bash
cd /path/sd-split && JOBS=4 BUILD=build-hex ./build.sh hexagon
llvm-strip -o /tmp/srv build-hex/bin/sd-server
cp /tmp/srv /path/pocket-model-studio/app/src/main/jniLibs/arm64-v8a/libsd_server.so
```

## 已知限制

- **部署状态不持久**：`StudioCore.instances` 只在内存里，App 重启后要重新「部署」再开开关。
- **熄屏断网**：ColorOS 会掐断应用网络，目前只能解锁后使用。
- **模型包是明文 zip**：导入即执行包内任意二进制，没有签名与来源校验。
- **网关多轮靠拼接**：后端只吃一个裸 prompt，历史被压成一段文字塞进同一个 user turn。
- **无多实例仲裁**：同时开两个大包会 OOM / 抢 DSP。

## 致谢

- [llama.cpp](https://github.com/ggml-org/llama.cpp) 与 `ggml-hexagon` 后端
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp)（Qwen-Image DiT 链路）
- Qualcomm QNN SDK（HTP 后端）
