package com.tvpointer.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()
    private var permissionsGranted by mutableStateOf(false)

    /** Without these the app can't work at all. */
    private val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN,
        ) else emptyArray()

    /** Nice to have: the notification, and searching for the TV on Android 9-11. */
    private val optionalPermissions: Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) add(Manifest.permission.ACCESS_FINE_LOCATION)
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checkPermissionsAndStart()
        }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkPermissionsAndStart()
        }

    private val makeDiscoverable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                if (!permissionsGranted) {
                    PermissionScreen(onAllow = {
                        permissionLauncher.launch(requiredPermissions + optionalPermissions)
                    })
                } else {
                    App(vm)
                }
            }
        }
        val missingOptional = optionalPermissions.any { !granted(it) }
        if (requiredPermissions.all(::granted) && missingOptional && savedInstanceState == null) {
            permissionLauncher.launch(optionalPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        checkPermissionsAndStart()
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissionsAndStart() {
        permissionsGranted = requiredPermissions.all(::granted)
        if (permissionsGranted) RemoteService.start(this)
    }

    fun requestEnableBluetooth() =
        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

    fun requestDiscoverable() = makeDiscoverable.launch(
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
    )

    @Composable
    private fun App(vm: RemoteViewModel) {
        val state by vm.remote.state.collectAsStateWithLifecycle()
        when (val s = state) {
            is HidState.Connected -> RemoteScreen(vm, s.name)
            else -> PairScreen(
                vm = vm,
                state = s,
                onEnableBluetooth = ::requestEnableBluetooth,
                onMakeDiscoverable = ::requestDiscoverable,
            )
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val accent = Color(0xFF3D7BFF)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = accent, background = Color(0xFF12151C), surface = Color(0xFF1B1F2A))
    } else {
        lightColorScheme(primary = accent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
