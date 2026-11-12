package dev.pocketmodel.studio.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

enum class ServiceState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

/** manifest 没声明 requires.ramMb 时的兜底门槛。这么点内存连最小模型都跑不动，直接拒绝比硬上更好。 */
private const val DEFAULT_MIN_AVAILABLE_MB = 1024

/**
 * 当前可用内存（MB），读 `/proc/meminfo` 的 `MemAvailable`（已把可回收的页缓存算进去）。
 * 读不到时返回 0，调用方按「不检查」处理 —— 不能因为读不到内存就拒绝启动。
 */
private fun readMemAvailableMb(): Int = try {
    File("/proc/meminfo").useLines { lines ->
        lines.firstOrNull { it.startsWith("MemAvailable:") }
            ?.removePrefix("MemAvailable:")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.toLongOrNull()
            ?.let { (it / 1024).toInt() }
    } ?: 0
} catch (e: Exception) {
    0
}

/**
 * 一个服务实例 = 一个原生子进程 + 一个端口 + 一份参数。
 *
 * 三条硬约束（都是踩出来的）：
 * 1. 可执行文件的位置：本 App 的 targetSdk=28，SELinux 域允许 exec app 私有目录，
 *    所以运行框架可以随模型包放在 <modelDir>/runtime/；没有随包时才用 nativeLibraryDir
 *    里的内置二进制（以 lib*.so 命名）。
 * 2. QNN 的 skel 路径必须显式给 ADSP_LIBRARY_PATH，否则报 0x80000406。
 * 3. 停止只用「关 stdin → SIGTERM」，绝不 SIGKILL：kill -9 设备侧 QNN 进程会漏 fastrpc PD
 *    （pdId=2 err 1009），只能重启手机。
 */
