package dev.pocketmodel.studio.core

import android.content.Context
import java.io.File

/** 运行框架的来源 */
enum class RuntimeSource {
    /** 模型包自带（<modelDir>/runtime/），版本随模型走 */
    EMBEDDED,
    /** App 内置（jniLibs → nativeLibraryDir） */
    BUILTIN,
}

/**
 * 「这次启动到底执行哪个二进制」的最终答案。
 *
 * 背景（两次实测，见 docs/架构.md §9.2 与 docs/Qwen-Image-APK-复现手册.md §3）：
 * - targetSdk 34：exec filesDir 里的二进制直接 `EPERM (error=13)`。
 * - targetSdk 28：可 exec（`untrusted_app_27` 域），但本 ROM 只给 `untrusted_app` 域
 *   放行 DSP，QNN 跑不起来。
 * - 现状 targetSdk 35：DSP 可用，exec 私有目录被禁 —— 所以入口二进制放进 APK 的
 *   jniLibs（`lib<name>.so`），从 nativeLibraryDir 启动；包内同名的子工具用符号链接
 *   指过去。模型包只提供库、skel、链文件和权重。
 *
 * 结论：不要试图靠调 targetSdk 让「包内二进制直接 exec」成立，两个域各封一边。
 */
data class ResolvedRuntime(
    val source: RuntimeSource,
    val exe: File,
    val libDirs: List<File>,
    val env: Map<String, String>,
    val label: String,
    val detail: String,
)

class RuntimeResolveException(message: String) : Exception(message)

object RuntimeResolver {

    /** 模型包内运行框架的目录约定 */
    const val RUNTIME_DIR = "runtime"
    private const val SUB_BIN = "bin"
    private const val SUB_LIB = "lib"
    private const val SUB_SKEL = "skel"

    /**
     * 解析顺序：模型包内嵌 → App 内置。
     * 内嵌时 entry.bin 按「文件名」找；内置时按 RuntimeSpec.bins 的「逻辑名」映射。
     */
    fun resolve(
        ctx: Context,
        manifest: ModelManifest,
        modelDir: File,
        builtin: RuntimeSpec?,
    ): ResolvedRuntime {
        embedded(ctx, manifest, modelDir)?.let { return it }

        if (builtin == null) {
            throw RuntimeResolveException(
                "模型既没有自带运行框架（$RUNTIME_DIR/），运行时标识 ${manifest.runtime} 也不被 App 内置支持"
            )
        }
        val binName = builtin.bins[manifest.entry.bin]
            ?: throw RuntimeResolveException("内置运行时 ${builtin.id} 没有定义入口 ${manifest.entry.bin}")

        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val exe = File(nativeDir, binName)
        if (!exe.exists()) {
            throw RuntimeResolveException(
                "缺少内置可执行文件 $binName。这个模型需要随包携带 ${RUNTIME_DIR}/，或在 App 中内置该运行时"
            )
        }
        return ResolvedRuntime(
            source = RuntimeSource.BUILTIN,
            exe = exe,
            libDirs = buildList {
                add(nativeDir)
                builtin.libDirs.forEach { d -> File(ctx.filesDir, d).takeIf { it.isDirectory }?.let(::add) }
            },
            env = builtin.env,
            label = builtin.displayName,
            detail = "内置 ${builtin.version}",
        )
    }

