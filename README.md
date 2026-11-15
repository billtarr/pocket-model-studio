# Pocket Model Studio

端侧模型服务管理器（Android）。把 **图片 / 视频 / 大语言模型** 三类本地推理服务
统一成「一个模型包 + 一个开关 + 一个端口」，思路对标 LM Studio，但跑在手机上。

> 名字是暂定的。包名 `dev.pocketmodel.studio`。
>
> 📒 **改代码或排查前先看结论台账**：`C:\Users\normin\WorkBuddy\2026-10-27-09-59-46\docs\LEDGER.md`
> （51 条一行一句话，带状态：现行 / 推翻 / 待验 / 禁止）。本 README 讲「是什么」，
> 台账讲「什么成立、什么已经试死」。

---

## 现在有什么

| 模块 | 状态 |
|---|---|
| Compose UI（服务 / 模型 / 设置三页） | ✅ |
| 模型包规范 `manifest.json`（schema 1） | ✅ |
| 模型导入（SAF 选 .pm/.zip → 校验 → 落地） | ✅ |
| 进程监管（exec / 环境注入 / 日志 / 健康检查 / 优雅停止） | ✅ |
| **运行框架随模型包**（`runtime/` 优先，内置兜底） | ✅ 实测 APK 不装任何原生库也能跑 |
| 三类服务的 runtime 抽象（llm / image / video） | ✅ 骨架，`qnn-*` 二进制待打包 |
| 参数表单（由 manifest 的 params schema 动态渲染） | ✅ |
| PC 端打包工具 `tools/pack_model.py`（含 `runtime` 子命令） | ✅ |
| 首个可用服务端到端跑通 | ✅ App 内启动 → 预热 → 健康检查 → 生成，全链路 |
| **混推：NPU prefill + CPU decode** | ✅ App 内跑通，模型包 7.96 GB 已打包导入；decode **11.3~11.6 t/s**（见下节） |
| 自研服务层 `studio-llm`（MTP / 贪心 / SSE） | ✅ 但 MTP 当前接受率 0.39，默认关闭 |
| 熄屏保活（WakeLock + 电池白名单入口） | ⚠️ 部分：CPU 不休眠，但 ColorOS 仍掐网络 |

## 混推：NPU prefill + CPU decode（2026-11-07，已跑通）

原来这套链路是 PC 上的 `serve_llm.py` 编排的（torch 算 embed/PLE → adb 推给手机 →
`layerrun` 跑 NPU → 拉回 KV → `kvbr` decode）。手机上跑不了 Python + torch，
现已全部搬到端侧的一个进程里：

```
studio-llm-hybrid
  ├─ 启动：加载 gguf 一次（tokenizer + PLE 张量，mmap 与 decode 进程共享页缓存）
  ├─ 启动：拉起常驻 kvbr_cpu --serve（stdin/stdout 管道驱动）
  └─ 每个请求：
       tokenize（gemma4 模板）
       → 算 x0 / ple_L（ggml，**只算真实 token**）
       → layerrun（QNN / Hexagon NPU，前 24 层出 KV）
       → mk_kvb 拼 .kvb
       → kvbr_cpu decode（CPU 4 线程）
```

新增两个工具（`D:/llm-npu-vk/llama-old/examples/`）：
- **`sl-pre`**：替代 PC 的 `prepare()`，用 gguf + ggml 重算 embed/PLE → `x0.bin` / `ple_L.bin`
- **`studio-llm-hybrid`**：上面的编排 + HTTP（`/health` `/props` `/completion`）

### 打包成 PLM（`dist/plm-gemma4-e4b.pm`，**7.96 GB / 96 文件**）

运行框架随包，但**可执行文件除外**：`targetSdk 35` 只允许从 nativeLibraryDir 执行，
所以四个二进制同时进 APK 的 jniLibs，包内同名文件在启动时被换成软链（几乎不占体积）。
包内布局：

```
manifest.json
weights/e4b-q4_0.gguf              5.19 GB
runtime/                           2.78 GB
  bin/  studio-llm-hybrid / layerrun / mk_kvb / kvbr_cpu   （运行时变软链）
  ctx/  h00_w8.bin … h23_w8.bin    24 个，T=1024 / W8A16，2.46 GB
  lib/  12 个 .so：ggml 全套 + libomp + libc++_shared + libOpenCL
        + libQnnHtp / libQnnSystem / libQnnHtpV73Stub
        ★ 不含 libcdsprpc.so（必须用系统 /vendor/lib64 那份，见 Qwen 手册 §3.2）
  skel/ 3 个：libQnnHtpV73Skel / libQairtHtpV73Skel / libQnnHtpNetRunExtensions
  table/ 47 个：cos/sin ×4 + ple_<L> ×42 + x0.bin（59 MB）
  kvbank/ base.kvb（4 层）+ swa.kvb（20 层）+ logits_imp.f32（58 MB）
```

`entry.args` 用 `{model}` 指包根、`{weight.main}` 指权重、`{n_ctx}`/`{threads}` 指可调参数。

