package dev.pocketmodel.studio.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * W^X 可行性探针 —— 决定「运行框架能否随模型包分发」。
 *
 * 背景：Android 10 (API 29) 起，targetSdk >= 29 的 app 被 SELinux 禁止执行
 * app 私有目录（filesDir 等）里的文件（`neverallow appdomain app_data_file:file execute`）。
 * 所以现在的二进制必须放在 nativeLibraryDir（由系统解压 jniLibs，权限 755）。
 *
 * 这个探针把二进制复制到 filesDir、chmod 755、再 exec，分三步给出结论：
 *  1. exec 本身是否被允许（EPERM / "Permission denied" / SIGSYS）
 *  2. 依赖库能否从 filesDir dlopen（同一套 execute 限制）
 *  3. 完全脱离 nativeLibraryDir 能否跑起来（这是「runtime 随包」的最低条件）
 *
 * 结果写 logcat（tag=EXECPROBE）与 files/logs/exec-probe.txt，不依赖 UI。
 */
object ExecProbe {
    private const val TAG = "EXECPROBE"

    /** 主二进制 + 它的全部依赖：直接取 nativeLibraryDir 里所有 .so，避免手写列表漏项 */
    private fun binariesIn(nativeDir: File): List<String> =
        nativeDir.listFiles { f -> f.isFile && f.name.endsWith(".so") }
            ?.map { it.name }?.sorted() ?: emptyList()

    data class Step(val name: String, val ok: Boolean, val detail: String)

    data class Report(val steps: List<Step>, val verdict: String) {
        fun render(): String = buildString {
            appendLine("exec probe  (targetSdk=${android.os.Build.VERSION.SDK_INT} 设备)")
            steps.forEach { s ->
                appendLine("[${if (s.ok) " OK " else "FAIL"}] ${s.name}")
                s.detail.lines().filter { it.isNotBlank() }.take(6)
                    .forEach { appendLine("        $it") }
            }
            appendLine()
            appendLine("结论: $verdict")
        }
    }

    suspend fun run(ctx: Context): Report = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Step>()
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val work = File(ctx.filesDir, "exec-probe").apply { mkdirs() }

        // --- 步骤 1：复制并给执行位 ---
        val copied = mutableListOf<File>()
        val missing = mutableListOf<String>()
        binariesIn(nativeDir).forEach { name ->
            val src = File(nativeDir, name)
            val dst = File(work, name)
            runCatching {
                src.copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
                dst.setReadable(true, false)
                copied.add(dst)
            }.onFailure { missing.add("$name(${it.message})") }
        }
        steps += Step(
            "复制 ${copied.size} 个二进制到 filesDir",
            copied.isNotEmpty(),
            "目录: ${work.absolutePath}\n缺失: ${missing.ifEmpty { listOf("无") }.joinToString(", ")}",
        )
        val exe = File(work, "libllama_server.so")
        if (!exe.exists()) {
            return@withContext Report(steps, "无法复制二进制，探针中止").also { log(ctx, it) }
        }
        steps += Step(
            "权限位",
            exe.canExecute(),
            "canExecute=${exe.canExecute()}  长格式: ${statOf(exe)}",
        )

        // --- 步骤 2：只依赖 nativeLibraryDir（exec 从 filesDir，库从 native） ---
        val r2 = try {
            runProcess(listOf(exe.absolutePath, "--version"), mapOf(
                "LD_LIBRARY_PATH" to (nativeDir.absolutePath + ":/system/lib64:/vendor/lib64"),
            ))
        } catch (e: Exception) {
            "启动异常: ${e.javaClass.simpleName}: ${e.message}"
        }
        val ok2 = r2.contains("version") || r2.contains("build")
        steps += Step("exec 从 filesDir（库仍走 nativeLibraryDir）", ok2, r2.trim())

        // --- 步骤 3：库也全部从 filesDir 加载（完全脱离 APK） ---
        val r3 = try {
            runProcess(listOf(exe.absolutePath, "--version"), mapOf(
                "LD_LIBRARY_PATH" to work.absolutePath,
            ))
        } catch (e: Exception) {
            "启动异常: ${e.javaClass.simpleName}: ${e.message}"
        }
        val ok3 = r3.contains("version") || r3.contains("build")
        steps += Step("exec + 库全部走 filesDir（runtime 完全随包）", ok3, r3.trim())

