package dev.pocketmodel.studio.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.pocketmodel.studio.MainActivity
import dev.pocketmodel.studio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * 全局核心：持有所有 ServiceInstance，唯一负责端口分配。
 * 前台 Service 只负责「让进程不被系统回收」+ 通知栏，业务逻辑在这里。
 */
object StudioCore {
    private var appCtx: Context? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _instances = MutableStateFlow<Map<String, ServiceInstance>>(emptyMap())
    val instances = _instances.asStateFlow()

    var repo: ModelRepository? = null
        private set

    /**
     * 本机 HTTP 网关：对内网暴露 WebUI / OpenAI 兼容接口，见 WebGateway 注释。
     * 用 StateFlow 而不是普通字段存：换端口时对象会被整体替换，UI 必须能观察到，
     * 否则设置页会一直显示旧端口（实测过：实际已经在 7861 监听，界面还写着 7860）。
     */
    private val _gateway = MutableStateFlow<WebGateway?>(null)
    val gatewayFlow: StateFlow<WebGateway?> = _gateway.asStateFlow()

    val gateway: WebGateway?
        get() = _gateway.value

    fun init(ctx: Context) {
        if (appCtx != null) return
        appCtx = ctx.applicationContext
        repo = ModelRepository(ctx.applicationContext)
    }

    // ---- 网关端口偏好 ------------------------------------------------------
    // 存 SharedPreferences 而不是只留 UI state：Activity 销毁后 Service 仍要用同一个端口
    // 重启网关，否则重进 App 端口就变了，局域网里已配好的客户端全部失效。
    private const val PREF_GW = "studio_prefs"
    private const val KEY_GW_PORT = "gateway_port"

    private fun prefs() = appCtx?.getSharedPreferences(PREF_GW, Context.MODE_PRIVATE)

    /** 用户设过的端口；没设过或越界时回落到默认值 */
    fun savedGatewayPort(): Int =
        prefs()?.getInt(KEY_GW_PORT, WebGateway.DEFAULT_PORT)
            ?.takeIf { it in 1024..65535 }
            ?: WebGateway.DEFAULT_PORT

    fun saveGatewayPort(port: Int) {
        if (port !in 1024..65535) return
        prefs()?.edit()?.putInt(KEY_GW_PORT, port)?.apply()
    }

    /**
     * 换端口重启。先落盘再重建：stop() 只关监听、不动后端实例，
     * 已经在跑的服务不会因为换端口被打断。
     */
    fun restartGateway(port: Int = savedGatewayPort()): WebGateway? {
        saveGatewayPort(port)
        gateway?.stop()
        _gateway.value = null
        return startGateway(port)
    }

    /**
     * 启动网关（幂等）。放在前台 Service 里而不是 Activity：退出 App 后局域网设备
     * 仍要能访问，Activity 会被销毁，Service 不会。
     */
    fun startGateway(preferred: Int = savedGatewayPort()): WebGateway? {
        val c = appCtx ?: return null
        val g = gateway ?: WebGateway(c).also { gw ->
            // 后端 = 第一个 RUNNING 的实例；多实例时先到先得（够用，且顺序可预期）
            gw.backendPort = {
                _instances.value.values
                    .firstOrNull { it.state.value == ServiceState.RUNNING && it.port.value > 0 }
                    ?.port?.value
            }
            gw.backendLabel = {
                _instances.value.values
                    .firstOrNull { it.state.value == ServiceState.RUNNING }
                    ?.manifest?.name ?: ""
            }
            gw.backendKind = {
                _instances.value.values
                    .firstOrNull { it.state.value == ServiceState.RUNNING }
                    ?.manifest?.kind ?: ""
            }
            // 模型包自带 webui/index.html 时优先用它（不同分辨率的包默认 UI 不同）
            gw.backendDir = {
                _instances.value.values
                    .firstOrNull { it.state.value == ServiceState.RUNNING }
                    ?.modelDir?.absolutePath
            }
            // 实例的生效参数（生成长度、上下文）—— 网关的默认值与 WebUI 都从这里取
            gw.backendParams = {
                _instances.value.values
                    .firstOrNull { it.state.value == ServiceState.RUNNING }
                    ?.params ?: emptyMap()
            }
            // 模型声明了哪些分辨率，就让 WebUI 只显示哪些（避免选到没有链的尺寸）
            gw.backendSizes = {
                _instances.value.values
                    .firstOrNull { it.state.value == ServiceState.RUNNING }
                    ?.manifest?.params
                    ?.firstOrNull { it.key == "size" }
                    ?.options ?: emptyList()
            }
            // 后端说什么协议：healthPath="tcp" 是本项目的约定，表示「不是 HTTP，是行协议」
            // （llama-split --serve），网关要自己用 SplitClient 说话。
            gw.backendProto = {
                val inst = _instances.value.values
                    .firstOrNull { it.state.value == ServiceState.RUNNING }
                if (inst?.manifest?.entry?.healthPath == "tcp") "split" else "http"
            }
            _gateway.value = gw
        }
        if (!g.started) g.start(preferred)
        return g
    }