> ★ 构建 / 打包 / 部署 / 验收的完整步骤见 **`C:/Users/normin/WorkBuddy/2026-10-27-09-59-46/docs\30-gemma4-e4b\hybrid-runbook.md`**。

**11 t/s 这个数字的来历**：早期在 `/data/local/tmp/plmtest` 手工搭同一布局时实测
启动 12 s、两轮 **11.04 / 10.16 t/s**；App 域后来的记录是 **9.23 t/s**（口径偏低，已不采信）。
**2026-11-07 20:33 复测定版：App 域 11.3~11.6 t/s**（16 tok 生成，8 次请求一致），
与本表 11.34/11.21 吻合 —— 下表数据现在是可信的。

### 实测（8 Gen 2 / Android 16 / Q4_0 5.19 GB）

| 项 | 第一次 | 第二次 |
|---|---|---|
| prompt | 19 token | 28 token |
| 端侧 tokenize + PLE（`pre_ms`） | 201 ms | 252 ms |
| **NPU prefill（`npu_ms`，24 层 / 1024 位置）** | 7523 ms | 7431 ms |
| KV 桥（`kv_ms`） | 316 ms | 338 ms |
| **CPU decode（`decode_ms`）** | 4234 ms / 48 tok | 3569 ms / 40 tok |
| **decode 速度** | **11.34 t/s** | **11.21 t/s** |
| 端到端 | 12.3 s | 11.6 s |

- NPU prefill **等效吞吐 ≈136 tok/s**（1024 位置 / 7.52 s，含 context create 等固定开销；
  PC 实验里单看纯 exec 阶段是 325 tok/s、约 3.15 s，剩下 ~4.8 s 是固定开销 —— 手册 §7.2）。
  CPU prefill 只有 6.3~12.5 tok/s，NPU 仍快 10 倍以上。
- decode **11.2~11.3 t/s**，比 `studio-llm` 直跑 Q4_0（5.6 t/s）快一倍 —— 差别在
  `kvbr_cpu` 用的那份 `libggml-cpu.so`（5012776 字节）比 build-oldcl 的（5003776）快
- 输出正确：中文连贯且切题（不是退化的复读或语言漂移）

### 三个关键点

1. **不能用 `llama_chat_apply_template`**。gemma4 的新控制符 `<|turn>` / `<turn|>` 不在
   llama 的内置模板表里，套出来只有 11 token（应为 19），模型收到非对话格式会直接退化
   （输出「请提供您的回答」，或语言漂移到印地语）。必须手写
   `<bos><|turn>user\n{prompt}<turn|>\n<|turn>model\n` 再 tokenize（`add_special=false`）。
   **判据：ids 必须等于 `[2,105,2364,107,237105,…,236924,106,107,105,4368,107]`。**
2. **PLE 只算真实 token**（padding 补 0）。1024 全算 6.4 s，19 个 token 只要 0.14 s，
   layerrun 输入仍是固定 1024，但 padding 位的 KV 会被丢弃。
3. **两个进程**：`layerrun` 每次请求跑完就退出（DSP context 必须释放给下次），
   decode 常驻。模型 mmap 同一文件，物理内存只有一份。

### 打包踩的六个坑（都已在代码/包里修掉）

1. **skel 认错版本**：`layerrun` 是 dlopen `libQnnHtp.so`，它要的是 **`libQnnHtpV73Skel.so`**，
   不是 Qairt 那一份。只带 `libQairtHtpV73Skel.so` 的表现是
   `DspTransport.openSession qnn_open failed, 0x80000442` → `Failed to load skel, error: 1002`，
   而 layerrun 仍然返回 0、还打印一个假的 2340 tok/s（空跑）。**判断标准是看它有没有跑出
   `L0/L1/...` 逐层日志。**
2. **`adb pull <dir> <dst>/` 会多套一层**：结果 `runtime/kvbank/kvbank/`、`runtime/table/chain1024q2/`，
   而 hybrid 找的是 `--tpl`/`--table` 目录下**直接**那批文件。打包前必须 `ls` 确认层级。
3. **执行位不是只给入口**：`layerrun` / `mk_kvb` / `kvbr_cpu` 是主程序 fork 出来的子进程，
   解压后丢了执行位会表现成「工具跑不动」而不是权限报错。`RuntimeResolver` 现在把
   `runtime/bin/*` 整目录补 `chmod +x`。
4. **`{model}` 占位符曾被通用循环吃掉**：`ArgsBuilder` 先跑 `\{(\w+)\}` 把所有花括号替换成
   参数值，`{model}`/`{port}` 查不到就变空串。现在先替换目录类占位符再走通用循环。
5. **`pack_model.py` 用 `read_bytes()` 会把 5 GB 权重读进内存**（直接 OOM），已改成 8 MB 流式写。
6. **decode 卡死会永久挂住服务**：`read()` 没有超时，一旦 kvbr 卡在生成里，请求永不返回且持锁。
   现在用 `poll` 1 s 轮询、180 s 超时，超时就把 decode 子进程整组 `SIGKILL` 并重建，
   返回可重试的错误而不是砖掉。

