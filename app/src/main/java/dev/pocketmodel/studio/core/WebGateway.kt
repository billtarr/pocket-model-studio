package dev.pocketmodel.studio.core

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本机 HTTP 网关：把「只听 127.0.0.1 的后端」变成一个能对内网、对浏览器说话的入口。
 *
 * 为什么要在 App 侧做，而不是改后端：
 * 1. 后端（studio-llm-hybrid）是随模型包发布的 ARM 二进制，改一行就要重编译 + 重打
 *    7.4 GB 的包，用户还得重新导入一次；网关在 APK 里，覆盖安装即可。
 * 2. 后端只实现了 /health、/props、/completion 三个接口，没有首页、没有 CORS 头。
 *    浏览器里的页面去 fetch 另一个端口的接口会被 CORS 拦，网关做同源反代直接绕开。
 * 3. 局域网访问必须 bind 0.0.0.0；后端绑定地址由 manifest 参数决定，不如网关自己绑。
 *
 * 对外暴露：
 *   GET  /                       内置 WebUI（assets/webui/index.html）
 *   GET  /health                 网关 + 后端状态（给 WebUI 轮询）
 *   POST /api/completion         反代到后端 /completion
 *   GET  /api/props              反代到后端 /props
 *   GET  /v1/models              OpenAI 兼容：模型列表
 *   POST /v1/chat/completions    OpenAI 兼容：转成后端的单轮 completion
 *
 * 后端是非流式的（一次请求跑完才返回），所以 stream=true 只会在结束时吐一个 delta +
 * [DONE]。客户端能用，但看不到逐字输出 —— 这是后端限制，不是网关省事。
 */
class WebGateway(private val ctx: Context) {

    @Volatile var port: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set

    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    /** 正在跑的行协议连接，`/cancel` 就是挨个往里写 CANCEL */
    private val activeGens: MutableSet<SplitClient> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** 后端端口由 StudioCore 注入：取当前 RUNNING 的那个实例 */
    var backendPort: () -> Int? = { null }
    /** 给 /health、/v1/models 用的展示名 */
    var backendLabel: () -> String = { "" }
    /** 服务类型（llm / image / video），决定 WebUI 显示对话还是生图 */
    var backendKind: () -> String = { "" }

    /**
     * 后端协议。`http` = llama-server / sd.cpp 那类直接能反代的；
     * `split` = `llama-split --serve` 的行协议（TOK/CHAT/GEN），必须自己说话，
     * 见 [SplitClient]。由 manifest 的 `entry.healthPath == "tcp"` 判定。
     */
    var backendProto: () -> String = { "http" }

    /**
     * 当前 RUNNING 实例的**生效参数**（键同 manifest.params.key）。
     * 网关用它取默认生成长度、上下文上限；UI 里改过的值也走这里，不会各说各话。
     */
    var backendParams: () -> Map<String, String> = { emptyMap() }
    /** 当前 RUNNING 实例的模型目录：有 `<dir>/webui/index.html` 就用它（模型专用 UI） */
    var backendDir: () -> String? = { null }
    /**
     * 当前模型支持的分辨率（来自 manifest 的 `size` 参数 options）。
     * WebUI 拿它重建下拉框 —— 否则 512² 包上也能选 320²，选了必然报错（链不匹配）。
     */
    var backendSizes: () -> List<String> = { emptyList() }

    /** key = html 的绝对路径（"builtin" 表示 APK assets） */
    private val htmlCache = HashMap<String, ByteArray>()
    private var lanIpCache: String? = null
    private var lanIpAt: Long = 0L

    val localUrl: String get() = "http://127.0.0.1:$port/"
    val lanUrl: String? get() = lanIp()?.let { "http://$it:$port/" }
    val started: Boolean get() = running.get()