class ServiceInstance(
    val id: String,
    val manifest: ModelManifest,
    val builtin: RuntimeSpec?,
    val modelDir: File,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ServiceState.STOPPED)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    var params: Map<String, String> = defaultParams(manifest)

    /** 运行时来源的描述，start() 解析后写入，给 UI 显示 */
    var resolvedLabel: String = builtin?.displayName ?: "随包运行框架"
        private set

    private var process: Process? = null
    private var readers: List<Job> = emptyList()
    private var health: Job? = null
    private val buffer = CopyOnWriteArrayList<String>()
    private var logFile: File? = null

    fun append(line: String) {
        buffer.add(line)
        while (buffer.size > MAX_LOG_LINES) buffer.removeAt(0)
        _log.value = buffer.toList()
        logFile?.appendText(line + "\n")
    }

    fun start(ctx: Context, preferredPort: Int) {
        if (_state.value == ServiceState.RUNNING || _state.value == ServiceState.STARTING) return
        _failure.value = null
        _state.value = ServiceState.STARTING
        // 启动过程含页缓存预热（秒级 IO），绝不能占着调用方线程（通常是主线程）
        scope.launch(Dispatchers.IO) { doStart(ctx, preferredPort) }
    }

    private fun doStart(ctx: Context, preferredPort: Int) {
        // 启动前内存检查：宁可拒绝启动，也别让它在内存已经被占满时一路 thrash 到被系统清掉。
        //
        // 11-12 事故：三个 5 小时 25 分前残留的 sd-server 进程一直占着 13.4 GB dma-buf，
        // 整机 MemAvailable 只剩 1 GB。新服务照样启动、照样把 4 GB 权重读一遍，于是三方
        // 一起进入 swap thrash（pswpout 累计 51 GB、pgscan_direct 4.2 TB），最后被 ColorOS
        // 的 o-kill 整片清掉，连百度输入法都被杀了两轮 —— 用户看到的是「点一下分辨率就卡死」。
        //
        // 门槛取 manifest.requires.minAvailableMb（qwen-image 分段链声明 4096），没声明就兜底 1024。
        val needMb = manifest.requires.minAvailableMb.takeIf { it > 0 } ?: DEFAULT_MIN_AVAILABLE_MB
        val availMb = readMemAvailableMb()
        if (availMb > 0 && availMb < needMb) {
            fail(
                "可用内存不足：${availMb} MB，需要 ${needMb} MB。" +
                    "多半是上一轮服务没退干净（残留进程会一直占着 dma-buf）—— 先在设置里停止，或重启设备再试。"
            )
            return
        }

        val resolved = try {
            RuntimeResolver.resolve(ctx, manifest, modelDir, builtin)
        } catch (e: Exception) {
            fail(e.message ?: "运行时解析失败")
            return
        }
        val exe = resolved.exe
        resolvedLabel = "${resolved.label} · ${resolved.detail}"

        val port = preferredPort
        _port.value = port

        val args = try {
            ArgsBuilder(manifest, modelDir.absolutePath, params, ctx.applicationInfo.nativeLibraryDir).build(port)
        } catch (e: Exception) {
            fail("参数展开失败：${e.message}")
            return
        }

        val cmd = ArrayList<String>(args.size + 8)
        cmd.add(exe.absolutePath)
        cmd.addAll(args)
        cmd.add(manifest.entry.portArg)
        cmd.add(port.toString())

        logFile = File(ctx.filesDir, "logs/${id}.log").apply {
            parentFile?.mkdirs()
            writeText("")
        }
        append("[studio] 运行时 ${resolved.label}（${resolved.detail}）")
        append("[studio] exe ${exe.absolutePath}")
        append("[studio] ${cmd.joinToString(" ")}")

        if (manifest.prewarm) prewarmWeights(manifest, modelDir)

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        val env = pb.environment()
        val ld = resolved.libDirs.joinToString(":") { it.absolutePath }
        if (ld.isNotEmpty()) env["LD_LIBRARY_PATH"] = ld
        resolved.env.forEach { (k, v) -> env[k] = v.replace("{filesDir}", ctx.filesDir.absolutePath) }
        // manifest 声明的环境变量优先于内置运行时（模型包知道自己的目录布局）
        manifest.env.forEach { (k, v) ->
            var s = v
                .replace("{model}", modelDir.absolutePath)
                .replace("{filesDir}", ctx.filesDir.absolutePath)
            // {param} 占位：取本次启动实际使用的参数值。
            // 例：混推包要 `KVBR_CTX={n_ctx}` 才能让 decode 侧的 ctx 跟 UI 里改的一致
            //（子进程 kvbr 从 hybrid 继承环境变量，hybrid 自己没有 -c 的转发逻辑）。
            Regex("""\{([a-zA-Z0-9_]+)\}""").findAll(s).forEach { m ->
                params[m.groupValues[1]]?.let { s = s.replace(m.value, it) }
            }
            env[k] = s
        }
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        env["HOME"] = ctx.filesDir.absolutePath

        val p = try {
            pb.start()
        } catch (e: Exception) {
            fail(startError(e))
            return
        }
        process = p

        readers = listOf(scope.launch(Dispatchers.IO) {
            // ★ 必须兜住：stop() 里 p.destroy() 后子进程的管道会立刻失效，
            //   正在 readLine 的这里会抛 IOException(EBADF/Stream closed)。
            //   以前没接住，协程异常会冒到默认 handler → **整个 App 崩掉**
            //   （现象：切模型时先 stop 再 start，App 直接消失、日志里是
            //    ServiceInstance.doStart$4.invokeSuspend 的 readLine 栈）。
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { append(it) }
                }
            } catch (_: Throwable) {
                // 进程已退出/流已关闭，属正常收尾
            }
        })

        scope.launch(Dispatchers.IO) {
            val code = p.waitFor()
            readers.forEach { it.cancel() }
            if (_state.value != ServiceState.STOPPING) {
                _state.value = ServiceState.FAILED
                _failure.value = "进程退出（code=$code），见日志"
            } else {
                _state.value = ServiceState.STOPPED
            }
            append("[studio] 进程退出 code=$code")
        }

        health = scope.launch(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + manifest.entry.readyTimeoutSec * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                if (probe(port, manifest.entry.healthPath)) {
                    _state.value = ServiceState.RUNNING
                    append("[studio] 健康检查通过，端口 $port")
                    return@launch
                }
                if (!p.isAlive) return@launch
                delay(700)
            }
            if (isActive && _state.value == ServiceState.STARTING) {
                _state.value = ServiceState.FAILED
                _failure.value = "健康检查超时（${manifest.entry.readyTimeoutSec}s）"
            }
        }
    }

    fun stop() {
        android.util.Log.i("Studio", "stop($id) process=${process != null} state=${_state.value}")
        val p = process ?: run { _state.value = ServiceState.STOPPED; return }
        _state.value = ServiceState.STOPPING
        health?.cancel()

        // stopMode 决定用哪种礼貌退出：只有真读 stdin 的服务才值得关管道等它自己收尾，
        // 否则（如 studio-llm 用 cpp-httplib）关 stdin 毫无作用，只会白等超时。
        val viaStdin = manifest.entry.stopMode == "stdin-close"

        scope.launch(Dispatchers.IO) {
            try {
                if (viaStdin) {
                    runCatching { p.outputStream.close() }
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroy()   // SIGTERM
                        p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } else {
                    // 先收拾整条链，再收父进程。llama-split 的三个 stage 是 fork 出来的，
                    // 父进程没装 SIGTERM handler：只杀父进程会留下三个孤儿（ppid=1）继续占着
                    // DSP 会话（fastrpc PD 泄漏，只能重启手机）。
                    // 顺序与手工停机时验证过的一致：先 TERM 子进程，再 TERM 父进程。
                    terminateChain(modelDir.absolutePath)
                    p.destroy()       // SIGTERM，绝不 destroyForcibly
                    p.waitFor(6, java.util.concurrent.TimeUnit.SECONDS)
                }
            } catch (_: Exception) {
            } finally {
                process = null
                _state.value = ServiceState.STOPPED
            }
        }
    }

    /**
     * 按「命令行里含模型目录」找整条链的进程。
     *
     * 为什么不用 Process.pid()：android.jar 里没有它（Java 9 的接口没进 platform），
     * 反射 ProcessImpl.pid 又被隐藏 API 拦住。而 `/proc/<pid>/cmdline` 对同 uid 的进程
     * 是可读的，模型目录路径又足够唯一，所以按它筛最稳。
     */
    private fun findChainPids(marker: String): List<Int> {
        val me = android.os.Process.myPid()
        return runCatching {
            File("/proc").list()?.mapNotNull { it.toIntOrNull() }?.filter { it != me }?.filter { pid ->
                runCatching {
                    File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).contains(marker)
                }.getOrDefault(false)
            } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 给整条链发 SIGTERM。绝不用 -9：设备侧 QNN/fastrpc 进程被强杀会漏 PD，
     * 之后只能重启手机（台账 E-016 同类事故）。
     */
    private fun terminateChain(marker: String) {
        val pids = findChainPids(marker)
        if (pids.isEmpty()) return
        append("[studio] 停止整条链 ${pids.joinToString(" ")}")
        pids.forEach { k ->
            runCatching { Runtime.getRuntime().exec(arrayOf("/system/bin/kill", "-TERM", k.toString())) }
        }
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline && findChainPids(marker).isNotEmpty()) {
            Thread.sleep(100)
        }
        val left = findChainPids(marker)
        if (left.isNotEmpty()) append("[studio] 仍有进程未退出：${left.joinToString(" ")}")
    }

    private fun fail(msg: String) {
        _state.value = ServiceState.FAILED
        _failure.value = msg
        append("[studio] ERROR $msg")
    }

    /**
     * 顺序读一遍权重，把它们钉进页缓存。
     *
     * 为什么必须做：llama 用 mmap 加载，冷页时每次缺页都要回读闪存（可能还是 zram 解压），
     * 实测同一模型同一参数，冷 2.5~3.5 t/s / 热 8.4 t/s。这不是「优化」，是正确性前提 ——
     * 不预热的表现会让用户以为运行框架有问题。
     */
    private fun prewarmWeights(manifest: ModelManifest, modelDir: File) {
        val files = manifest.weights
            .map { File(modelDir, it.path) }
            .filter { it.isFile }
            .toMutableList()
        // 运行框架里的分段链（chain/kvbank）和 so 也是启动路径上的冷读，
        // 混推一次要读 200 MB+，一并灌热
        File(modelDir, RuntimeResolver.RUNTIME_DIR).let { rt ->
            listOf("table", "kvbank", "lib", "skel").forEach { sub ->
                File(rt, sub).takeIf { it.isDirectory }?.walkTopDown()
                    ?.filter { it.isFile }?.forEach { if (it.length() < 64L shl 20) files.add(it) }
            }
        }
        if (files.isEmpty()) return

        val t0 = System.currentTimeMillis()
        val buf = ByteArray(1 shl 20)
        var total = 0L
        for (f in files) {
            runCatching {
                f.inputStream().use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                    }
                }
            }
        }
        val ms = (System.currentTimeMillis() - t0).coerceAtLeast(1)
        val mb = total / (1024 * 1024)
        val rate = total * 1000 / ms / (1024 * 1024)
        append("[studio] 预热页缓存 $mb MB / $ms ms（${rate} MB/s）")
    }

    /** 把 exec 失败翻译成能直接照做的提示 */
    private fun startError(e: Exception): String {
        val m = e.message ?: e.javaClass.simpleName
        return if (m.contains("error=13") || m.contains("Permission denied")) {
            "系统拒绝执行该二进制（EPERM）。本 App 需 targetSdk≤28 才能从私有目录 exec；" +
                "若已改高 targetSdk，请把运行框架放回 APK 的 jniLibs（走内置运行时）。原始错误：$m"
        } else {
            "启动失败：$m"
        }
    }

    /**
     * 健康检查。
     *
     * `healthPath == "tcp"` 表示后端不是 HTTP（llama-split 的 --serve 是行协议），
     * 只做一次 TCP 连接：能连上就说明它在监听，再往下问就要实现整条协议了，
     * 那件事归网关（[SplitClient]），不归探活。
     *
     * HTTP 分支必须直连 127.0.0.1：URL.openConnection() 会走系统 ProxySelector，
     * 设备上只要配过 Wi-Fi 代理，对本地回环的请求也会被塞给代理，结果拿回 502 ——
     * 服务明明已经起来了，UI 却一直停在「启动中」。（在 PC 上用 curl 复现过同样的 502。）
     */
    private fun probe(port: Int, path: String): Boolean = try {
        if (path == "tcp") {
            java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 1200) }
            true
        } else {
            val url = URL("http://127.0.0.1:$port$path")
            (url.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection).run {
                connectTimeout = 1200
                readTimeout = 1200
                requestMethod = "GET"
                val ok = responseCode in 200..499
                disconnect()
                ok
            }
        }
    } catch (_: Exception) {
        false
    }

    companion object {
        const val MAX_LOG_LINES = 3000
    }
}