### ★ 在 App 里真正跑通 NPU：五个坑（2026-11-07，已解决）

`adb shell` 里跑得好好的 QNN，搬进 App 就报
`loadRemoteSymbols 4000 → Failed to load skel 4000 → 14001`。
这串报错长得像"权限被 ROM 封了"，实际是两个完全不同的原因叠在一起：

**1. `libQnnHtpV73Stub.so` 依赖 vendor 的 `libcdsprpc.so`（FastRPC 通往 DSP 的唯一通道），
而 targetSdk 31+ 的 app 默认看不到 vendor 库。**
`manifest` 的 `<application>` 里必须声明（注意位置，放在 `<manifest>` 下会 AAPT 报错）：

```xml
<uses-native-library android:name="libcdsprpc.so" android:required="false" />
<uses-native-library android:name="libadsprpc.so" android:required="false" />
```

⚠️ 注意这条声明的**作用范围**：它只给 App 自身的 classloader namespace 用。
本项目的推理进程是 `execve` 起来的，落在 `(default)` namespace，实测**不生效**
（探针显示 `permitted_paths` 里没有 `/vendor/lib64`）。真正起作用的是下面第 2 条。
声明保留无害，别指望它解决 exec 子进程的链接问题。

**2. 光声明还不够，`/vendor/lib64` 必须在 `LD_LIBRARY_PATH` 里。**
所以 layerrun 的启动命令固定写成
`LD_LIBRARY_PATH=runtime/lib:/system/lib64:/vendor/lib64`。

**3. 千万别把 `libcdsprpc.so` 的副本放进模型包。**
打包时我"贴心地"拷了一份 vendor 库进 `runtime/lib`，结果副本被优先加载 → 又回到 4000。
删掉副本、交给系统解析，立刻就通了。**这是个自伤陷阱。**

**4. `libOpenCL.so` 不在公共 native 库白名单**，`uses-native-library` 对它无效
（`CANNOT LINK EXECUTABLE ... library "libOpenCL.so" not found`），必须放进 APK 的 jniLibs。

**5. 可执行文件只能从 nativeLibraryDir 跑（targetSdk 29+ W^X）**，
jniLibs 里的文件名不能带 `-`，打包时换成 `_`，`RuntimeResolver` 按同一规则反查
（`studio-llm-hybrid` → `libstudio_llm_hybrid.so`）。**这个命名不一致白花了一轮。**

顺带确认的事实（用来缩小范围，别再重复试）：

| 变量 | 结论 |
|---|---|
| targetSdk 28 / 34 / **35** | 35 才落 `untrusted_app`（和 LocalDream 同域），但**域不是根因** |
| skel 放哪 | `filesDir` 即可（DSP 读得到）；`nativeLibraryDir` 也行 |
| QNN 版本 2.39 / 2.50 / 2.51 | **都能跑**，之前"只有 2.39 能跑"是误判，真凶是 libcdsprpc |
| `run-as` 测 DSP | 域是 `runas_app`，**结论不可信**，必须用真实 App 进程 |

**App 内实测（untrusted_app 域，QNN 2.51）**：启动 30 s（预热 5.1 GB / 0.6 s），
decode **9.23 t/s**，NPU prefill 3.7 s，输出正确。

### 导入大包的进度反馈（2026-11-07）

用户反馈：「导入时模型页没有任何进度，只能看着占用空间变大」——确实是个真 bug，两处都改了：

1. **`emit` 的位置错了**。原代码在 `zip.copyTo(out)` **之后**才报一次进度，而模型包里的权重
   是单个几 GB 的 entry → 整个拷贝期间进度永远是 0%，结束瞬间跳到 100%，等于没有进度条。
   改成 1 MB 一块的 `read`/`write` 循环内报进度（节流 120 ms）。
2. **顺手把 7 GB 的二次写入去掉了**：解压到 `cacheDir` 后用 `renameTo` 搬进 `filesDir`
   （同一分区，纯元数据操作）。原来无条件 `copyRecursively`，等于把 7 GB 又写一遍。

UI 侧：顶部一张横幅卡片，分四阶段（准备 / 解压 / 安装 / 校验），显示
`已读 / 总大小 · 百分比 · 实测速度 · 剩余时间`，可取消（取消会删掉半成品目录）。
细节：进度回调来自 IO 线程，必须 post 到主线程再写 Compose state；进度条要显式给
`trackColor`（默认轨道色与 `secondaryContainer` 撞色，卡片里只看得见一个小点），
并 `gapSize = 0.dp` + `drawStopIndicator = {}` 去掉末端圆点。

实测（SM8550，6 GB 假包）：`2.65 GB / 6.00 GB · 44% · 1601 MB/s · 约剩 2 秒`，
约 6 s 完成；取消路径提示「已取消导入，未完成的数据已清理」，`cache/` 与 `models/` 均无残留。

### WebUI 与局域网访问（2026-11-07）

