package dev.pocketmodel.studio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocketmodel.studio.core.ParamSpec
import dev.pocketmodel.studio.core.ServiceInstance
import dev.pocketmodel.studio.core.ServiceState
import dev.pocketmodel.studio.core.StudioCore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioApp() {
    var tab by remember { mutableStateOf(0) }
    val titles = listOf("服务", "模型", "设置")
    // 非 null 时整屏显示 WebUI（不走 tab，避免底栏挤占聊天空间）
    var webui by remember { mutableStateOf<Pair<String, String>?>(null) }
    val appCtx = LocalContext.current

    val openWebUi: (String) -> Unit = { title ->
        val g = StudioCore.startGateway()
        val u = g?.takeIf { it.started }?.localUrl
        if (u == null) {
            Toast.makeText(appCtx, "网关未能启动：${g?.lastError ?: "未知原因"}", Toast.LENGTH_LONG).show()
        } else {
            webui = u to title
        }
    }

    webui?.let { (url, title) ->
        val lan = StudioCore.gateway?.lanUrl
        WebUiScreen(url = url, title = title, lanUrl = lan) { webui = null }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pocket Model Studio") },
                actions = {
                    IconButton(onClick = { openWebUi("WebUI") }) {
                        Icon(Icons.Default.Language, "打开 WebUI")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PlayArrow, null) },
                    label = { Text(titles[0]) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.ViewInAr, null) },
                    label = { Text(titles[1]) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text(titles[2]) },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ServicesScreen(onOpenWebUi = openWebUi)
                1 -> ModelsScreen()
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun ServicesScreen(onOpenWebUi: (String) -> Unit) {
    val instances by StudioCore.instances.collectAsState()
    val ctx = LocalContext.current
    val list = instances.values.toList()
    var logFor by remember { mutableStateOf<ServiceInstance?>(null) }
    var paramsFor by remember { mutableStateOf<ServiceInstance?>(null) }
    val gw = StudioCore.gateway
    val lanUrl = gw?.takeIf { it.started }?.lanUrl

    if (list.isEmpty()) {
        EmptyHint("还没有部署任何服务。\n去「模型」页导入模型包，然后点「部署」。")
    } else {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(list, key = { it.id }) { inst ->
                val state by inst.state.collectAsState()
                val port by inst.port.collectAsState()
                val err by inst.failure.collectAsState()
                ServiceCard(
                    inst = inst,
                    state = state,
                    port = port,
                    error = err,
                    lanUrl = lanUrl,
                    onToggle = { on ->
                        if (on) inst.start(ctx, StudioCore.allocatePort())
                        else inst.stop()
                    },
                    onLog = { logFor = inst },
                    onParams = { paramsFor = inst },
                    onWebUi = { onOpenWebUi(inst.manifest.name) },
                )
            }
        }
    }

    logFor?.let { inst ->
        val lines by inst.log.collectAsState()
        LogDialog(title = inst.manifest.name, lines = lines) { logFor = null }
    }
    paramsFor?.let { inst ->
        ParamDialog(inst) { paramsFor = null }
    }
}

