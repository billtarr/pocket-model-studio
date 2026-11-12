package dev.pocketmodel.studio.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.pocketmodel.studio.core.StudioCore
import java.io.File

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("后台运行", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            val pm = ctx.getSystemService(PowerManager::class.java)
            val ignoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("电池优化白名单", Modifier.weight(1f))
                        Text(
                            if (ignoring) "已加入" else "未加入",
                            color = if (ignoring) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "熄屏后系统会挂起应用（ColorOS 实测更激进），表现为端口仍在监听但所有请求超时，" +
                            "连 127.0.0.1 都连不上。加入白名单 + 前台服务可显著降低被冻结的概率。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!ignoring) {
                        OutlinedButton(onClick = {
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${ctx.packageName}"),
                                    )
                                )
                            }
                        }) { Text("加入白名单") }
                    }
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("WebUI 与局域网访问", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            // 网关对象在换端口时会被整体替换，必须走 StateFlow 才能观察到
            val gw by StudioCore.gatewayFlow.collectAsState()
            val started = gw?.started == true
            val lan = gw?.takeIf { started }?.lanUrl
            // 网关的 port/started 不是 Compose state：改完端口靠 status 变化触发重组读到新值
            var portText by remember { mutableStateOf(StudioCore.savedGatewayPort().toString()) }
            var status by remember { mutableStateOf<String?>(null) }

            fun applyPort() {
                val p = portText.trim().toIntOrNull()
                if (p == null || p !in 1024..65535) {
                    status = "端口需在 1024–65535 之间"
                    return
                }
                // restartGateway 幂等：没启动时等价于「按这个端口启动」
                val g = StudioCore.restartGateway(p)
                if (g == null) {
                    status = "启动失败：应用未初始化"
                } else if (!g.started) {
                    status = "启动失败：${g.lastError ?: "未知原因"}"
                } else {
                    status = if (g.port == p) "已监听 :${g.port}" else "$p 被占用，已顺延到 :${g.port}"
                    portText = g.port.toString()
                }
            }

            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("本机网关", Modifier.weight(1f))
                        Text(
                            if (started) "运行中 :${gw?.port}" else "未启动",
                            color = if (started) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "网关把只听 127.0.0.1 的后端包了一层：App 内 WebUI 走同源页面，局域网设备" +
                            "（同一 Wi-Fi / 热点）直接访问下面的地址即可，还可使用 OpenAI 兼容接口 " +
                            "/v1/chat/completions。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = portText,
                            onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                            label = { Text("端口") },
                            singleLine = true,
                            modifier = Modifier.width(140.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        Spacer(Modifier.width(10.dp))
                        OutlinedButton(onClick = { applyPort() }) {
                            Text(if (started) "应用并重启" else "启动网关")
                        }
                    }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (it.startsWith("启动失败") || it.contains("之间") || it.contains("被占用"))
                                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (started) {
                        Text(
                            lan ?: "未检测到局域网地址（未连 Wi-Fi？）",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (lan != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "本机 http://127.0.0.1:${gw?.port}/",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "注意：熄屏后 ColorOS 会挂起本 App（端口还在但连接超时），局域网访问时请保持屏幕常亮。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("存储", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("模型库", Modifier.weight(1f))
                        Text(fmtBytes(StudioCore.repo?.storageUsed() ?: 0L))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("日志目录", Modifier.weight(1f))
                        Text(fmtBytes(dirSize(File(ctx.filesDir, "logs"))))
                    }
                    OutlinedButton(onClick = { File(ctx.filesDir, "logs").deleteRecursively() }) {
                        Text("清空日志")
                    }
                }
            }
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("关于", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        item {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Pocket Model Studio 0.1.0", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "本地模型服务管理器：LLM / 图像 / 视频三类服务统一接入，" +
                            "每个服务一个独立端口与开关。运行时（可执行文件与依赖库）随模型包分发，" +
                            "导入即可运行，无需另装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "设备 ${android.os.Build.MODEL} · ${android.os.Build.SOC_MODEL}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun dirSize(f: File): Long = if (!f.exists()) 0L else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