后端（`studio-llm-hybrid`）只监听 `127.0.0.1`，只有 `/health` `/props` `/completion` 三个接口，
没有首页。要做「App 里直达 WebUI」+「局域网能访问」，两条路：

- 改后端：重编译 ARM 二进制 + 重打 7.4 GB 模型包，用户还得重新导入一次。**没走这条。**
- 在 App 里加一层网关（`core/WebGateway.kt`）：APK 覆盖安装即可，模型包完全不动。

网关绑 `0.0.0.0:7860`（被占则顺延），对外提供：

| 路径 | 说明 |
|---|---|
| `GET /` | 内置 WebUI：**`<modelDir>/webui/index.html` 优先，没有才用 `assets/webui/index.html`**（单文件无外链，离线可用） |
| `GET /health` | 网关 + 后端状态，WebUI 每 3 s 轮询，后端没起来时页面会自己说清楚 |
| `POST /api/completion` `GET /api/props` | 同源反代到后端（绕开 CORS，页面不用跨端口） |
| `GET /v1/models` `POST /v1/chat/completions` | OpenAI 兼容层，可直接接第三方客户端 |

入口：服务卡片上的「打开 WebUI」（RUNNING 时才出现）+ 顶栏地球图标（任何时候可开）；
App 内用 WebView 打开，局域网地址就在卡片和设置页上，点一下复制。

改 WebUI 的顺序（2026-11-11 实测）：

- **只改 UI 就只重装 APK**，权重包不用动。当前主包 `dist/qwen35-9b-split.pm` 里
  `manifest.webui = null`、没有 `webui/` 目录，页面 100% 来自 assets。
- **模型包里一旦有 `webui/index.html`，就永久盖住 assets 那份** —— 之后改 assets 对这个模型
  不再生效，改的是「改了没反应」的经典原因。
- `pack_model.py init --webui` 只写占位页，不会把 assets 那份拷进包；`pack` 只收目录里已有的 `webui/`。
- 首页有进程内缓存（`htmlCache` 按绝对路径 key，只读一次），换文件后要**重启 App 进程**，
  刷新浏览器无效；重装 APK 会因为杀进程自然生效。

**几个坑：**

1. **WebView 里 `height:100%` 不可靠**。`useWideViewPort` + `loadWithOverviewMode` 下，
   body 会退化成内容高度 → 聊天区被压成 0、输入框顶到标题下面。
   必须用 `position: fixed; inset: 0` + `#chat { flex:1 1 auto; min-height:0 }`。
2. **进度/状态回调要 post 到主线程**（和导入进度同一个坑）。
3. **网关要放在前台 Service 里启动**，放 Activity 会在退出 App 时随之消失，局域网就断了。
4. **多轮只能「拼接历史」**：后端 `/completion` 只吃一个裸 prompt 并自己套单轮模板，
   想让模型看到上下文只能把历史压成一段文字塞进同一个 user turn。真正的多轮要改后端。

**实测（PC 经 Wi-Fi 访问手机 192.168.1.8:7860）**：
`/api/completion` 17 tok prompt + 48 tok 生成 → 10.4 s（NPU 3.4 s / decode 9.5 t/s）；
`/v1/chat/completions` 返回标准 OpenAI 结构并附 `timings`；`stream:true` 也能收尾。

⚠️ **已知问题（后端，不是网关）**：`kvbr_test.cpp` 的 decode 是**纯 argmax 贪心**，
没有 temperature / repetition penalty / stop token，于是会出现
`Ready to answer your question.ure.ure.ure…` 这种循环，以及 `<|channel>thought` 之类
控制串泄漏。修它要改 `kvbr_cpu` 并重打模型包，暂未做。

### Qwen-Image 在 APK 内跑通 QNN（2026-11-07，★ 关键配方）

> 完整复现步骤（路径、编译命令、打包、验收判据、故障对照）见 **`C:/Users/normin/WorkBuddy/2026-10-27-09-59-46/docs\20-qwen-image-qnn\apk-runbook.md`**。本节只留结论与两条硬约束。

**结论：512²/8 步 `generate_image` 194.4~268.4 s（21.7~33.5 s/it，5 次实测区间），
App 进程内 QNN 八段链直出，出图正确。** 波动主因**未定位**（37 ℃ 那次反而最慢，
「降频」解释已作废）—— 手册 §7。

**320×320 变体（2026-11-08）**：同一套代码/权重，DiT 的图像 patch 网格改成 20×20
（seq 1152 → 528），11 个 context 全部重导，出图 **154.5 s / 19.3 s/it**，画质正常。
独立包 `qwen-image-2.1-qnn-320`（17.9 GB），可与 512² 包并存。完整步骤见手册 **§11**。
注意 exec 降到 1/3 但 create 只降 38%（每前向仍付 2.8 s），所以只快了 20~42% ——
**create 是固定税，这是逐段链在 v73 上的天花板**。
4 步能出但画面糊（Qwen-Image 2.1 冷 prompt 至少要 8 步）。
⚠️ **跑的时候 App 必须在前台、屏幕常亮**：退到后台会被 ColorOS 冻结，单步从 21 s 掉到 150~226 s
（实测同一请求 352.9 s），详见复现手册 §7。

