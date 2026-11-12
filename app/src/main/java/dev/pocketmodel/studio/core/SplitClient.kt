package dev.pocketmodel.studio.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * `llama-split --serve` 的行协议客户端。
 *
 * 为什么需要它：llama-split 的对外接口**不是 HTTP**，而是一行一条命令的文本协议
 * （TOK / CHAT / GEN / INFO / PING，见 llama-split.cpp 头部注释）。App 的其它后端
 * （llama-server / sd.cpp）都是 HTTP，网关直接反代就行；这条链必须自己说话。
 *
 * 协议要点（对齐 serve_http.py 的实现）：
 * - 文本一律 hex 编码，避免换行把协议行截断；
 * - `GEN <rid> <max> <temp> <top_k> <top_p> <seed> <nprompt> <ids...>` 之后逐 token
 *   回 `P <rid> <tok> <hex>`，最后一行 `E <rid> <status> <nout>`；
 * - `status` 里 `ok` / `length` / `cancelled` 都是**正常结束**，只有 `error` / `skew`
 *   / `ctx_overflow` 等才是失败（L-064 的教训：跑满预算不是错误）。
 *
 * 连接不共享：设备侧 `--slots N` 就是靠多条连接并行工作的，共用一个 socket 会把
 * 并发又串回去。所以每次请求 new 一个，用完 close。
 */
class SplitError(message: String) : Exception(message)

data class SplitInfo(
    val model: String,
    val nCtx: Int,
    val nStage: Int,
    val nEmbd: Int,
    val nVocab: Int,
    val split: String,
)

data class SplitResult(
    val text: String,
    val nPrompt: Int,
    val nOut: Int,
    val wallMs: Long,
    val truncated: Boolean,
    val cancelled: Boolean,
)