    fun start(preferred: Int = DEFAULT_PORT): Boolean {
        if (running.get()) return true
        lastError = null
        // 端口被占就顺延，最多试 20 个（常见占用：其它 App 的调试服务、Gradio 的 7860）
        var bound: ServerSocket? = null
        for (p in preferred until preferred + 20) {
            bound = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", p), 64)
                }
            }.getOrNull()
            if (bound != null) {
                port = p
                break
            }
        }
        val ss = bound ?: run {
            lastError = "端口 $preferred~${preferred + 19} 全被占用"
            Log.w(TAG, lastError!!)
            return false
        }
        server = ss
        running.set(true)
        pool.execute {
            while (running.get()) {
                val sock = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "accept 失败: ${e.message}")
                    break
                }
                pool.execute { runCatching { handle(sock) }.onFailure { Log.w(TAG, "处理失败: ${it.message}") } }
            }
        }
        Log.i(TAG, "网关监听 0.0.0.0:$port（局域网 ${lanUrl ?: "无 IP"}）")
        return true
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
    }

    // ---------------------------------------------------------------- 请求处理

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 20_000
            val input = BufferedInputStream(s.getInputStream())
            val out = BufferedOutputStream(s.getOutputStream())

            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val parts = lines.firstOrNull()?.split(" ") ?: return
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val path = rawPath.substringBefore('?')
            val query = rawPath.substringAfter('?', "")

            val headers = HashMap<String, String>()
            lines.drop(1).forEach { l ->
                val i = l.indexOf(':')
                if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
            }

            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (len > 0) {
                val buf = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = input.read(buf, got, len - got)
                    if (n <= 0) break
                    got += n
                }
                buf
            } else null

            // 流式必须边走边写，不能先算出一个完整 Resp 再发（那又变回非流式了）
            if (method == "POST" && splitMode() && wantsStream(headers, body, query)) {
                if (path == "/api/completion") {
                    streamCompletionSplit(body, out, s)
                    return
                }
                if (path == "/v1/chat/completions") {
                    streamChatCompletions(body, out, s)
                    return
                }
            }

            val resp = when {
                method == "GET" && (path == "/" || path == "/index.html") -> asset()
                method == "GET" && path == "/favicon.ico" -> Resp(204, "text/plain", ByteArray(0))
                method == "GET" && path == "/health" -> health()
                method == "GET" && path == "/api/props" ->
                    if (splitMode()) propsViaSplit() else forward("GET", "/props", null)
                method == "POST" && path == "/api/completion" ->
                    if (splitMode()) completionViaSplit(body) else forward("POST", "/completion", body)
                // 停止正在跑的请求：WebUI 的「停止」按钮，与 serve_http.py 的 /cancel 同义
                (method == "POST" || method == "GET") && path == "/cancel" ->
                    json(200, """{"ok":${cancelAll() > 0}}""")
                // 图像服务的 A1111 接口原样透传：/api/sdapi/v1/txt2img、/api/sdapi/v1/progress
                path.startsWith("/api/sdapi/") -> forward(method, path.removePrefix("/api"), body)
                method == "GET" && path == "/v1/models" -> models()
                method == "POST" && path == "/v1/chat/completions" -> chatCompletions(body, headers["accept"], query)
                else -> json(404, """{"error":"not found: $path"}""")
            }
            write(out, resp, s)
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            last4 = (last4 shl 8) or (b and 0xff)
            if (last4 == 0x0d0a0d0a) return sb.toString()   // \r\n\r\n
            if (sb.length > 64 * 1024) return null
        }
    }

    private fun write(out: BufferedOutputStream, r: Resp, s: Socket) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ${r.code} ${statusText(r.code)}\r\n")
        sb.append("Content-Type: ${r.type}\r\n")
        sb.append("Content-Length: ${r.body.size}\r\n")
        // 页面与状态都不缓存：后端随时可能换端口/停止
        sb.append("Cache-Control: no-store\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.write(r.body)
        out.flush()
        runCatching { s.shutdownOutput() }
    }

    // ---------------------------------------------------------------- 路由实现

    /**
     * 首页：**模型包自带的 webui/index.html 优先**，没有才用 APK 内置那份。
     *
     * 为什么要有这一层：不同模型的默认分辨率/步数不一样（512² 包 vs 320² 包），
     * 而 WebUI 只有一份内置。把 UI 放进模型包，切模型就切 UI，不必改 APK。
     */
    private fun asset(): Resp {
        val dir = runCatching { backendDir() }.getOrNull()
        if (!dir.isNullOrBlank()) {
            val f = java.io.File(dir, "webui/index.html")
            if (f.isFile) {
                val key = f.absolutePath
                val body = htmlCache[key] ?: runCatching { f.readBytes() }.getOrNull()
                if (body != null) {
                    htmlCache[key] = body
                    return Resp(200, "text/html; charset=utf-8", body)
                }
            }
        }
        val body = htmlCache["builtin"] ?: runCatching {
            ctx.assets.open("webui/index.html").use { it.readBytes() }
        }.getOrNull() ?: return json(500, """{"error":"缺少 assets/webui/index.html"}""").also {
            Log.e(TAG, "内置 WebUI 缺失")
        }
        htmlCache["builtin"] = body
        return Resp(200, "text/html; charset=utf-8", body)
    }

    private fun health(): Resp {
        val bp = runCatching { backendPort() }.getOrNull()
        val ok = bp != null && bp > 0
        val body = buildJsonObject {
            put("status", if (ok) "ok" else "no-backend")
            put("gateway_port", port)
            put("backend_port", bp ?: 0)
            put("model", runCatching { backendLabel() }.getOrNull() ?: "")
            put("kind", runCatching { backendKind() }.getOrNull() ?: "")
            put("lan", lanUrl ?: "")
            putJsonArray("sizes") {
                runCatching { backendSizes() }.getOrNull()?.forEach { add(it) }
            }
            // 生成长度与上下文：WebUI 用它当默认值，别各自写死一个数字
            runCatching { backendParams() }.getOrNull()?.let { bp ->
                bp["n_predict"]?.toIntOrNull()?.let { put("n_predict", it) }
                bp["n_ctx"]?.toIntOrNull()?.let { put("n_ctx", it) }
                // 图像模型的默认生成参数，同样以实例设置为准。
                // ★ cfg 必须跟着走：Qwen-Image Turbo 是 8 步蒸馏模型，cfg 只能是 1.0；
                //   页面以前写死 6.0，会让 sd.cpp 启用 uncond 分支（batch=2），
                //   单链条的分段下发扛不住 → 建图时 GGML_ASSERT 直接 SIGABRT（11-12 定位）。
                bp["cfg"]?.toDoubleOrNull()?.let { put("cfg_scale", it) }
                bp["size"]?.toIntOrNull()?.let { put("size", it) }
                bp["steps"]?.toIntOrNull()?.let { put("steps", it) }
            }
        }.toString()
        return json(200, body)
    }

    private fun models(): Resp {
        val name = runCatching { backendLabel() }.getOrNull()?.takeIf { it.isNotBlank() } ?: "pocket-model"
        val body = buildJsonObject {
            put("object", "list")
            putJsonArray("data") {
                add(buildJsonObject {
                    put("id", name)
                    put("object", "model")
                    put("owned_by", "pocket-model-studio")
                })
            }
        }.toString()
        return json(200, body)
    }

    /** 同源反代：路径拼到 127.0.0.1:<后端端口> 上 */
    private fun forward(method: String, path: String, body: ByteArray?): Resp {
        val bp = runCatching { backendPort() }.getOrNull()
            ?: return json(503, """{"error":"没有运行中的服务，请先在 App 里启动模型"}""")
        return try {
            val conn = (URL("http://127.0.0.1:$bp$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection)
            conn.requestMethod = method
            conn.connectTimeout = 5000
            // 生成 256 token 在 8 Gen 2 上约 30 s，给足余量
            conn.readTimeout = 900_000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            conn.disconnect()
            Resp(code, conn.contentType ?: "application/json", bytes)
        } catch (e: Exception) {
            json(502, """{"error":"后端请求失败: ${e.message?.replace("\"", "'")}"}""")
        }
    }

    /**
     * OpenAI 兼容层。
     *
     * 后端只吃一个裸 prompt（自己套 gemma4 的单轮模板），所以多轮对话只能把历史
     * 压成一段文字塞进同一个 user turn。这不是理想做法，但胜过不支持 ——
     * 想真正多轮得改后端，那是重打 7.4 GB 包的代价。
     */
    /** 一次 chat 请求解析出来的东西：流式与非流式共用 */
    private data class ChatReq(val prompt: String, val maxTokens: Int, val model: String)

    private fun prepareChat(body: ByteArray?): ChatReq {
        val root = runCatching { Json.parseToJsonElement(body?.decodeToString() ?: "{}").jsonObject }
            .getOrElse { throw Rejected(json(400, """{"error":"请求体不是合法 JSON"}""")) }

        val msgs = root["messages"] as? JsonArray
            ?: throw Rejected(json(400, """{"error":"缺少 messages"}"""))
        val texts = msgs.mapNotNull { m ->
            val o = m as? JsonObject ?: return@mapNotNull null
            val role = o["role"]?.jsonPrimitive?.contentOrNull ?: "user"
            val content = o["content"]?.let { c ->
                // content 可能是字符串，也可能是 [{type:"text",text:"..."}]
                when (c) {
                    is JsonPrimitive -> c.contentOrNull
                    is JsonArray -> c.mapNotNull {
                        (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                    }.joinToString("\n")
                    else -> null
                }
            }
            if (content.isNullOrBlank()) null else role to content
        }
        if (texts.isEmpty()) throw Rejected(json(400, """{"error":"messages 里没有可用文本"}"""))

        val lastUser = texts.lastOrNull { it.first == "user" }?.second ?: texts.last().second
        val history = texts.dropLastWhile { it.second != lastUser }
        val prompt = if (history.size <= 1) {
            lastUser
        } else {
            buildString {
                appendLine("以下是此前的对话记录：")
                history.dropLast(1).forEach { (role, text) ->
                    appendLine(if (role == "assistant") "助手：$text" else "用户：$text")
                }
                appendLine()
                append("现在请直接回答这个问题（不要复述上面的记录）：$lastUser")
            }
        }

        val bp = backendParams()
        val nCtx = bp["n_ctx"]?.toIntOrNull() ?: 32768
        // 默认生成长度取实例的实际参数（不再写死 256 —— 那会让每条长答案都被截断，
        // 而截断的半句又被下一轮塞进历史，模型就顺着半句续写，看起来像「半截的思考段」）
        val defGen = bp["n_predict"]?.toIntOrNull() ?: 8192
        // prompt 侧可以分块跑 NPU（每块 1024，多块拼 KV，见 hybrid-runbook §6.5），
        // 所以生成预算只跟 n_ctx 走，不再按「预填只有 1024」砍。
        val budget = (nCtx - 1).coerceAtLeast(1)
        val maxTokens = (root["max_tokens"]?.jsonPrimitive?.intOrNull
            ?: root["max_completion_tokens"]?.jsonPrimitive?.intOrNull
            ?: defGen).coerceIn(1, budget)
        val model = runCatching { backendLabel() }.getOrNull() ?: "pocket-model"
        return ChatReq(prompt, maxTokens, model)
    }

    private fun chatCompletions(body: ByteArray?, accept: String?, query: String): Resp {
        val cr = try {
            prepareChat(body)
        } catch (e: Rejected) {
            return e.resp
        }
        val prompt = cr.prompt
        val maxTokens = cr.maxTokens
        val model = cr.model

        val req = buildJsonObject {
            put("prompt", prompt)
            put("n_predict", maxTokens)
        }.toString()

        val r = completionCall(req.toByteArray())
        if (r.code !in 200..299) return r

        val out = runCatching { Json.parseToJsonElement(r.body.decodeToString()).jsonObject }
            .getOrElse { return json(502, """{"error":"后端返回的不是 JSON"}""") }
        val content = out["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val t = out["timings"] as? JsonObject
        val nPred = t?.get("predicted_n")?.jsonPrimitive?.intOrNull ?: 0
        val nPrompt = t?.get("prompt_n")?.jsonPrimitive?.intOrNull ?: 0
        val tps = t?.get("predicted_per_second")?.jsonPrimitive?.contentOrNull ?: ""
        val ctxLimit = runCatching { backendParams() }.getOrNull()
            ?.get("n_ctx")?.toIntOrNull() ?: 32768

        val id = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000

        // 行协议后端在上面已经走真流式分支了，这里只剩 HTTP 后端（llama-server / sd.cpp），
        // 它们本身不吐 SSE，只能跑完再给一个 delta + [DONE]。
        val wantStream = (accept?.contains("text/event-stream") == true) || query.contains("stream=true")
        if (wantStream) {
            val chunk = buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", model)
                putJsonArray("choices") {
                    add(buildJsonObject {
                        put("index", 0)
                        putJsonObject("delta") {
                            put("role", "assistant")
                            put("content", content)
                        }
                        put("finish_reason", if (nPred >= maxTokens) "length" else "stop")
                    })
                }
            }.toString()
            val sse = "data: $chunk\n\ndata: [DONE]\n\n"
            return Resp(200, "text/event-stream; charset=utf-8", sse.toByteArray())
        }

        val respBody = buildJsonObject {
            put("id", id)
            put("object", "chat.completion")
            put("created", created)
            put("model", model)
            putJsonArray("choices") {
                add(buildJsonObject {
                    put("index", 0)
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content)
                    }
                    put("finish_reason", if (nPred >= maxTokens) "length" else "stop")
                })
            }
            putJsonObject("usage") {
                put("prompt_tokens", nPrompt)
                put("completion_tokens", nPred)
                put("total_tokens", nPrompt + nPred)
            }
            // 非标准字段：客户端不看也无害，自己看性能方便
            putJsonObject("timings") {
                put("prompt_n", nPrompt)
                put("predicted_n", nPred)
                put("predicted_per_second", tps.toDoubleOrNull() ?: 0.0)
                put("pre_ms", t?.get("pre_ms")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0)
                put("decode_ms", t?.get("decode_ms")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0)
                put("total_ms", t?.get("total_ms")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0)
                put("truncated", nPred >= maxTokens)
                // prompt 只有在顶到 n_ctx 时才会被切（hybrid 按 1024 分块跑图，不按块截断）
                put("prompt_truncated", nPrompt >= ctxLimit - 1)
            }
        }.toString()
        return json(200, respBody)
    }

    // ---------------------------------------------------------------- 行协议后端（llama-split）

    private fun splitMode(): Boolean = runCatching { backendProto() }.getOrNull() == "split"

    private fun completionCall(body: ByteArray?): Resp =
        if (splitMode()) completionViaSplit(body) else forward("POST", "/completion", body)

    /** 行协议请求的解析结果：流式与非流式共用，避免两套参数各解一遍、各错一遍 */
    private data class SplitReq(
        val port: Int,
        val prompt: String,
        val nCtx: Int,
        val nPredict: Int,
        val chatMode: Boolean,
        val noThink: Boolean,
        val temp: Float,
        val topK: Int,
        val topP: Float,
        val seed: Int,
    )

    /** 已经能给出完整 HTTP 响应体的错误（参数不对 / 无后端），不需要再抛到外层 */
    private class Rejected(val resp: Resp) : Exception()

    private fun prepareSplit(body: ByteArray?): SplitReq {
        val port = backendPort()
            ?: throw Rejected(json(503, """{"error":"没有运行中的服务，请先在 App 里启动模型"}"""))
        val root = runCatching {
            Json.parseToJsonElement(body?.decodeToString() ?: "{}").jsonObject
        }.getOrElse { throw Rejected(json(400, """{"error":"请求体不是合法 JSON"}""")) }

        val prompt = root["prompt"]?.jsonPrimitive?.contentOrNull ?: ""
        val bpParams = runCatching { backendParams() }.getOrNull() ?: emptyMap()
        val nCtx = bpParams["n_ctx"]?.toIntOrNull() ?: 8192
        val defGen = bpParams["n_predict"]?.toIntOrNull() ?: 1024
        val nPredict = (root["n_predict"]?.jsonPrimitive?.intOrNull
            ?: root["max_tokens"]?.jsonPrimitive?.intOrNull ?: defGen)
            .coerceIn(1, (nCtx - 1).coerceAtLeast(1))
        return SplitReq(
            port = port,
            prompt = prompt,
            nCtx = nCtx,
            nPredict = nPredict,
            chatMode = root["chat"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true,
            noThink = root["no_think"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: true,
            temp = root["temperature"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.7f,
            topK = root["top_k"]?.jsonPrimitive?.intOrNull ?: 40,
            topP = root["top_p"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0.95f,
            seed = root["seed"]?.jsonPrimitive?.intOrNull ?: -1,
        )
    }

    /**
     * 跑一次行协议生成。`onToken` 非空时逐片回调（流式），为空则一口气跑完。
     *
     * 正在跑的客户端会登记到 [activeGens]，`/cancel` 直接对它写 CANCEL —— 这条命令
     * 故意不参与 generate 的阻塞读，见 SplitClient.cancel()。
     */
    private fun runSplit(sr: SplitReq, onToken: ((String) -> Unit)?): SplitResult {
        SplitClient(sr.port).use { c ->
            c.connect()
            activeGens.add(c)
            try {
                // `enable_thinking=false` 时模板会吐一个空的 think 块，llama_chat_apply_template
                // 拿不到这个开关，只能按 serve_http.py 的做法补同样的字节。
                val rendered = if (sr.chatMode) {
                    c.chat(sr.prompt) + if (sr.noThink) "<think>\n\n</think>\n\n" else ""
                } else sr.prompt
                val ids = c.tokenize(rendered, addSpecial = !sr.chatMode)
                if (ids.size + sr.nPredict > sr.nCtx) {
                    throw Rejected(json(400, buildJsonObject {
                        put("error", "context overflow")
                        put("prompt_tokens", ids.size)
                        put("max_tokens", sr.nPredict)
                        put("context", sr.nCtx)
                    }.toString()))
                }
                return c.generate(ids, sr.nPredict, sr.temp, sr.topK, sr.topP, sr.seed, onToken)
            } finally {
                activeGens.remove(c)
            }
        }
    }

    /**
     * 把 llama.cpp 的 `/completion` 语义映射到 llama-split 的行协议上。
     *
     * 设备侧的 chat 模板跟着模型走（`CHAT` 命令由 llama-split 调 llama_chat_apply_template），
     * 所以这里不自己拼 `<|im_start|>`，只负责：渲染 → 分词 → GEN → 拼回 llama.cpp 的
     * JSON 形状，让 WebUI 与 `/v1/chat/completions` 都不用改。
     */
    private fun completionViaSplit(body: ByteArray?): Resp {
        return try {
            val sr = prepareSplit(body)
            val r = runSplit(sr, null)
            var text = r.text
            if (sr.chatMode && sr.noThink) {
                text = text.replace(Regex("""^\s*<think>\s*</think>\s*"""), "")
            }
            json(200, buildJsonObject {
                put("content", text)
                put("stop", true)
                // 行协议只报总数，没有 prefill/decode 分项；pre_ms 给 0，decode_ms 记墙钟，
                // 这样 WebUI 的 t/s 至少是真实吞吐（含 prefill），不会虚高。
                putJsonObject("timings") {
                    put("prompt_n", r.nPrompt)
                    put("predicted_n", r.nOut)
                    put("predicted_per_second",
                        if (r.wallMs > 0) r.nOut * 1000.0 / r.wallMs else 0.0)
                    put("pre_ms", 0)
                    put("decode_ms", r.wallMs)
                    put("total_ms", r.wallMs)
                    put("truncated", r.truncated)
                    put("cancelled", r.cancelled)
                }
            }.toString())
        } catch (e: Rejected) {
            e.resp
        } catch (e: Exception) {
            json(502, """{"error":"${(e.message ?: "后端请求失败").replace("\"", "'")}"}""")
        }
    }

    // ---------------------------------------------------------------- 流式（SSE）

    /**
     * `/api/completion` 的流式版：真逐 token 下发，而不是跑完再吐一个 delta。
     *
     * 为什么这件事重要：9B 三段链约 5 t/s，1024 token 就是 3 分钟。非流式时浏览器只能
     * 盯着转圈，长答案还会被误以为卡死。行协议本身就是逐 token 的，网关没理由把它们
     * 攒到最后再发。
     *
     * 事件形状（与 llama.cpp `/completion` 的 stream 对齐，另加 done 汇总）：
     *   data: {"token":"..."}
     *   data: {"done":true,"n_predict":..,"n_prompt":..,"truncated":..,"cancelled":..,"seconds":..,"tps":..}
     *   data: [DONE]
     */
    private fun streamCompletionSplit(body: ByteArray?, out: BufferedOutputStream, sock: Socket) {
        val sr = try {
            prepareSplit(body)
        } catch (e: Rejected) {
            write(out, e.resp, sock)
            return
        }
        startSse(out) ?: return
        fun event(json: String) {
            runCatching {
                out.write("data: $json\n\n".toByteArray())
                out.flush()
            }
        }

        val filter = if (sr.chatMode && sr.noThink) ThinkFilter() else null
        val t0 = System.currentTimeMillis()
        try {
            val r = runSplit(sr) { piece ->
                val shown = filter?.feed(piece) ?: piece
                if (shown.isNotEmpty()) {
                    event(buildJsonObject { put("token", shown) }.toString())
                }
            }
            filter?.flush()?.takeIf { it.isNotEmpty() }?.let {
                event(buildJsonObject { put("token", it) }.toString())
            }
            val ms = System.currentTimeMillis() - t0
            val secs = ms / 1000.0
            event(buildJsonObject {
                put("done", true)
                put("n_prompt", r.nPrompt)
                put("n_predict", r.nOut)          // 与 serve_http.py 的字段名一致
                put("predicted_n", r.nOut)
                put("truncated", r.truncated)
                put("cancelled", r.cancelled)
                put("seconds", String.format(java.util.Locale.US, "%.2f", secs))
                put("tps", if (ms > 0) String.format(
                    java.util.Locale.US, "%.2f", r.nOut * 1000.0 / ms) else "0.00")
            }.toString())
            event("[DONE]")
        } catch (e: Rejected) {
            event(buildJsonObject { put("error", e.resp.body.decodeToString()) }.toString())
        } catch (e: Exception) {
            val msg = (e.message ?: "后端请求失败").replace("\"", "'")
            event(buildJsonObject { put("error", msg) }.toString())
        }
        runCatching { out.flush() }
    }

    /** 请求方要不要流式：body 的 stream、Accept 头、?stream=true 任一命中即可 */
    private fun wantsStream(headers: Map<String, String>, body: ByteArray?, query: String): Boolean {
        if (query.contains("stream=true")) return true
        if (headers["accept"]?.contains("text/event-stream") == true) return true
        val root = runCatching {
            Json.parseToJsonElement(body?.decodeToString() ?: "{}").jsonObject
        }.getOrNull() ?: return false
        val p = root["stream"]?.jsonPrimitive ?: return false
        return runCatching { p.booleanOrNull ?: ((p.intOrNull ?: 0) != 0) }.getOrDefault(false)
    }

    /**
     * `/v1/chat/completions` 的流式版（仅行协议后端）。
     *
     * 之前这里是「跑完再塞一个 delta」—— 客户端拿到的确实是合法 SSE，但 3 分钟里一个字节
     * 都没有，OpenAI SDK 的超时早触发了。现在逐 token 发 `chat.completion.chunk`。
     */
    private fun streamChatCompletions(body: ByteArray?, out: BufferedOutputStream, sock: Socket) {
        val cr = try {
            prepareChat(body)
        } catch (e: Rejected) {
            write(out, e.resp, sock)
            return
        }
        val sr = try {
            prepareSplit(buildJsonObject {
                put("prompt", cr.prompt)
                put("n_predict", cr.maxTokens)
            }.toString().toByteArray())
        } catch (e: Rejected) {
            write(out, e.resp, sock)
            return
        }
        startSse(out) ?: return

        val id = "chatcmpl-${System.currentTimeMillis()}"
        val created = System.currentTimeMillis() / 1000
        fun event(s: String) {
            runCatching {
                out.write("data: $s\n\n".toByteArray())
                out.flush()
            }
        }
        fun chunk(delta: JsonObject, finish: String?) {
            event(buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", cr.model)
                putJsonArray("choices") {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", delta)
                        put("finish_reason", finish?.let { JsonPrimitive(it) } ?: JsonNull)
                    })
                }
            }.toString())
        }

        val filter = if (sr.chatMode && sr.noThink) ThinkFilter() else null
        val t0 = System.currentTimeMillis()
        try {
            chunk(buildJsonObject { put("role", "assistant"); put("content", "") }, null)
            val r = runSplit(sr) { piece ->
                val shown = filter?.feed(piece) ?: piece
                if (shown.isNotEmpty()) chunk(buildJsonObject { put("content", shown) }, null)
            }
            filter?.flush()?.takeIf { it.isNotEmpty() }?.let {
                chunk(buildJsonObject { put("content", it) }, null)
            }
            val ms = System.currentTimeMillis() - t0
            // 收尾 chunk 带 usage / timings（非标准，OpenAI 客户端会忽略）
            event(buildJsonObject {
                put("id", id)
                put("object", "chat.completion.chunk")
                put("created", created)
                put("model", cr.model)
                putJsonArray("choices") {
                    add(buildJsonObject {
                        put("index", 0)
                        put("delta", buildJsonObject { })
                        put("finish_reason", if (r.truncated) "length" else "stop")
                    })
                }
                putJsonObject("usage") {
                    put("prompt_tokens", r.nPrompt)
                    put("completion_tokens", r.nOut)
                    put("total_tokens", r.nPrompt + r.nOut)
                }
                putJsonObject("timings") {
                    put("prompt_n", r.nPrompt)
                    put("predicted_n", r.nOut)
                    put("predicted_per_second", if (ms > 0) r.nOut * 1000.0 / ms else 0.0)
                    put("total_ms", ms)
                    put("truncated", r.truncated)
                    put("cancelled", r.cancelled)
                }
            }.toString())
            event("[DONE]")
        } catch (e: Rejected) {
            event(buildJsonObject { put("error", e.resp.body.decodeToString()) }.toString())
        } catch (e: Exception) {
            event(buildJsonObject {
                put("error", (e.message ?: "后端请求失败").replace("\"", "'"))
            }.toString())
        }
        runCatching { out.flush() }
    }

    /** 写出 SSE 响应头。返回 null 表示写失败，调用方直接放弃 */
    private fun startSse(out: BufferedOutputStream): Unit? = runCatching {
        out.write((
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "X-Accel-Buffering: no\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"
            ).toByteArray())
        out.flush()
    }.getOrNull()

    /** 停掉所有正在生成的请求。返回命中的条数 */
    private fun cancelAll(): Int {
        var n = 0
        for (c in activeGens) {
            if (c.cancel()) n++
        }
        return n
    }

    /**
     * 逐片剥掉开头的空 think 块。
     *
     * 整段生成完再拿正则剥是行不通的：流式下第一个字就得决定要不要显示。做法是先攒
     * 最多 64 字符 —— 只要首字符不是 `<` 就立刻放行（绝大多数情况），是 `<` 就等到
     * `</think>` 出现再吐后面的内容。
     */
    private class ThinkFilter {
        private val buf = StringBuilder()
        private var decided = false

        fun feed(s: String): String {
            if (decided) return s
            buf.append(s)
            val t = buf.toString().trimStart()
            if (t.isEmpty()) return ""
            if (!t.startsWith("<")) {
                decided = true
                return buf.toString()
            }
            val end = t.indexOf("</think>")
            if (end >= 0) {
                decided = true
                return t.substring(end + "</think>".length).trimStart()
            }
            // 可能是被切开的 "<think"，继续等；攒够 64 字符还没等到就放弃剥离
            if (buf.length < 64) return ""
            decided = true
            return buf.toString()
        }

        fun flush(): String {
            if (decided) return ""
            decided = true
            return buf.toString()
        }
    }

    /** `/api/props` 的等价物：行协议用 INFO 一次性给出模型 / 段数 / hidden / vocab */
    private fun propsViaSplit(): Resp {
        val bp = backendPort() ?: return json(503, """{"error":"没有运行中的服务"}""")
        return try {
            SplitClient(bp).use { c ->
                c.connect()
                val i = c.info()
                json(200, buildJsonObject {
                    put("model", i.model)
                    put("n_ctx", i.nCtx)
                    put("n_stage", i.nStage)
                    put("n_embd", i.nEmbd)
                    put("n_vocab", i.nVocab)
                    put("split", i.split)
                }.toString())
            }
        } catch (e: Exception) {
            json(502, """{"error":"${(e.message ?: "读取模型信息失败").replace("\"", "'")}"}""")
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun json(code: Int, body: String) = Resp(code, "application/json; charset=utf-8", body.toByteArray())

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 404 -> "Not Found"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
        else -> "OK"
    }

    /** 局域网地址。缓存 5 s：手机切网/开热点时 IP 会变，但没必要每个请求都枚举网卡 */
    fun lanIp(): String? {
        val now = System.currentTimeMillis()
        if (now - lanIpAt < 5000) return lanIpCache
        lanIpAt = now
        lanIpCache = runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val candidates = ifaces.flatMap { ni ->
                if (!ni.isUp || ni.isLoopback) emptyList()
                else ni.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .map { ni.name to it.hostAddress }
            }
            // 优先无线/热点接口，其次任意私有地址
            candidates.minByOrNull { (name, _) ->
                when {
                    name.startsWith("wlan") -> 0
                    name.startsWith("ap") || name.startsWith("swlan") -> 1
                    name.startsWith("eth") -> 2
                    else -> 3
                }
            }?.second
        }.getOrNull()
        return lanIpCache
    }

    private data class Resp(val code: Int, val type: String, val body: ByteArray)

    companion object {
        private const val TAG = "Gateway"

        /** 7860 与 Gradio 默认端口冲突，但手机上没有 Gradio；占用了会自动顺延 */
        const val DEFAULT_PORT = 7860
    }
}