App 沙箱里让 QNN 见到 DSP，踩了四层，前三条是「让库能加载」，最后一条才是「让会话能建立」：

| 现象 | 根因 | 处理 |
|---|---|---|
| 私有目录 exec → EPERM | targetSdk 35 禁止执行 app_data_file | 二进制进 jniLibs；包内同名文件做符号链接指向 nativeLibraryDir |
| `dlopen libQnnHtp.so` 拿到的不是随包那份 | exec 出来的进程是 `(default)` linker namespace，`permitted_paths` **不含 `/vendor/lib64`** | 2.51 库全部进 APK jniLibs |
| `libQnnHtpV73Stub.so` 加载失败（err 4000） | stub 的 `DT_NEEDED` 有 `libcdsprpc.so` | LD_LIBRARY_PATH 里加 `/system/lib64:/vendor/lib64`，用**厂商那份** |
| `openSessionForPriority` 0x80000442 | `ADSP_LIBRARY_PATH` 给了**多个**目录 | 只给**一个**：包内的 `runtime/skel` |

★ 两条硬约束（违反就是 0x36b1 / 0x80000442，且报错完全误导）：

1. **`ADSP_LIBRARY_PATH` 只能有一个目录**（模型包的 `runtime/skel`）。多给一个
   nativeLibraryDir，QNN 就会改走「显式 openSession(uri=`file:///libQnnHtpV73Skel.so`)」，
   DSP 侧按名字找不到 skel → 0x80000442；只给一个目录时它按默认方式加载，日志里能看到
   `getSkelBuildId=v2.51.200…` + `verifySkelVersion` 通过。
2. **不要自带 `libcdsprpc.so` / `libvmmem.so` / `vendor.qti.hardware.dsp@1.0.so`**。
   自己塞进 APK 会让 stub 用那份副本，而它的 HIDL 依赖在 `(default)` namespace 里解析不了
   （表现为 `loadRemoteSymbols failed 4000`）。正确做法是让 LD 里带上 `/vendor/lib64`
   用系统那份——**gemma 的混推服务本来就是这么设的**，这也是定位到这一点的对照物。

順带修掉两个坑：

- **`segs.txt` 缺了就自己推**：模型包常常只带 `<tag>_blk*.bin` 不带 `segs.txt`，
  默认列表是 3 层粒度（`blk0_3`）与包里的 4 层粒度对不上，报 `! open …/pv6_blk0_3.bin`。
  现在 chainrun 会扫 `out/` 按 `<tag>_blk*` 推导（日志 `segs(自动): 8 个 block 段`）。
- **qnn_dit 不再重复前插 LD/ADSP**：只在调用方没给时兜底，避免出现重复目录。

调试开关（都不需要重装 APK）：

```
files/qnnlog      → QNN_LOG_LEVEL（chainrun 的 logCb 输出 QNN 内部日志：skel/openSession/uri）
files/extra_env   → KEY=VALUE 覆盖任意环境变量
files/adsp_extra  → 额外楔入 skel 搜索目录（注意：会和约束 1 冲突，只用于排查）
```

### 还没解决

- **AnyFlow 进 APK**：设备侧只有 `farrun`（裸图执行器），采样循环/分块/TE/VAE 还在 PC，
  见下一节。QNN 会话这关已经过了（同一套配方），现在是编排要重写。
- **Qwen-Image 模型包没带 `segs.txt`**：chainrun 现在能自动推导，但源头应该打进包
  （`D:/tmp/qwen-pkg/runtime/segs.txt` 已补，下次重打包生效）。
- **部署状态不持久**：`StudioCore.instances` 只在内存里，App 重启后要重新「部署」再开开关。
- **短 prompt 用 NPU 不划算**：chain 固定 T=1024，19 token 的 prompt 也要付 **7.6 s**
  （2026-11-07 复测：10 / 159 / 629 token 的 `npu_ms` 都是 7.5~7.9 s，与长度无关）。
  应在真实 token 数偏低时回落到 llama 自己的 CPU prefill —— 交叉点按 7.6 s 重算应高于旧估的
  `~200`，但 CPU prefill 速度还没实测，**阈值待定**（手册 §7.3）。
- KV 不复用：每个请求都重跑一遍 24 层 NPU，多轮对话等于把历史重算 N 次。

### AnyFlow 为什么不能直接「搬上去」

`D:/wan-anyflow` 本机侧是真跑通的（256²/81 帧/4 步，~345 s），但它**不是端侧自洽的**：

- 设备上只有 `farrun`：一个常驻的**裸图执行器**，协议是「收 fp32 → 跑一次前向 → 回 fp32」。
- 采样循环、8 个 chunk 的分块与 mask、k/v 缓存搬运、UMT5 文本编码、VAE 解码，**全在 PC 的
  Python 里**（`dev_persist.py` 344 行 + `far_client.py` 291 行 + `recompute_ref.py` 184 行），
  通过 adb forward 逐 chunk 驱动。