    /** 模型包内的运行框架：<model>/runtime/bin/<exe>，库在 <model>/runtime/lib 或同目录 */
    private fun embedded(ctx: Context, manifest: ModelManifest, modelDir: File): ResolvedRuntime? {
        val rt = File(modelDir, RUNTIME_DIR)
        if (!rt.isDirectory) return null

        val binDir = File(rt, SUB_BIN).takeIf { it.isDirectory } ?: rt
        // entry.file 显式指定文件名；否则用 entry.bin
        val fileName = manifest.entry.file ?: manifest.entry.bin
        // targetSdk 29+ 禁止 exec app 私有目录的文件，但 nativeLibraryDir（APK 的 lib/）可以。
        // 约定：包里的可执行文件在打包时以 lib<name>.so 的形式同时放进 jniLibs，
        // 这里优先用 APK 里那份；其余（.so 依赖、skel、ctx、权重）仍从模型包读。
        // jniLibs 里的文件名不能带 '-'，打包时统一换成 '_'（studio-llm-hybrid → libstudio_hybrid.so）
        val soName = "lib" + fileName.replace('-', '_') + ".so"
        val nativeExe = File(ctx.applicationInfo.nativeLibraryDir, soName)
        val exe = when {
            nativeExe.isFile -> nativeExe
            else -> File(binDir, fileName).let { if (it.exists()) it else File(binDir, manifest.entry.bin) }
        }
        if (!exe.isFile) return null

        // 模型包经 zip 解压后执行位可能丢失，这里补上（app 拥有文件，无需 root）。
        // 注意不能只补入口：混推链里 layerrun / mk_kvb / kvbr_cpu 都是主程序 fork 出来的
        // 子进程，缺执行位时表现为「运行时找不到工具」而不是权限错误，很难查。
        binDir.listFiles()?.forEach { f ->
            if (f.isFile && !f.canExecute()) f.setExecutable(true, false)
        }
        if (!exe.canExecute()) {
            exe.setExecutable(true, false)
            if (!exe.canExecute()) throw RuntimeResolveException("无法为 ${exe.name} 设置执行位")
        }

        // 子工具也要能 exec。私有目录被 SELinux 禁止执行（EPERM），但这些子工具是被主程序
        // 按相对路径调用的（`./chainrun`、`<rt>/qnn_dit`），改不了调用方就只能换文件本身：
        // 把包内同名文件替换成指向 nativeLibraryDir 的符号链接。execve 跟符号链接走，
        // SELinux 校验的是目标的 apk_data_file 标签，因此放行。
        runCatching {
            linkToNative(ctx.applicationInfo.nativeLibraryDir, listOf(rt, binDir, File(rt, "rt")))
        }

        val libDirs = buildList {
            File(rt, SUB_LIB).takeIf { it.isDirectory }?.let(::add)
            if (binDir != rt) add(binDir)   // 允许二进制与 .so 混放
            add(File(ctx.applicationInfo.nativeLibraryDir))
            // /system/lib64 + /vendor/lib64 必须在 nativesLibraryDir 之后、但必须在 LD 里：
            // libQnnHtpV73Stub.so 的 DT_NEEDED 有 libcdsprpc.so，而 linker 的 (default)
            // namespace 不允许直接去 /vendor/lib64 找库（报 "not accessible for the
            // namespace"）。把它列进 LD_LIBRARY_PATH 之后才可解析，且必须用厂商那份——
            // 自带副本会导致 openSession 0x80000442。gemma 的混推服务也是这么设的。
            add(File("/system/lib64"))
            add(File("/vendor/lib64"))
            // libc++_shared.so 只在 /system_ext/lib64（不在 /system/lib64，实测）。
            // llama-split 的 DT_NEEDED 里有它，缺了就起不来。
            add(File("/system_ext/lib64"))
        }
        val skel = File(rt, SUB_SKEL).takeIf { it.isDirectory }
        val env = buildMap {
            // ADSP_LIBRARY_PATH 一定要带上 nativeLibraryDir：skel 是由 adsprpc 守护进程
            // 打开并下发到 DSP 的，读的不是本进程的身份，而 App 私有目录是 0700 ——
            // 守护进程进不去。放在 APK jniLibs 里那份（系统解压到 0755 目录）才读得到。
            // 症状很隐蔽：QNN 初始化成功，但 context create 返回 invalid config（0x36b1）。
            // ADSP_LIBRARY_PATH 只放「包内 skel 目录」——不要拼 nativeLibraryDir。
            // 实测多一个目录会让 QNN 改走「显式 openSession(uri=file:///libQnnHtpV73Skel.so)」
            // 那条路并失败 0x80000442；只给一个目录时它按 DSP 默认方式加载 skel，
            // 日志里直接出现 getSkelBuildId / verifySkelVersion 通过。
            // 包内没有 skel 时才退回 nativeLibraryDir（那份是系统解压出来的 0755 目录）。
            val skelDirs = buildList {
                // files/adsp_extra 里可以再插额外的 skel 搜索目录（冒号分隔）。
                // 排查时用它热切路径，不必重装 APK。
                File(ctx.filesDir, "adsp_extra").takeIf { it.isFile }
                    ?.readText()?.trim()?.split(":")?.filter { it.isNotBlank() }
                    ?.let { addAll(it) }
                if (skel != null) add(skel.absolutePath) else add(ctx.applicationInfo.nativeLibraryDir)
            }
            put("ADSP_LIBRARY_PATH", skelDirs.joinToString(":"))
            // 让运行时能调用同目录的兄弟程序（如 llama-server 调 llama-quantize）
            put("PATH", (libDirs + binDir).joinToString(":") { it.absolutePath } +
                ":/system/bin:/system/xbin")
            // QNN 的 HTP 日志（chainrun 的 logCb 读到这个值才输出）。默认 ERROR；
            // 排查 deviceCreate / createFromBinary 失败时临时调到 4（VERBOSE）：
            //   adb shell run-as dev.pocketmodel.studio sh -c 'echo 4 > files/qnnlog'
            // 用文件开关而不是属性，是为了不重装 APK 也能切换。
            val qnnLog = File(ctx.filesDir, "qnnlog").takeIf { it.isFile }
                ?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            if (qnnLog != null) put("QNN_LOG_LEVEL", qnnLog)
        } + extraEnv(ctx)   // files/extra_env 可覆盖任意变量（排查用，后写者胜）
        val versionFile = File(rt, "VERSION")
        val version = if (versionFile.isFile) versionFile.readText().trim() else "随包"
        return ResolvedRuntime(
            source = RuntimeSource.EMBEDDED,
            exe = exe,
            libDirs = libDirs,
            env = env,
            label = "随包运行框架",
            detail = "$version · ${exe.name}",
        )
    }