        // --- 步骤 4：真实 App 进程域里跑 QNN（DSP 访问权限探针）---
        // 注意 run-as 的域是 runas_app，与真实的 untrusted_app_* 不同，
        // 用 run-as 测 DSP 会得到错误结论，必须在 App 进程里 fork 出去跑。
        val qnnExe = File(nativeDir, "liblayerrun.so")
        val modelWithWork = File(ctx.filesDir, "models").listFiles()
            ?.firstOrNull { File(it, "work/chainlive").isDirectory }
        if (qnnExe.isFile && modelWithWork != null) {
            val work = File(modelWithWork, "work")
            // QNN 2.50+ 改用 loadRemoteSymbols —— 由 DSP 侧自己打开 skel 文件，
            // 所以 skel 必须放在 DSP（vendor 域）读得到的地方。逐个位置试。
            val candidates = mutableListOf<Pair<String, File>>()
            // DSP 读不到 nativeLibraryDir，只有 filesDir 可读；skel 还必须 world-readable
            File(modelWithWork, "runtime/skel").takeIf { File(it, "libQnnHtpV73Skel.so").isFile }
                ?.let { d ->
                    d.listFiles()?.forEach { f -> runCatching { f.setReadable(true, false) } }
                    candidates += "filesDir" to d
                }
            candidates += "APK lib" to nativeDir
            File("/data/local/tmp/plmskel")
                .takeIf { File(it, "libQnnHtpV73Skel.so").isFile }
                ?.let { candidates += "tmp(shell)" to it }
            ctx.getExternalFilesDir(null)?.let { ext ->
                val dst = File(ext, "skel").apply { mkdirs() }
                listOf("libQnnHtpV73Skel.so", "libQnnHtpNetRunExtensions.so").forEach { n ->
                    File(nativeDir, n).takeIf { it.isFile }
                        ?.let { s -> runCatching { s.copyTo(File(dst, n), overwrite = true) } }
                }
                if (File(dst, "libQnnHtpV73Skel.so").isFile) candidates += "外部私有" to dst
            }

            for ((label, skelDir) in candidates) {
                // 优先用 2.51 的完整 24 段 ctx；只有单个时退回 2.50 那份
                val ctxDir = listOf("/data/local/tmp/anyflow/out", "/data/local/tmp/p250/ctx")
                    .map { File(it) }
                    .firstOrNull { File(it, "h00_w8.bin").isFile } ?: File("/nonexistent")
                val base = mutableListOf(
                    qnnExe.absolutePath, "--pipe", "0", "--crt", "2", "--hold", "8",
                    "--seq", "1024", "--pfx", "h",
                    "--in", File(work, "chainlive").absolutePath,
                    "--out", File(work, "lrlive").absolutePath,
                    "--to", if (File(ctxDir, "h01_w8.bin").isFile) "23" else "0",
                )
                if (ctxDir.isDirectory) base += listOf("--ctx", ctxDir.absolutePath)
                val r = try {
                    runProcess(
                        base,
                        mapOf(
                            "LD_LIBRARY_PATH" to (nativeDir.absolutePath + ":/system/lib64:/vendor/lib64"),
                            "ADSP_LIBRARY_PATH" to skelDir.absolutePath,
                        ),
                    )
                } catch (e: Exception) {
                    "启动异常: ${e.javaClass.simpleName}: ${e.message}"
                }
                val ok = r.contains("L0 exec") || r.contains("L23 exec")
                steps += Step("DSP · skel@$label", ok, r.takeLast(400))
                if (ok) break
            }
        } else {
            steps += Step("App 域访问 DSP（layerrun / QNN）", false,
                "跳过：liblayerrun.so=${qnnExe.isFile}，含 work/chainlive 的模型=${modelWithWork?.name}")
        }

        val dspOk = steps.lastOrNull { it.name.contains("DSP") }?.ok == true
        val verdict = when {
            !dspOk -> "DSP 不可用：App 域访问不了 NPU（NPU 链路对本 ROM 的第三方 app 关闭）"
            ok3 -> "DSP 可用 · 运行框架可完全随包并 exec filesDir"
            ok2 -> "DSP 可用 · 二进制可从 filesDir exec，依赖库仍需随 APK"
            else -> "DSP 可用 · 但必须走 nativeLibraryDir（runtime 要随 APK 安装）"
        }
        Report(steps, verdict).also { log(ctx, it) }
    }

    private fun log(ctx: Context, r: Report) {
        val text = r.render()
        text.lines().forEach { Log.i(TAG, it) }
        runCatching {
            File(ctx.filesDir, "logs/exec-probe.txt").apply { parentFile?.mkdirs() }
                .writeText(text)
        }
    }

    /** 同步跑子进程，合并 stdout/stderr，最多 20 s */
    private fun runProcess(cmd: List<String>, envExtra: Map<String, String>): String {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            putAll(envExtra)
            put("TMPDIR", System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroy()
            return "超时 20s\n$out"
        }
        return "exit=${p.exitValue()}\n$out"
    }

    private fun statOf(f: File): String = try {
        ProcessBuilder("ls", "-l", f.absolutePath).redirectErrorStream(true).start()
            .inputStream.bufferedReader().readText().trim()
    } catch (e: Exception) {
        "?(${e.message})"
    }
}