所以「搬到 APK」不是打包问题，而是要先把这套编排逻辑重写成设备端程序（并解决 UMT5 22 GB
fp32 在手机上怎么量化/分批）。这是独立的一个项目，不是一版的活。

---

### 实测（SM8550 / 8 Gen 2 / Android 16 / 23 GB，2026-11-07）

| 项 | 结果 |
|---|---|
| APK | **66 MB，不含任何原生库**（运行框架在模型包里） |
| 运行框架体积 | 模型包内 `runtime/` 约 205 MB（llama.cpp 全家桶） |
| 启动耗时（App 内，含预热） | **约 12 s**：预热 7822 MB / 0.9 s，权重加载 ~10 s |
| `/health` | HTTP 200，**1.6 ms**（生成进行中也是这个量级） |
| 生成 · studio-llm 无投机 | **5.8 t/s**（172 ms/token） |
| 生成 · studio-llm 开 MTP n_max=3 | 2.25 t/s（接受率仅 0.39 → **负收益**，默认已关） |
| prefill | 6.3 t/s（**异常低**，642 token 要 100 s，待查，见下） |
| 停止服务（UI 开关） | **2 s**（stopMode=sigterm） |
| 原生 exec | 从 `<model>/runtime/bin/studio-llm` 直接启动，W^X 通过 |

⚠️ **降级说明**：昨夜命令行环境的 8.45 t/s 今天在 App 内复现不出来。同一二进制、
同一权重的裸跑基线也从 6.88 掉到 5.8 t/s，且 MTP 接受率从 0.89 掉到 0.39。
中核已跑满 2.32 GHz、CPU 不过热、页缓存已预热 —— 尚未定位，优先怀疑 ColorOS 的
uag 调速器对 app 域进程与 shell 域待遇不同。

九个必须记住的坑（都已在代码/文档里处理）：

1. **熄屏 = 断网，连 127.0.0.1 都断**。ColorOS 在屏幕关闭后掐断应用（含其子进程）的
   网络，表现为「端口还在 LISTEN，但任何连接都超时」，甚至 `adb forward` 也连不进去；
   解锁后立刻恢复。PARTIAL_WAKE_LOCK + Doze 白名单都绕不过，属于 ROM 策略。
   **排查时先看 `dumpsys power | grep mWakefulness`**，别去怀疑服务代码。
2. **targetSdk 28 默认禁止明文 HTTP**。App 内用 `HttpURLConnection` 打
   `http://127.0.0.1:7800/health` 会抛 `Cleartext HTTP traffic not permitted`，
   被 catch 吞掉后 UI 永远停在「启动中」。必须 `android:usesCleartextTraffic="true"`。
3. **冷页缓存能让速度差 2.4 倍**。llama 用 mmap，冷页时 2.5~3.5 t/s，热页 5.8~8.4 t/s。
   启动前顺序读一遍权重（8 GB 约 1~8 s）是必需的，`manifest.prewarm`（默认 true）。
4. **`onNewIntent` 不实现，调试 intent 会被静默丢弃**。Activity 已在前台时
   `am start --es start_model` / `--ez stop_all` 只走 onNewIntent，不重建 Activity。
5. **`stopMode` 要和运行框架匹配**。`stdin-close` 只对真读 stdin 的服务有效；
   `studio-llm` 用 cpp-httplib 不读 stdin，错配的结果是每次停止都白等 12 s 兜底。
6. **PC 上的系统代理会劫持 `127.0.0.1`**。`curl` 返回 `502 upstream connect failed`，
   Java 侧 `URL.openConnection()` 会走 ProxySelector —— 健康检查必须
   `openConnection(Proxy.NO_PROXY)`，curl 必须 `--noproxy '*'`。
7. **`-ngl 0` 挡不住 OpenCL**。新版 llama.cpp 会自行把 layer 分给 `GPUOpenCL`，
   实测只有 3.01 t/s；必须再加 **`-dev none`**，才回到 6.69 t/s。
8. **运行框架的依赖闭包要自己补齐**：`libomp.so`（从设备拉）、`libOpenCL.so`
   （`/vendor/lib64/`）、`libggml-opencl.so`（`DT_NEEDED`，不能删）。
   放进模型包的 `runtime/lib/` 即可，App 会把 `LD_LIBRARY_PATH` 指向那里。
9. **ColorOS 熄屏时 `adb install` 会被静默拦截**：`OplusPackageInstallInterceptManager:
   OPLUS_ADB_INSTALL_CANCEL`，只报 `Failure [-99]`。安装前必须先唤醒并解锁屏幕。


---

## 仓库范围（2026-11-12 起纳入 git）

