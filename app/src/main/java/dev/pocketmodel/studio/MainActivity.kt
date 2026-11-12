package dev.pocketmodel.studio

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import dev.pocketmodel.studio.core.ExecProbe
import dev.pocketmodel.studio.core.ServiceHost
import dev.pocketmodel.studio.core.StudioCore
import dev.pocketmodel.studio.ui.StudioApp
import dev.pocketmodel.studio.ui.StudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        StudioCore.init(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startForegroundService(Intent(this, ServiceHost::class.java))

        handleIntent(intent)

        setContent {
            StudioTheme {
                LaunchedEffect(Unit) { StudioCore.repo?.root?.mkdirs() }
                StudioApp()
            }
        }
    }

    /**
     * Activity 已在前台时，`am start` 只会走 onNewIntent，不会重建 Activity。
     * 没有这个重载，第二次及以后的调试 intent（start_model / stop_all）会被静默丢弃 ——
     * 之前「stop_all 杀不掉进程」就是这个原因。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        android.util.Log.i("Studio", "handleIntent extras=${intent.extras}")

        // 调试入口：am start ... --ez exec_probe true
        // 用来验证 targetSdk/W^X 是否允许从 filesDir 执行运行框架
        if (intent.getBooleanExtra("exec_probe", false)) {
            lifecycleScope.launch { ExecProbe.run(this@MainActivity) }
        }

        // 调试入口：am start ... --es start_model <id>   （跳过 UI 直接起服务）
        intent.getStringExtra("start_model")?.let { modelId ->
            lifecycleScope.launch(Dispatchers.IO) {
                val repo = StudioCore.repo ?: return@launch
                val m = repo.list().firstOrNull { it.manifest.id == modelId } ?: return@launch
                StudioCore.instanceFor(m.manifest, m.dir)
                    .start(this@MainActivity, StudioCore.allocatePort())
            }
        }
        if (intent.getBooleanExtra("stop_all", false)) {
            lifecycleScope.launch(Dispatchers.IO) {
                StudioCore.instances.value.values.forEach { it.stop() }
            }
        }
    }
}
