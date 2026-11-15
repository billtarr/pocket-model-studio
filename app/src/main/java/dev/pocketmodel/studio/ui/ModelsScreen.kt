package dev.pocketmodel.studio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.pocketmodel.studio.core.ImportCancelledException
import dev.pocketmodel.studio.core.ImportPhase
import dev.pocketmodel.studio.core.ImportProgress
import dev.pocketmodel.studio.core.InstalledModel
import dev.pocketmodel.studio.core.ParamSpec
import dev.pocketmodel.studio.core.ServiceInstance
import dev.pocketmodel.studio.core.ServiceState
import dev.pocketmodel.studio.core.StudioCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(onDeployed: () -> Unit = {}) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<InstalledModel>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<InstalledModel?>(null) }
    var used by remember { mutableStateOf(0L) }
    // null = 空闲；非 null = 正在导入。这个值驱动横幅，选完文件立刻置位，
    // 因为拿不到 SIZE 的那几百毫秒里界面也得有反应。
    var progress by remember { mutableStateOf<ImportProgress?>(null) }
    val cancelFlag = remember { AtomicBoolean(false) }
    // 进度回调来自 IO 线程：投递到主线程再写状态（后台线程写 Compose state 要等下一次
    // 全局快照 apply，实测会漏帧），同时用 importing 挡住导入结束后才到的迟到帧。
    val importing = remember { AtomicBoolean(false) }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    suspend fun refresh() {
        loading = models.isEmpty()
        val (list, size) = withContext(Dispatchers.IO) {
            (runCatching { StudioCore.repo?.list() }.getOrNull() ?: emptyList()) to
                (runCatching { StudioCore.repo?.storageUsed() }.getOrNull() ?: 0L)
        }
        models = list
        used = size
        loading = false
    }

    // 回到前台就重扫一遍。导入 5 GB 的包要一两分钟，期间系统可能把 App 冻住/重建，
    // 那样 importZip 内的 refresh() 就失效了，卡片会一直停留在「解压到一半」的快照上
    // （表现为「没有 runtime/」「权重大小不符」，但文件其实是完整的）。
    // 导入进行中不重扫——半成品目录扫出来只会误导。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, progress) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && progress == null) scope.launch { refresh() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (progress != null) return@rememberLauncherForActivityResult
        cancelFlag.set(false)
        importing.set(true)
        progress = ImportProgress(ImportPhase.PREPARING, detail = "准备中")
        scope.launch {
            try {
                val m = withContext(Dispatchers.IO) {
                    StudioCore.repo!!.importZip(
                        uri,
                        onProgress = { p -> mainHandler.post { if (importing.get()) progress = p } },
                        isCancelled = { cancelFlag.get() },
                    )
                }
                message = "已导入 ${m.manifest.name}（${fmtBytes(m.bytes)}）"
            } catch (e: ImportCancelledException) {
                message = "已取消导入，未完成的数据已清理"
            } catch (e: Exception) {
                message = "导入失败：${e.message}"
            } finally {
                importing.set(false)
                progress = null
                refresh()
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (progress == null) picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text(if (progress != null) "导入中" else "导入") },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxWidth()) {
            progress?.let { p ->
                ImportBanner(p) { cancelFlag.set(true) }
            }
            StorageBar(used)
            if (loading) {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            } else if (models.isEmpty()) {
                EmptyHint("还没有模型。\n点右下角「导入」，选择 .pm / .zip 模型包。\n\n大包（5 GB 以上）导入要一到三分钟，进度显示在页面顶部。")
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(models, key = { it.manifest.id }) { m ->
                        ModelCard(
                            m = m,
                            onDeploy = {
                                // 只创建实例，启动开关在「服务」页；建完直接把用户带过去，
                                // 否则点完没有任何可见变化（Toast 一闪），像是按钮没反应。
                                StudioCore.instanceFor(m.manifest, m.dir)
                                android.widget.Toast.makeText(
                                    ctx, "已加入「服务」页，打开开关启动",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onDeployed()
                            },
                            onDelete = { pendingDelete = m },
                        )
                    }
                }
            }
        }
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
            title = { Text("提示") },
            text = { Text(it) },
        )
    }

    pendingDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型？") },
            text = { Text("将删除 ${m.manifest.name}（${fmtBytes(m.bytes)}）及其全部权重，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        StudioCore.removeInstance(m.manifest.id)
                        StudioCore.repo?.delete(m)
                        pendingDelete = null
                        refresh()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun StorageBar(used: Long) {
    Text(
        "模型库占用 ${fmtBytes(used)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
}

/**
 * 导入横幅：告诉用户「正在做什么、到哪了、还剩多久」。
 * 7 GB 的包要写两份（cache 解压 + 搬进 filesDir），没有这个横幅，界面上唯一的变化
 * 就是存储占用在长，看着像卡死。
 */
@Composable
private fun ImportBanner(p: ImportProgress, onCancel: () -> Unit) {
    // 速度用滑动采样算，别用累计/总耗时——那样中途掉速也会被历史平均掩盖
    var speed by remember { mutableStateOf(0.0) }   // bytes/s
    var lastBytes by remember { mutableStateOf(0L) }
    var lastAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p.bytesDone) {
        val now = System.currentTimeMillis()
        val dt = now - lastAt
        if (dt >= 400 && p.bytesDone >= lastBytes) {
            speed = (p.bytesDone - lastBytes) * 1000.0 / dt
            lastBytes = p.bytesDone
            lastAt = now
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    phaseTitle(p.phase),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (p.phase == ImportPhase.EXTRACTING) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }

            Spacer(Modifier.height(10.dp))
            val f = p.fraction
            // 不加圆角/高度和显式 trackColor 的话，默认轨道色与 secondaryContainer 撞色，
            // 在卡片里只能看见一个小圆点，等于没有进度条
            val barMod = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp))
            // gapSize/drawStopIndicator 默认会在轨道末端画一个点，配圆角轨道很突兀，去掉
            if (f != null) {
                LinearProgressIndicator(
                    progress = { f },
                    modifier = barMod,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(
                    modifier = barMod,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.14f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                detailLine(p, speed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private fun phaseTitle(phase: ImportPhase): String = when (phase) {
    ImportPhase.PREPARING -> "准备导入…"
    ImportPhase.EXTRACTING -> "正在解压模型包"
    ImportPhase.INSTALLING -> "安装到模型库"
    ImportPhase.VERIFYING -> "校验文件"
}

private fun detailLine(p: ImportProgress, speed: Double): String {
    val f = p.fraction
    if (p.bytesTotal <= 0L || f == null) {
        return if (p.filesDone > 0) "已解压 ${p.filesDone} 个文件" else "读取中…"
    }
    val head = "${fmtBytes(p.bytesDone)} / ${fmtBytes(p.bytesTotal)} · ${(f * 100).toInt()}%"
    if (p.phase != ImportPhase.EXTRACTING) return head
    if (speed < 64 * 1024) return "$head · 测速中"
    val eta = ((p.bytesTotal - p.bytesDone) / speed).toLong()
    return "$head · ${fmtRate(speed.toLong())} · 约剩 ${etaText(eta)}"
}

/** 1.6 GB/s 这种量级不需要两位小数 */
private fun fmtRate(bps: Long): String {
    if (bps < 1024) return "$bps B/s"
    if (bps < 1024 * 1024) return "${bps / 1024} KB/s"
    val mb = bps / (1024.0 * 1024.0)
    return if (mb < 10) String.format("%.1f MB/s", mb) else String.format("%.0f MB/s", mb)
}

private fun etaText(sec: Long): String = when {
    sec <= 0 -> "几秒"
    sec < 60 -> "${sec} 秒"
    else -> "${sec / 60} 分 ${sec % 60} 秒"
}

@Composable
private fun ModelCard(m: InstalledModel, onDeploy: () -> Unit, onDelete: () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(m.manifest.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${m.manifest.kind.uppercase()} · v${m.manifest.version} · ${fmtBytes(m.bytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "运行时 ${m.runtimeLabel}" +
                            if (m.embeddedRuntime) " · 随包内置" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (m.embeddedRuntime) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onDeploy() }) { Icon(Icons.Default.RocketLaunch, "部署") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "删除") }
            }
            if (m.issues.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                m.issues.forEach {
                    Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 参数编辑：完全由 manifest 的 params schema 驱动，新增模型不需要改代码 */
@Composable
fun ParamDialog(inst: ServiceInstance, onDismiss: () -> Unit) {
    val values = remember { mutableStateMapOf<String, String>().apply { putAll(inst.params) } }
    val running = inst.state.value == ServiceState.RUNNING
    // enum（分辨率这类）已经挪到卡片上直接点选，这里不再重复。
    // 每打开一次对话框都要新建视图树、点输入框还要拉起输入法（~90 MB），能少开一次是一次。
    val editable = inst.manifest.params.filter { it.type != "enum" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${inst.manifest.name} · 参数") },
        text = {
            if (editable.isEmpty()) {
                Text("该模型没有可调参数")
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(editable, key = { it.key }) { p ->
                        ParamRow(p, values[p.key] ?: "") { v -> values[p.key] = v }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                inst.params = inst.params.toMutableMap().apply {
                    putAll(values)
                    if (running) {
                        // 参数只在启动时读取，改完需重启才生效
                    }
                }
                onDismiss()
            }) { Text(if (running) "保存（需重启生效）" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ParamRow(p: ParamSpec, value: String, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            when (p.type) {
                "bool" -> Checkbox(
                    checked = value == "1" || value.equals("true", true),
                    onCheckedChange = { onChange(if (it) "1" else "0") },
                )
                else -> OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    modifier = Modifier.width(140.dp),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (p.type == "int" || p.type == "float") KeyboardType.Number else KeyboardType.Text,
                    ),
                    suffix = if (p.unit.isNotEmpty()) { { Text(p.unit) } } else null,
                )
            }
        }
        if (p.type == "enum" && p.options.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                p.options.forEach { o ->
                    FilterChip(selected = value == o, onClick = { onChange(o) }, label = { Text(o) })
                }
            }
        }
        if (p.help.isNotEmpty()) {
            Text(p.help, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (p.type == "int" || p.type == "float") {
            val hint = buildList {
                p.min?.let { add("min $it") }
                p.max?.let { add("max $it") }
            }.joinToString("  ")
            if (hint.isNotEmpty()) {
                Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

fun fmtBytes(b: Long): String {
    if (b <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var v = b.toDouble()
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format("%.2f %s", v, units[i])
}