    /** UI 用：这个模型会不会带运行框架 */
    fun hasEmbedded(modelDir: File): Boolean = File(modelDir, RUNTIME_DIR).isDirectory

    /**
     * 把模型包里的子工具替换成指向 APK 的符号链接。
     *
     * 规则：对给定目录里的每个普通文件 X，如果 nativeLibraryDir 里有 libX.so，就
     * 删掉包内那份、建一个符号链接过去。找不到对应 lib 的文件（权重、配置）原样保留。
     *
     * 为什么需要：targetSdk 29+ 不允许执行 app 私有目录里的文件，但某些运行时（Qwen-Image
     * 的 sd.cpp → qnn_dit → chainrun）是把子工具路径写死成相对路径的，改不了。
     * 符号链接是唯一既不动调用方、也不动 17 GB 模型包的办法。
     *
     * @return 实际建立/确认的链接数
     */
    private fun linkToNative(nativeDirPath: String, dirs: List<File>): Int {
        val nativeDir = File(nativeDirPath)
        var n = 0
        dirs.distinct().filter { it.isDirectory }.forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (!f.isFile && !isSymlink(f)) return@forEach
                if (f.name.endsWith(".so")) return@forEach
                val target = File(nativeDir, "lib${f.name.replace('-', '_')}.so")
                if (!target.isFile) return@forEach
                runCatching {
                    val linkPath = f.toPath()
                    if (java.nio.file.Files.isSymbolicLink(linkPath) &&
                        java.nio.file.Files.readSymbolicLink(linkPath).toString() == target.absolutePath
                    ) {
                        n++; return@forEach
                    }
                    f.delete()
                    android.system.Os.symlink(target.absolutePath, f.absolutePath)
                    n++
                }
            }
        }
        return n
    }

    private fun isSymlink(f: File): Boolean = java.nio.file.Files.isSymbolicLink(f.toPath())

    /**
     * files/extra_env 里的 KEY=VALUE 会覆盖上面的默认环境变量，用于现场排查
     * （改一行、重启服务即可生效，不用重装 APK）：
     *   adb shell run-as dev.pocketmodel.studio sh -c 'printf "LD_LIBRARY_PATH=/x:/y\n" > /data/data/dev.pocketmodel.studio/files/extra_env'
     */
    private fun extraEnv(ctx: Context): Map<String, String> {
        val f = File(ctx.filesDir, "extra_env")
        if (!f.isFile) return emptyMap()
        return f.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
    }
}
