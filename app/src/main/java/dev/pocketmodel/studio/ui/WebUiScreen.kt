package dev.pocketmodel.studio.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 内的 WebUI 浏览器。
 *
 * 用 WebView 而不是「跳外部浏览器」：这样它就是 App 的一个页面，返回键回 App、
 * 不需要用户记住端口，也不会因为切到浏览器导致 App 被系统挂起。
 *
 * 局域网地址在这里也能复制/分享 —— 用户想在电脑上看得先拿到这个 URL。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebUiScreen(
    url: String,
    title: String,
    lanUrl: String?,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }

    BackHandler { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            lanUrl ?: url,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "刷新") }
                    if (lanUrl != null) {
                        IconButton(onClick = {
                            copy(ctx, lanUrl)
                            Toast.makeText(ctx, "已复制局域网地址", Toast.LENGTH_SHORT).show()
                        }) { Icon(Icons.Default.Share, "复制局域网地址") }
                    }
                    IconButton(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lanUrl ?: url)))
                        }
                    }) { Icon(Icons.Default.OpenInBrowser, "用浏览器打开") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { pad ->
        // imePadding 必须加：manifest 是 adjustResize，但 App 走 edge-to-edge，
        // 没有这一步 WebView 仍是整屏高度、被键盘整个盖住，页面里的输入框点不到。
        Box(Modifier.padding(pad).fillMaxSize().imePadding()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    WebView(c).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        // 长按图片能「另存为」：WebView 自己不提供系统的那套图片菜单
                        // （长按只有文本选择），所以生成图的保存一律走页面上的按钮 +
                        // 这个长按兜底，两条路都落到同一个 JS 函数 __pmsSaveImage()。
                        addJavascriptInterface(ImageSaver(c), "pms")
                        setOnLongClickListener { v ->
                            val wv = v as WebView
                            val hit = wv.hitTestResult
                            val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                                hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                            if (isImage) {
                                // 页面里没有图时函数自己会忽略，不用在这里判
                                wv.evaluateJavascript(
                                    "window.__pmsSaveImage && window.__pmsSaveImage()", null
                                )
                            }
                            isImage
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                            }
                        }
                        loadUrl(url)
                        tag = url
                        webView = this
                    }
                },
                // update 会在每次重组时被调用（loading 状态一变就重组），
                // 无条件 loadUrl 会把页面刷掉；只在 url 真变了才重新载入
                update = { wv ->
                    if (wv.tag != url) {
                        wv.tag = url
                        wv.loadUrl(url)
                    }
                },
            )
            if (loading) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("url", text))
}

/**
 * 把 WebUI 里的 base64 图片落到相册。
 *
 * 为什么不用 `<a download>`：WebView 默认没有 DownloadListener，点了毫无反应；
 * 就算接了，`download` 属性在 data: URL 上也不可靠。走 MediaStore 才是 App 里
 * 「另存为」的正解 —— 相册里直接可见，不用管权限（API 29+ 写自己的 Pictures
 * 子目录不需要任何运行时权限）。
 *
 * 返回值用字符串而不是抛异常：@JavascriptInterface 抛出的异常在 JS 侧只会变成
 * 一句 "Java exception was thrown"，页面拿不到原因，只能干瞪眼。
 *
 * ★ 类不能是 private / 匿名内部类：WebView 靠反射找 @JavascriptInterface 方法，
 *   目标类本身不可见时会抛 IllegalAccessException，表现为 JS 里 `pms` 是 undefined。
 */
class ImageSaver(private val ctx: Context) {
    @JavascriptInterface
    fun saveImage(dataUrl: String): String {        return try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return "err:图片数据格式不对"
            if (!dataUrl.substring(0, comma).contains("base64")) return "err:只支持 base64 图片"
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            if (bytes.isEmpty()) return "err:图片是空的"
            val name = "pms-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".png"
            val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(bytes, name)
            } else {
                saveLegacy(bytes, name)
            }
            "ok:$where"
        } catch (e: Throwable) {
            "err:" + (e.message ?: e.javaClass.simpleName)
        }
    }

    /** API 29+：插入 MediaStore 走「图片」集合，相册与文件管理器都能直接看到 */
    private fun saveToMediaStore(bytes: ByteArray, name: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/PocketModelStudio",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("相册拒绝写入")
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
            ?: throw IllegalStateException("打不开输出流")
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return "Pictures/PocketModelStudio/$name"
    }

    /**
     * API 26~28：没有 RELATIVE_PATH，写 App 自己的外部目录再通知相册扫描。
     * 不做 WRITE_EXTERNAL_STORAGE 运行时申请 —— 那条路在国产 ROM 上弹窗多、
     * 一旦被拒就是静默失败，体验还不如放 App 目录里（相册照样能看到）。
     */
    private fun saveLegacy(bytes: ByteArray, name: String): String {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: throw IllegalStateException("外部存储不可用")
        val f = File(dir, name)
        f.outputStream().use { it.write(bytes) }
        MediaScannerConnection.scanFile(ctx, arrayOf(f.absolutePath), arrayOf("image/png"), null)
        return f.absolutePath
    }
}