class SplitClient(
    private val port: Int,
    private val host: String = "127.0.0.1",
) : Closeable {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: BufferedOutputStream? = null

    @Volatile private var closed = false
    private var rid = 0

    fun connect(connectTimeoutMs: Int = 4000, readTimeoutMs: Int = 30_000): SplitClient {
        close()
        val s = Socket()
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), connectTimeoutMs)
        s.soTimeout = readTimeoutMs
        socket = s
        input = BufferedInputStream(s.getInputStream())
        output = BufferedOutputStream(s.getOutputStream())
        closed = false
        return this
    }

    fun readTimeout(ms: Int) {
        socket?.soTimeout = ms
    }

    override fun close() {
        closed = true
        runCatching { output?.flush() }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    // ---------------------------------------------------------------- 基础收发

    private fun send(line: String) {
        val o = output ?: throw SplitError("未连接设备")
        try {
            o.write(line.toByteArray(Charsets.UTF_8))
            o.write('\n'.code)
            o.flush()
        } catch (e: Exception) {
            close()
            throw SplitError("设备连接已断开：${e.message}")
        }
    }

    private fun readLine(what: String = "回复"): String {
        val i = input ?: throw SplitError("未连接设备")
        val sb = StringBuilder()
        try {
            while (true) {
                val b = i.read()
                if (b < 0) {
                    close()
                    throw SplitError("设备关闭了连接（等待$what）")
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) sb.append(b.toChar())
                if (sb.length > 4 shl 20) throw SplitError("$what 过长")
            }
        } catch (e: java.net.SocketTimeoutException) {
            close()
            throw SplitError("设备 ${socket?.soTimeout?.div(1000) ?: 0} 秒无响应（等待$what）")
        } catch (e: SplitError) {
            throw e
        } catch (e: Exception) {
            close()
            throw SplitError("读取$what 失败：${e.message}")
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 命令

    fun ping(): Boolean = try {
        send("PING")
        readLine("PONG") == "PONG"
    } catch (_: SplitError) {
        false
    }

    fun info(): SplitInfo {
        send("INFO")
        val p = readLine("模型信息").split(" ")
        if (p.isEmpty() || p[0] != "INFO") throw SplitError("INFO 回复异常：${p.take(3)}")
        fun at(i: Int, def: Int = 0) = p.getOrNull(i)?.toIntOrNull() ?: def
        return SplitInfo(
            model = unhex(p.getOrNull(1) ?: ""),
            nCtx = at(2),
            nStage = at(3),
            nEmbd = at(4),
            nVocab = at(5),
            split = p.getOrNull(6) ?: "?",
        )
    }

    /** 用设备侧那份 chat 模板把对话渲染成一段 token 串（模板跟着模型走，App 不猜） */
    fun chat(user: String, system: String? = null): String {
        send("CHAT ${if (system.isNullOrBlank()) "-" else hex(system)} ${hex(user)}")
        val line = readLine("chat 模板")
        val sp = line.indexOf(' ')
        if (!line.startsWith("CHATT")) throw SplitError("CHAT 回复异常：${line.take(40)}")
        return unhex(if (sp >= 0) line.substring(sp + 1) else "")
    }

    fun tokenize(text: String, addSpecial: Boolean = false): List<Int> {
        send((if (addSpecial) "TOK " else "TOKN ") + hex(text))
        val p = readLine("分词结果").split(" ")
        if (p.isEmpty() || p[0] != "TOKS") throw SplitError("TOKS 回复异常：${p.take(3)}")
        val n = p.getOrNull(1)?.toIntOrNull() ?: 0
        return (2 until 2 + n).mapNotNull { p.getOrNull(it)?.toIntOrNull() }
    }

    fun detokenize(ids: List<Int>): String {
        send("DETOK ${ids.size} ${ids.joinToString(" ")}")
        val line = readLine("解码结果")
        val sp = line.indexOf(' ')
        if (!line.startsWith("TEXT")) throw SplitError("DETOK 回复异常：${line.take(40)}")
        return unhex(if (sp >= 0) line.substring(sp + 1) else "")
    }

    fun cancel(): Boolean {
        // 不参与锁：generate() 正在阻塞读，取消就是要插进去
        return runCatching {
            val o = output ?: return false
            o.write("CANCEL\n".toByteArray())
            o.flush()
            true
        }.getOrDefault(false)
    }

    /**
     * 跑一次生成。返回完整文本与计时；`onToken` 逐片回调（可以为 null，纯非流式）。
     * 设备的 `ok` / `length` / `cancelled` 都按正常结束处理。
     */
    fun generate(
        ids: List<Int>,
        maxTokens: Int,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f,
        seed: Int = -1,
        onToken: ((String) -> Unit)? = null,
    ): SplitResult {
        if (ids.isEmpty()) throw SplitError("prompt 为空")
        rid += 1
        val myRid = rid
        val useSeed = if (seed < 0) (System.currentTimeMillis() and 0x7FFFFFFF).toInt() else seed
        readTimeout(900_000)   // 8192 token 的长答案在 5 t/s 下要 20+ 分钟，逐 token 复位
        send("GEN $myRid $maxTokens ${fmt(temperature)} $topK ${fmt(topP)} $useSeed " +
                "${ids.size} ${ids.joinToString(" ")}")

        val t0 = System.currentTimeMillis()
        val sb = StringBuilder()
        var truncated = false
        var cancelled = false
        var nOut = 0
        // 一个 token 的字节可能是半个汉字（Qwen 的 byte-level BPE 会切在 UTF-8 中间），
        // 逐片 decode 会吐 U+FFFD。所以按字节攒，只吐到最后一个完整字符为止。
        val pending = ByteArrayOutputStream()
        fun emit(bytes: ByteArray): String {
            pending.write(bytes)
            val all = pending.toByteArray()
            pending.reset()
            val cut = utf8Complete(all)
            if (cut < all.size) pending.write(all, cut, all.size - cut)
            if (cut == 0) return ""
            val s = String(all, 0, cut, Charsets.UTF_8)
            sb.append(s)
            onToken?.invoke(s)
            return s
        }
        while (true) {
            val line = readLine("生成结果")
            val p = line.split(" ", limit = 4)
            when (p[0]) {
                "P" -> {
                    if (p.size < 3) continue
                    val tok = p[2].toIntOrNull() ?: -1
                    if (tok >= 0) {
                        nOut++
                        val raw = if (p.size > 3) unhexBytes(p[3]) else ByteArray(0)
                        emit(raw)
                    }
                }
                "E" -> {
                    when (val status = p.getOrNull(2) ?: "error") {
                        "ok" -> Unit
                        "length" -> truncated = true
                        "cancelled" -> cancelled = true
                        else -> throw SplitError("生成失败：$status")
                    }
                    // 收尾：剩下的半截字节（正常不会出现）一次性吐出去，避免丢字
                    val rest = pending.toByteArray()
                    if (rest.isNotEmpty()) {
                        pending.reset()
                        val s = String(rest, Charsets.UTF_8)
                        sb.append(s)
                        onToken?.invoke(s)
                    }
                    return SplitResult(
                        text = sb.toString(),
                        nPrompt = ids.size,
                        nOut = nOut,
                        wallMs = System.currentTimeMillis() - t0,
                        truncated = truncated,
                        cancelled = cancelled,
                    )
                }
                "OK", "CANCEL", "" -> continue   // 与请求结尾撞车的取消回执
                else -> throw SplitError("意外的回复：${line.take(80)}")
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun hex(s: String): String {
        val b = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("0123456789abcdef"[(x.toInt() shr 4) and 0xF])
            .append("0123456789abcdef"[x.toInt() and 0xF])
        return sb.toString()
    }

    private fun unhex(s: String): String = String(unhexBytes(s), Charsets.UTF_8)

    private fun unhexBytes(s: String): ByteArray {
        if (s.isEmpty()) return ByteArray(0)
        val n = s.length / 2
        val b = ByteArray(n)
        for (i in 0 until n) {
            b[i] = ((digit(s[i * 2]) shl 4) or digit(s[i * 2 + 1])).toByte()
        }
        return b
    }

    /**
     * 返回 `all` 里「到最后一个完整 UTF-8 字符为止」的字节数，剩下的留到下一片。
     * 判断方法：从尾部往前跳过最多 3 个延续字节，找到起始字节，按它声明的长度判断是否被截断。
     */
    private fun utf8Complete(all: ByteArray): Int {
        var i = all.size - 1
        var cont = 0
        while (i >= 0 && cont < 3 && (all[i].toInt() and 0xC0) == 0x80) {
            i--
            cont++
        }
        if (i < 0) return 0                 // 全是延续字节：一个完整字符都还没凑齐
        val b = all[i].toInt() and 0xFF
        val need = when {
            b < 0x80 -> 1
            b and 0xE0 == 0xC0 -> 2
            b and 0xF0 == 0xE0 -> 3
            b and 0xF8 == 0xF0 -> 4
            else -> 1                        // 非法起始字节，别卡住流
        }
        return if (i + need > all.size) i else all.size
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> 0
    }

    private fun fmt(f: Float): String = String.format(java.util.Locale.US, "%.4f", f)
}
