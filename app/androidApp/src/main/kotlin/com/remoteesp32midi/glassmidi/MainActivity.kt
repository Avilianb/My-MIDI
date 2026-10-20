package com.remoteesp32midi.glassmidi

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.remoteesp32midi.glassmidi.ble.BleDevice
import com.remoteesp32midi.glassmidi.service.MidiConnectionService
import com.remoteesp32midi.glassmidi.ui.GlassMidiApp

class MainActivity : ComponentActivity() {
    private val container by lazy {
        (application as GlassMidiApplication).container
    }
    private var permissionsReady by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionsReady = hasRequiredPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        permissionsReady = hasRequiredPermissions()

        setContent {
            GlassMidiApp(
                store = container.store,
                permissionsReady = permissionsReady,
                onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                onConnect = { device: BleDevice ->
                    runCatching { MidiConnectionService.start(this) }
                    container.store.connect(device)
                },
                onDisconnect = {
                    container.store.disconnect()
                    MidiConnectionService.stop(this)
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
