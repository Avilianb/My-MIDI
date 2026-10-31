package com.remoteesp32midi.glassmidi

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.service.MidiConnectionService
import com.remoteesp32midi.glassmidi.ui.GlassMidiApp

class MainActivity : ComponentActivity() {
    private val container by lazy {
        (application as GlassMidiApplication).container
    }
    private var permissionsReady by mutableStateOf(false)
    private var landscapeDockRevealed by mutableStateOf(false)
    private var landscapeDockTick by mutableIntStateOf(0)
    private var edgeTouchStartX = 0f
    private var edgeTouchStartY = 0f
    private var edgeTouchActive = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsReady = hasRequiredPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // F-10：现场演出防息屏，保持控制器界面常亮。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setupTransientSystemBarListeners()
        updateSystemBarsForOrientation(resources.configuration.orientation)
        permissionsReady = hasRequiredPermissions()

        setContent {
            GlassMidiApp(
                store = container.store,
                permissionsReady = permissionsReady,
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                onConnect = { device: BleDevice ->
                    runCatching { MidiConnectionService.start(this) }
                        .onSuccess { container.store.connect(device) }
                        .onFailure { container.store.reportError("无法启动蓝牙前台服务，请检查附近设备权限后重试") }
                },
                onDisconnect = {
                    container.store.disconnect()
                    MidiConnectionService.stop(this)
                },
                landscapeDockRevealed = landscapeDockRevealed,
                landscapeDockTick = landscapeDockTick,
                onLandscapeDockInteraction = { landscapeDockTick++ },
                onDismissLandscapeDock = {
                    landscapeDockRevealed = false
                    updateSystemBarsForOrientation(resources.configuration.orientation)
                },
            )
        }

        if (savedInstanceState == null) {
            requestPermissionsOnFirstLaunch()
        }
    }

    private fun requestPermissionsOnFirstLaunch() {
        val permissions = requiredPermissions()
        if (permissions.isEmpty()) return
        val preferences = getSharedPreferences("permissions", MODE_PRIVATE)
        if (preferences.getBoolean("requested", false)) return
        preferences.edit().putBoolean("requested", true).apply()
        permissionLauncher.launch(permissions)
    }

    override fun onResume() {
        super.onResume()
        permissionsReady = hasRequiredPermissions()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateSystemBarsForOrientation(newConfig.orientation)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            updateSystemBarsForOrientation(resources.configuration.orientation)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            val density = resources.displayMetrics.density
            val edgeThreshold = 32f * density
            val w = window.decorView.width.toFloat()
            val h = window.decorView.height.toFloat()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    edgeTouchStartX = ev.rawX
                    edgeTouchStartY = ev.rawY
                    edgeTouchActive = w > 0f && h > 0f && (
                        ev.rawY >= h - edgeThreshold ||
                            ev.rawY <= edgeThreshold ||
                            ev.rawX <= edgeThreshold ||
                            ev.rawX >= w - edgeThreshold
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    if (edgeTouchActive) {
                        val dx = ev.rawX - edgeTouchStartX
                        val dy = ev.rawY - edgeTouchStartY
                        val triggerDist = 14f * density
                        val swipedInward =
                            (edgeTouchStartY >= h - edgeThreshold && dy < -triggerDist) ||
                                (edgeTouchStartY <= edgeThreshold && dy > triggerDist) ||
                                (edgeTouchStartX <= edgeThreshold && dx > triggerDist) ||
                                (edgeTouchStartX >= w - edgeThreshold && dx < -triggerDist)
                        if (swipedInward) {
                            edgeTouchActive = false
                            revealLandscapeDock()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    edgeTouchActive = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun revealLandscapeDock() {
        landscapeDockRevealed = true
        landscapeDockTick++
    }

    private fun setupTransientSystemBarListeners() {
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { view, insets ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                val navVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                val statusVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
                if (navVisible || statusVisible) {
                    revealLandscapeDock()
                }
            }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                val barsShown =
                    (visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0 ||
                        (visibility and View.SYSTEM_UI_FLAG_FULLSCREEN) == 0
                if (barsShown) {
                    revealLandscapeDock()
                }
            }
        }
    }

    private fun updateSystemBarsForOrientation(orientation: Int) {
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        if (isLandscape) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            landscapeDockRevealed = false
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        requiredBlePermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredBlePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requiredPermissions(): Array<String> = buildList {
        addAll(requiredBlePermissions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()
}