这个仓库管**代码与包规范**，不管**权重与分发产物**（那两部分 20 GB 量级，且权重是
`D:\` 根目录大文件的硬链接）。`git init` 之后的边界：

| 入库 | 说明 |
|---|---|
| `app/src/**` | Kotlin 源码、`AndroidManifest.xml`、`res/`、`assets/webui/index.html` |
| `app/src/main/jniLibs/arm64-v8a/*.so` | **入口二进制的唯一来源**（targetSdk 35 只允许从 nativeLibraryDir 执行，见下文「接一个新的运行框架」）。合计 67 MB，其中 `libsd_server.so` 39 MB |
| `build/qwen-image-split/manifest.json` | 手写的模型包清单，**唯一事实源**（不是生成物） |
| `build/qwen-image-split/runtime/VERSION` | 同上，运行时版本标记 |
| `tools/*.py` | 打包工具 |

| 不入库 | 原因 |
|---|---|
| `build/qwen-image-split/weights/`（9.3 GB） | 硬链接到 `D:\qwen-image-2.1-turbo-Q4_K_M.gguf` 等，git 会把内容实体化 |
| `build/qwen-image-split/runtime/bin/`（39 MB） | `libsd_server.so` 的副本，真身已在 jniLibs |
| `dist/*.pm`（20 GB） | 打好的模型包；`pack_model.py pack` 可重新生成 |
| `app/build/`、`.gradle/`、`.kotlin/` | 构建产物 |
| `local.properties` | 本机 SDK 路径 |

**`libsd_server.so` 的来源**：`D:\sd-split` 的 `build-hex/bin/sd-server`，strip 后改名。
更新流程（三段切分链的代码都在 sd-split 的 `overlay/` 里，**不要在本仓库改 C++**）：

```bash
cd /d/sd-split && JOBS=4 BUILD=build-hex ./build.sh hexagon
llvm-strip -o /tmp/srv build-hex/bin/sd-server
cp /tmp/srv /d/pocket-model-studio/app/src/main/jniLibs/arm64-v8a/libsd_server.so
# 然后重打 APK（jniLibs 变化不会让 gradle 增量编译跳过，但装完要重启 App 才生效）
```

---

## 目录

```
app/src/main/java/dev/pocketmodel/studio/
├── MainActivity.kt              入口，起前台 Service（含三个调试 extra）
├── core/
│   ├── ModelManifest.kt         模型包 schema（kotlinx.serialization）
│   ├── RuntimeSpec.kt           内置运行时定义 + 参数展开（ArgsBuilder）
│   ├── RuntimeResolver.kt       ★ 决定执行哪个 exe：随包 runtime/ 优先，内置兜底
│   ├── ExecProbe.kt             W^X 能力自检（--ez exec_probe true 触发）
│   ├── ModelRepository.kt       导入 / 校验 / 删除 / 占用统计
│   ├── ServiceInstance.kt       ★ 进程监管：exec / env / 日志 / 健康检查 / 停止
│   └── ServiceHost.kt           ForegroundService + StudioCore 单例
└── ui/
    ├── StudioApp.kt             导航 + 服务页 + 日志弹窗
    ├── ModelsScreen.kt          模型页 + 导入 + 参数编辑
    ├── SettingsScreen.kt        运行时自检 + 存储
    └── Theme.kt

app/src/main/jniLibs/             ← ★ 入口二进制都在这儿：libsd_server / libqnn_dit / libchainrun
                                     + QNN 2.51 库、libOpenCL、libomp（见复现手册 §4.4）
examples/                         三个可直接打包的 manifest 样例
  └── gemma4-e4b-q8-mtp/runtime/  随包运行框架示例（205 MB）
tools/pack_model.py               模型包工具（init / runtime / scan / check / pack / list）
（文档已统一迁到 C:/Users/normin/WorkBuddy/2026-10-27-09-59-46/docs\，见该目录 README.md）

```

### 调试入口（跳过 UI 自动化）

```bash
adb shell am start -n dev.pocketmodel.studio/.MainActivity --ez exec_probe true   # W^X 自检
adb shell am start -n dev.pocketmodel.studio/.MainActivity --es start_model <id>  # 直接起服务
adb shell am start -n dev.pocketmodel.studio/.MainActivity --ez stop_all true     # 全停
```

---

## 构建

需要 JDK 17 + Android SDK（compileSdk 35）。本机工具链装在 `D:/android-tools/`：

```bash
export JAVA_HOME=/d/android-tools/jdk-17
export ANDROID_HOME=/d/android-tools/sdk
cd /d/pocket-model-studio
./gradlew assembleDebug        # 或 gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 做一个模型包

```bash
cd tools
python pack_model.py init  ../examples/my-model --kind llm --runtime llama-cpu-mtp --name "我的模型"
cp /d/gemma-4-E4B-Q8_0.gguf ../examples/my-model/weights/
python pack_model.py scan  ../examples/my-model      # 自动补 weights 列表（按文件名猜 role）
python pack_model.py check ../examples/my-model      # 校验：字段/文件/大小/runtime 入口
python pack_model.py pack  ../examples/my-model -o dist/my-model.pm
```

把 `.pm` 传到手机，在 App 的「模型」页点「导入」即可。

⚠️ 打包用 **store 模式不压缩**：权重本身已压缩，deflate 只浪费 CPU 和时间。

---

## 接一个新的运行框架

**运行框架随模型包走**（不需要发 App）。但它不是「全都能放包里」—— 有三类文件
受 Android 约束，必须留在 APK 的 `jniLibs`（2026-11-11 实测，三段切分链踩全了）：

```bash
python tools/pack_model.py runtime ./my-model \
    --from /d/llm-npu-vk/build-oldcl/bin --exe llama-server \
    --exclude 'libggml-htp-*' --version "llama.cpp b10144"
```

它会自动把 ELF 分到 `runtime/bin`（可执行）和 `runtime/lib`（`*.so`），
并把 `entry.file` 写成真实文件名。App 启动时 `RuntimeResolver` 优先用包内的，
`LD_LIBRARY_PATH` 自动指向 `runtime/lib` 与 nativeLibraryDir，执行位也会自动补上。

| 必须放 APK `jniLibs` | 原因 | 症状 |
|---|---|---|
| 入口可执行文件（改名 `lib<name>.so`） | targetSdk 35 下私有目录 **禁止 exec**（SELinux `app_data_file`） | `EPERM (error=13)` 起不来 |
| DSP applet（`libggml-htp-v73.so`、QNN skel） | 由 **adsprpc 守护进程** 按 `ADSP_LIBRARY_PATH` 打开，它进不去 0700 的私有目录 | QNN init 成功但 context create 失败 / `0x80000406` |
| 依赖闭包里的 `.so`（可选） | 这些只被 linker 读，放包里没问题；放多了浪费 App 体积 | — |

**包里那份入口二进制不是废文件**：`RuntimeResolver` 会把 `runtime/bin/<exe>` 换成
指向 nativeLibraryDir 的符号链接，从而让「按相对路径调自己」的运行时也能跑。

大包还有两条硬约束：

- **单个 zip entry 必须 < 4 GiB**。Android 的 `ZipInputStream` 不支持 zip64，
  超过就静默截断。`pack_model.py --part-size`（默认 2 GiB）会切成 `<path>.partNN`，
  导入时 `ModelRepository` 按序追加合并，manifest 里仍只写一个 path。
- **运行时若在私有目录开共享内存/日志，要用 env 指到 `{filesDir}`**。默认路径
  （`/data/local/tmp/...`）对 App 不可写，表现为 `open shm: Permission denied` 后立刻退出。

### 三段切分链（llama-split）的接入点

llama-split 的 `--serve` **不是 HTTP**，是行协议（`PING`/`INFO`/`CHAT`/`TOKN`/`GEN`）。
所以模型包要声明 `entry.healthPath = "tcp"`（只做 TCP 探活），网关据此走
`core/SplitClient.kt` 自己说话，`/api/completion` 与 `/v1/chat/completions` 都照常可用。

它的三个 stage 是 **fork** 出来的，父进程没装 SIGTERM handler —— 只杀父进程会留下
三个孤儿继续占 DSP（fastrpc PD 泄漏）。`ServiceInstance.terminateChain()` 按
「cmdline 里含模型目录」找出整条链，先 TERM 子进程再 TERM 父进程，绝不用 `-9`。

**不推荐：内置到 APK**（每换一个框架版本就要重发 App）

1. 可执行文件改名 `lib<name>.so` 放进 `app/src/main/jniLibs/arm64-v8a/`；
2. 依赖 `.so` 同目录；
3. `core/RuntimeSpec.kt` 的 `Runtimes.ALL` 加一条；
4. 模型的 `manifest.runtime` 填这个 id，且**不带** `runtime/` 目录。

---

## 下一步

1. **prefill 只有 6.3 t/s（最高优先级）**。642 token 要 100 s，长 prompt 基本不可用。
   `n_batch=512 / n_ubatch=64` 已生效，但 64 token 批只比单 token 快 16%，
   怀疑 GEMM 没摊销（repack 路径 / op_offload）。decode 无投机 5.8 t/s 是对齐的，
   所以问题只在批处理。
2. **MTP 接受率回归**：昨夜命令行 0.89 → 今天 App 内 0.39，同一二进制同一权重。
   需要弄清是「App/uid 域调度差异」还是「prompt/上下文差异」，再决定是否默认开。
3. **熄屏断网的产品化处理**：现在只能靠「解锁后可用」。要么引导用户把 App 加进
   ColorOS 的后台白名单（设置页已有电池优化入口，还需要「睡眠待机优化」提示），
   要么改用前台服务 + 常驻通知常亮屏幕的策略，需要真机验证哪种在目标 ROM 上真的有效。
4. `chainrun` 加 HTTP 服务层，图片/视频才能作为常驻服务被 API 调用。
5. 模型包签名与来源校验（现在 `.pm` 是明文 zip，会执行里面的任意二进制）。
6. 多实例资源仲裁（RAM / DSP VA 预算）。
7. SAF 导入流程真机走查（代码已写，只验证过命令行投放）。

设计细节与踩坑见 `C:/Users/normin/WorkBuddy/2026-10-27-09-59-46/docs\10-pms-apk\architecture.md`（文档已迁出本仓库）。