@Composable
private fun ServiceCard(
    inst: ServiceInstance,
    state: ServiceState,
    port: Int,
    error: String?,
    lanUrl: String?,
    onToggle: (Boolean) -> Unit,
    onLog: () -> Unit,
    onParams: () -> Unit,
    onWebUi: () -> Unit,
) {
    val running = state == ServiceState.RUNNING || state == ServiceState.STARTING
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(inst.manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        "${inst.manifest.kind.uppercase()} · ${inst.resolvedLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = running, onCheckedChange = onToggle)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(state)
                if (state == ServiceState.RUNNING && port > 0) {
                    Spacer(Modifier.width(8.dp))
                    AssistChip(onClick = {}, label = { Text(":${port}", fontFamily = FontFamily.Monospace) })
                }
                Spacer(Modifier.weight(1f))
                // 运行中禁用参数编辑：参数只在启动时读取，改了也要重启才生效；
                // 而弹对话框要拉起输入法（约 90 MB）+ 新建 Compose 视图树，在
                // MemAvailable 只剩几百 MB 时正是压垮系统的那一次分配（11-12 实测）。
                IconButton(
                    onClick = {
                        if (running) {
                            Toast.makeText(
                                ctx,
                                "服务运行中不能改参数（会拉起输入法、占内存）。先停止服务再改。",
                                Toast.LENGTH_LONG,
                            ).show()
                        } else {
                            onParams()
                        }
                    },
                ) {
                    Icon(
                        Icons.Default.Tune,
                        "参数",
                        tint = if (running) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onLog) { Icon(Icons.Default.Article, "日志") }
            }
            // ---- 快捷设置：enum 参数（分辨率这类）直接摆在卡片上，点一下即改 ----
            // 不再为了选一个分辨率去弹参数对话框：对话框要新建视图树，点进输入框还会拉起输入法
            // （约 90 MB）。11-12 的卡死就发生在「正在读 4.8 GB 权重、可用内存只剩几百 MB」时
            // 点开分辨率的那一刻 —— 系统随即开始 o-kill，整条服务链被清掉。
            val enums = inst.manifest.params.filter { it.type == "enum" && it.options.isNotEmpty() }
            if (enums.isNotEmpty()) {
                var quick by remember(inst) { mutableStateOf(inst.params) }
                LaunchedEffect(inst.params) { quick = inst.params }
                var customFor by remember(inst) { mutableStateOf<ParamSpec?>(null) }
                enums.forEach { p ->
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            p.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val current = quick[p.key] ?: p.defaultString()
                            p.options.forEach { o ->
                                FilterChip(
                                    selected = current == o,
                                    onClick = {
                                        quick = quick + (p.key to o)
                                        inst.params = quick
                                        if (running) {
                                            Toast.makeText(
                                                ctx,
                                                "${p.label} ${o}²，重启服务后生效",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    },
                                    // 尺寸档位写成 320² / 512²，比 320x320 省一半宽度，一行放得下
                                    label = {
                                        Text(
                                            if (o.all(Char::isDigit)) "$o²" else o,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    },
                                )
                            }
                            // 自由输入：预设之外的值。点了才弹输入框 —— 和「一进对话框就拉输入法」
                            // 不是一回事，这里用户明确知道要打字。
                            val isCustom = current.isNotEmpty() && current !in p.options
                            FilterChip(
                                selected = isCustom,
                                onClick = { customFor = p },
                                label = {
                                    Text(
                                        if (isCustom) "$current²" else "自定义",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                },
                            )
                        }
                    }
                }
                customFor?.let { p ->
                    SizeInputDialog(
                        label = p.label,
                        initial = (quick[p.key] ?: p.defaultString()).filter(Char::isDigit),
                        onDismiss = { customFor = null },
                    ) { v ->
                        quick = quick + (p.key to v)
                        inst.params = quick
                        customFor = null
                        if (running) {
                            Toast.makeText(ctx, "${p.label} ${v}²，重启服务后生效", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            if (state == ServiceState.RUNNING) {
                Spacer(Modifier.height(4.dp))
                // WebUI 是「本机页面」，局域网地址是给别的设备用的，两者都放在卡片上，
                // 用户不用去设置里翻端口
                FilledTonalButton(onClick = onWebUi, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Language, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开 WebUI")
                }
                if (lanUrl != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "局域网 ${lanUrl}（长按复制）",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("url", lanUrl))
                            Toast.makeText(ctx, "已复制局域网地址", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun StatusChip(state: ServiceState) {
    val (label, color) = when (state) {
        ServiceState.RUNNING -> "运行中" to MaterialTheme.colorScheme.primary
        ServiceState.STARTING -> "启动中" to MaterialTheme.colorScheme.tertiary
        ServiceState.STOPPING -> "停止中" to MaterialTheme.colorScheme.secondary
        ServiceState.FAILED -> "失败" to MaterialTheme.colorScheme.error
        ServiceState.STOPPED -> "已停止" to MaterialTheme.colorScheme.outline
    }
    Text(label, color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun LogDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
    val state = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleSmall) },
        text = {
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(420.dp)) {
                items(lines) { l ->
                    Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/**
 * 自定义正方边长（预设之外的值）。
 *
 * 只在用户主动点「自定义」时才弹 —— 和「一打开参数对话框就顺手拉起输入法」是两回事，
 * 这里用户明确知道要打字，不会踩到内存临界时那次额外分配。
 *
 * 约束取 16 的倍数：VAE 下采样 8×、DiT patch 2×，边长不是 16 的倍数会让 latent 切不整，
 * 后端直接报错。上限 1024 是实用上限（512² 已经要吃掉近百秒 VAE 解码）。
 */
@Composable
private fun SizeInputDialog(
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial.ifEmpty { "320" }) }
    val value = text.toIntOrNull()
    val valid = value != null && value in 64..1024 && value % 16 == 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label · 自定义") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    label = { Text("边长") },
                    suffix = { Text("px") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = text.isNotEmpty() && !valid,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "必须是 16 的倍数（64~1024）。改完要重启服务才生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = valid) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
