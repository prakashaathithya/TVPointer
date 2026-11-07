package com.tvpointer.app

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    private val neededPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else emptyArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checkPermissionsAndStart()
        }

    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.remote.start() }

    private val makeDiscoverable =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    // Refresh the paired list and Bluetooth state as they change.
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (permissionsGranted) vm.remote.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ContextCompat.registerReceiver(
            this, btReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        setContent {
            AppTheme {
                if (!permissionsGranted) {
                    PermissionScreen(onAllow = { permissionLauncher.launch(neededPermissions) })
                } else {
                    App(vm)
                }
            }
        }
        checkPermissionsAndStart()
    }

    override fun onDestroy() {
        unregisterReceiver(btReceiver)
        super.onDestroy()
    }

    private fun checkPermissionsAndStart() {
        permissionsGranted = neededPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (permissionsGranted) vm.remote.start()
    }

    fun requestEnableBluetooth() =
        enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))

    fun requestDiscoverable() = makeDiscoverable.launch(
        Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180)
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
