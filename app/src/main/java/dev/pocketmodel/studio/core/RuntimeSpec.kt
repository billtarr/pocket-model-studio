package dev.pocketmodel.studio.core

import android.content.Context
import kotlinx.serialization.json.JsonPrimitive

/**
 * 运行时 = App 内置的执行框架与专有库（llama.cpp / QNN / NTP）。
 *
 * 模型包只声明 runtime id，二进制与 skel 都由 App 提供，所以「换框架」是升 App，
 * 而不是重新打包每个模型。原生可执行文件以 lib*.so 形式放在 jniLibs，
 * 安装时被解压到 nativeLibraryDir，只有那个目录允许 exec（Android 10+ W^X）。
 */
data class RuntimeSpec(
    val id: String,
    val displayName: String,
    val version: String,
    /** 逻辑名 -> jniLibs 里的文件名 */
    val bins: Map<String, String>,
    /** 额外 so 目录（相对 filesDir，可为空） */
    val libDirs: List<String> = emptyList(),
    /** assets 下需要解压的 skel 目录（如 hexagon-v73） */
    val skelAsset: String? = null,
    val env: Map<String, String> = emptyMap(),
    val note: String = "",
)

object Runtimes {
    private const val T = "runtimes"

    val ALL: List<RuntimeSpec> = listOf(
        RuntimeSpec(
            id = "llama-cpu-mtp",
            displayName = "llama.cpp (CPU + MTP)",
            version = "b10144",
            bins = mapOf(
                "server" to "libllama_server.so",
                "completion" to "libllama_completion.so",
                "mtp-bench" to "libmtp_bench.so",
                "kvbr" to "libkvbr_cpu.so",
            ),
            note = "4 线程 + dotprod + repack；MTP 需 n_max=3（batch 4）",
        ),
        RuntimeSpec(
            id = "qnn-prefill",
            displayName = "QNN prefill 链 (Hexagon v73)",
            version = "2.51",
            bins = mapOf(
                "layerrun" to "liblayerrun.so",
                "mk-kvb" to "libmk_kvb.so",
                "quantize" to "libllama_quantize.so",
            ),
            libDirs = listOf("$T/qnn251/lib", "$T/qnn251/skel"),
            skelAsset = "$T/qnn251/skel",
            env = mapOf("ADSP_LIBRARY_PATH" to "{filesDir}/$T/qnn251/skel"),
            note = "context binary 需与 soc_id 匹配；VA 窗口 2304 MB",
        ),
        RuntimeSpec(
            id = "qnn-diffusion",
            displayName = "QNN Diffusion (Qwen-Image)",
            version = "2.51",
            bins = mapOf(
                "chainrun" to "libchainrun.so",
            ),
            libDirs = listOf("$T/qnn251/lib", "$T/qnn251/skel"),
            skelAsset = "$T/qnn251/skel",
            env = mapOf("ADSP_LIBRARY_PATH" to "{filesDir}/$T/qnn251/skel"),
            note = "8 段换入，单段 0.90 GB；te=cpu 是硬要求",
        ),
    )

    fun byId(id: String): RuntimeSpec? = ALL.firstOrNull { it.id == id }
}

/**
 * 把 manifest 的 args 模板展开成真实命令行。
 * 支持 {param}、{weight.role}、{model}（模型根目录）、{port}。
 */
class ArgsBuilder(
    private val manifest: ModelManifest,
    private val modelDir: String,
    private val params: Map<String, String>,
    private val nativeLib: String = "",
) {
    fun build(port: Int): List<String> {
        val byRole = manifest.weights.associateBy { it.role }
        return manifest.entry.args.map { raw ->
            // 目录类占位符必须先替换：下面的通用循环会把 {model}/{port} 当成参数名，
            // 查不到就换成空串，后面的 replace 就再也匹配不到了。
            var s = raw.replace("{model}", modelDir).replace("{port}", port.toString())
                .replace("{nativeLib}", nativeLib)
            Regex("""\{weight\.([a-zA-Z0-9_]+)\}""").findAll(raw).forEach { m ->
                val role = m.groupValues[1]
                val w = byRole[role] ?: error("manifest 缺少 weight role=${role}")
                s = s.replace(m.value, "$modelDir/${w.path}")
            }
            Regex("""\{([a-zA-Z0-9_]+)\}""").findAll(s).forEach { m ->
                val key = m.groupValues[1]
                val v = params[key] ?: ""
                s = s.replace(m.value, v)
            }
            s
        }.also {
            // 参数里出现未知占位符时给它留个空串，避免把 "{}" 原样传进吧台
            if (it.any { a -> a.contains("{") && a.contains("}") }) {
                throw IllegalArgumentException("未解析的占位符: $it")
            }
        }
    }

    fun cliParams(): List<String> = params.entries
        .filter { (k, _) -> manifest.params.any { it.key == k } }
        .flatMap { (k, v) -> if (v.isBlank()) emptyList() else listOf("--$k", v) }
}

/** manifest 里 param 的默认值 -> 字符串，用于首次启动与「恢复默认」 */
fun defaultParams(m: ModelManifest): Map<String, String> =
    m.params.associate { p ->
        p.key to when (p.type) {
            "int" -> p.defaultInt().toString()
            "float" -> p.defaultFloat().toString()
            "bool" -> if (p.defaultBool()) "1" else "0"
            else -> (p.default as? JsonPrimitive)?.content ?: p.defaultString()
        }
    }
