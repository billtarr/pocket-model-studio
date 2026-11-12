package dev.pocketmodel.studio.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 模型包规范（schema 1）。
 *
 * 一个模型包 = 一个目录（或 .mlm 压缩包），必须含 manifest.json；
 * weights/ 放权重；可选 runtime/ 放**这个模型专用的运行框架**；可选 webui/ 放前端静态文件。
 *
 * 为什么运行框架随包走：不同模型对执行器版本极其敏感 —— Qwen-Image 要绑 QNN skel 版本与
 * soc_id，gemma4 要特定 commit 的 llama.cpp + MTP 头，视频模型又是另一套。
 * 把框架固定在 APK 里意味着每换一个模型就要重发一次 App，且新旧模型无法共存。
 *
 * 所以：runtime/ 优先；没有才回落到 App 内置运行时（RuntimeSpec）。
 */
@Serializable
data class ModelManifest(
    val schema: Int = 1,
    val id: String,
    val name: String,
    val kind: String,                 // llm | image | video
    /** App 内置运行时 id；留空表示「必须随包携带 runtime/」 */
    val runtime: String = "",
    val version: String = "0.0.0",
    val description: String = "",
    val weights: List<WeightEntry> = emptyList(),
    val params: List<ParamSpec> = emptyList(),
    val entry: EntrySpec,
    val requires: Requirement = Requirement(),
    /**
     * 追加给子进程的环境变量，支持 {model} / {filesDir} 占位。
     *
     * 为什么需要：有些运行时靠环境变量定位自己的资源（Qwen-Image 的 sd.cpp 用
     * SD_QWEN_QNN 指向可写工作目录、SD_QNN_DEV 指向链根目录）。这些路径都在模型包内，
     * 而内置运行时的 env 是全局的、不知道模型在哪，只能由 manifest 声明。
     */
    val env: Map<String, String> = emptyMap(),
    val webui: String? = null,
    /**
     * 启动前把权重顺序读一遍灌进页缓存。
     *
     * 实测（8 Gen 2 / 8 GB Q8_0）：冷页缓存只有 2.5~3.5 t/s，预热后 8.4 t/s —— 差 2.4 倍。
     * 原因不是算力，是 mmap 的随机缺页：权重走到哪读到哪，而回收过的页要从 zram 解压。
     * 顺序读 8 GB 约 8 s，换来常驻热量，比「冷加载 21 s」还划算，所以默认开启。
     * 模型小到能整块放进内存、或权重本来就在缓存里时，可以关掉省这几秒。
     */
    val prewarm: Boolean = true,
) {
    val kindEnum: ModelKind get() = ModelKind.from(kind)
}

@Serializable
data class WeightEntry(
    val path: String,
    val role: String,                 // main | drafter | context | vae | te | ple ...
    val bytes: Long = 0,
    val optional: Boolean = false,
)

@Serializable
data class ParamSpec(
    val key: String,
    val label: String,
    val type: String,                 // int | float | bool | enum | string
    val default: JsonElement,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<String> = emptyList(),
    val unit: String = "",
    val help: String = "",
) {
    fun defaultInt(): Int = (default as? JsonPrimitive)?.intOrNull ?: 0
    fun defaultFloat(): Float = (default as? JsonPrimitive)?.doubleOrNull?.toFloat() ?: 0f
    fun defaultBool(): Boolean = (default as? JsonPrimitive)?.booleanOrNull ?: false
    fun defaultString(): String = (default as? JsonPrimitive)?.content ?: ""
}

@Serializable
data class EntrySpec(
    /**
     * 入口标识。
     * - 随包运行框架：runtime/bin 下的文件名（如 llama-server）
     * - 内置运行时：RuntimeSpec.bins 里的逻辑名（如 server）
     */
    val bin: String,
    /** 显式指定 runtime/bin 下的文件名；留空时用 bin 本身 */
    val file: String? = null,
    val args: List<String> = emptyList(),   // 支持 {param} / {weight.role} 占位
    val portArg: String = "--port",
    val healthPath: String = "/health",
    val readyTimeoutSec: Int = 300,
    val stopMode: String = "stdin-close", // stdin-close | sigterm
)

@Serializable
data class Requirement(
    val ramMb: Int = 0,
    /**
     * 启动前要求的 `MemAvailable`（MB，含可回收页缓存），0 表示用 1024 兜底。
     *
     * 与 [ramMb] 的区别：ramMb 是「设备总内存够不够」的宣传口径，查了也没法拦 ——
     * 真正会出事的是**空闲不够**：残留的上一轮进程把 dma-buf 吃光时，设备仍有 12 GB 总内存，
     * 但可用只剩 1 GB，新服务照样加载 4 GB 权重，直接进入 swap thrash 被系统清掉。
     * 所以启动前要看的是这个值。
     */
    val minAvailableMb: Int = 0,
    val vaMb: Int = 0,
    val abi: String = "arm64-v8a",
)

enum class ModelKind {
    LLM, IMAGE, VIDEO;

    companion object {
        fun from(s: String): ModelKind = when (s.lowercase()) {
            "llm", "text", "language" -> LLM
            "image", "diffusion", "sd" -> IMAGE
            "video" -> VIDEO
            else -> LLM
        }
    }
}

val ManifestJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
    encodeDefaults = true
}