    fun instanceFor(model: ModelManifest, dir: File): ServiceInstance {
        val key = model.id
        _instances.value[key]?.let { return it }
        // 可为 null：模型包自带 runtime/ 时不需要内置运行时
        val rt = Runtimes.byId(model.runtime)
        val inst = ServiceInstance(key, model, rt, dir, scope)
        _instances.value = _instances.value + (key to inst)
        return inst
    }

    fun removeInstance(key: String) {
        _instances.value[key]?.stop()
        _instances.value = _instances.value - key
    }

    /** 从 7800 起找第一个能 bind 的端口 */
    fun allocatePort(start: Int = 7800): Int {
        val used = _instances.value.values.mapNotNull { it.port.value.takeIf { p -> p > 0 } }.toSet()
        for (p in start until start + 200) {
            if (p in used) continue
            try {
                ServerSocket(p).use { return p }
            } catch (_: Exception) {
            }
        }
        return start + 200
    }
}

class ServiceHost : LifecycleService() {

    private val channelId = "services"
    private val noteId = 1001

    /** 推理是长跑任务，熄屏后必须让 CPU 继续跑；释放只在 onDestroy 做，避免引用计数错配 */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * 命令通道：走广播而不是 Activity intent。
     *
     * 原因（实测）：Activity 已在前台时 `am start --es ...` 只会 delivered 给栈顶实例，
     * onNewIntent 在 ColorOS 上并不回调（试过自定义 extra、保持屏幕唤醒都不行），
     * 于是第二次及以后的调试命令全被静默丢弃。广播不依赖 Activity 生命周期，稳定可用。
     *
     *   adb shell am broadcast -a dev.pocketmodel.studio.CMD --es cmd start --es model <id>
     *   adb shell am broadcast -a dev.pocketmodel.studio.CMD --es cmd stop
     */
    private val cmdReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getStringExtra("cmd")) {
                "start" -> {
                    val id = intent.getStringExtra("model") ?: return
                    StudioCore.scope.launch {
                        val repo = StudioCore.repo ?: return@launch
                        val m = repo.list().firstOrNull { it.manifest.id == id } ?: return@launch
                        StudioCore.instanceFor(m.manifest, m.dir)
                            .start(context.applicationContext, StudioCore.allocatePort())
                    }
                }
                "stop" -> StudioCore.scope.launch {
                    StudioCore.instances.value.values.forEach { it.stop() }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        StudioCore.init(this)
        createChannel()
        startForeground(noteId, buildNotification())
        // 网关跟着前台服务走：即使 Activity 退出，局域网里的浏览器仍能访问
        StudioCore.startGateway()

        // ★ targetSdk 35：Android 14+ 动态注册**必须**显式声明导出标志，否则抛
        //   SecurityException（被 runCatching 吞掉 → 广播通道整条静默失效，
        //   表现为 `am broadcast` 返回 result=0 但 App 毫无反应）。
        //   adb shell 属于外部来源，所以必须是 RECEIVER_EXPORTED。
        val cmdFilter = android.content.IntentFilter("dev.pocketmodel.studio.CMD")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(cmdReceiver, cmdFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                registerReceiver(cmdReceiver, cmdFilter)
            }
        }.onFailure {
            android.util.Log.e("Studio", "命令广播注册失败: ${it.message}")
        }

        // 实测（ColorOS / Android 16）：熄屏后 App 及其子进程会被挂起 ——
        // 表现为「TCP 端口还在 LISTEN，但任何连接都超时」，连 127.0.0.1 都连不上。
        // 前台服务只能防回收，防不住挂起，必须自己拿 PARTIAL_WAKE_LOCK。
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "PocketStudio:runtime"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        // 慢速刷新通知，避免每帧都更新
        lifecycleScope.launch(Dispatchers.IO) {
            StudioCore.instances.collect { map ->
                val running = map.values.count { it.state.value == ServiceState.RUNNING }
                val mgr = getSystemService(NotificationManager::class.java)
                mgr.notify(noteId, buildNotification(running, map.size))
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(cmdReceiver) }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "本地服务", NotificationManager.IMPORTANCE_LOW)
            ch.description = "模型服务的运行状态"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(running: Int = 0, total: Int = 0): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_service)
            .setContentTitle("Pocket Model Studio")
            .setContentText(if (total == 0) "无服务运行" else "$running/$total 个服务运行中")
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }
}
