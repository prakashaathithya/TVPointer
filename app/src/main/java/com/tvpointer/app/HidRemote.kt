package com.tvpointer.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors
import kotlin.math.roundToInt

sealed interface HidState {
    data object Unsupported : HidState
    data object BluetoothOff : HidState
    data object Starting : HidState
    /** Registered as a mouse/keyboard; waiting for the TV to connect. */
    data class Ready(val connecting: String? = null, val error: String? = null) : HidState
    data class Connected(val name: String) : HidState
}

data class PairedDevice(val name: String, val address: String)

/**
 * Makes the phone a Bluetooth HID mouse + keyboard (Android 9+ BluetoothHidDevice).
 * The TV shows its own system cursor; nothing has to be installed on the TV.
 *
 * Android drops the HID registration as soon as the app is not in the foreground
 * (even a system popup over it counts), so [RemoteService] keeps the app in the
 * foreground with a notification, and we re-register automatically if it is lost.
 */
@SuppressLint("MissingPermission") // permissions are requested in MainActivity before start()
class HidRemote private constructor(private val context: Context) {

    companion object {
        @Volatile private var instance: HidRemote? = null
        fun get(context: Context): HidRemote = instance ?: synchronized(this) {
            instance ?: HidRemote(context.applicationContext).also { instance = it }
        }
        private const val MAX_RETRIES = 5
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    // One thread so reports always reach the TV in order.
    private val sender = Executors.newSingleThreadExecutor()
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    @Volatile private var registered = false
    private var registering = false
    private var binding = false
    private var wanted = false
    private var retries = 0

    private val _state = MutableStateFlow<HidState>(HidState.Starting)
    val state: StateFlow<HidState> = _state

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired

    // Devices found by searching from the phone.
    private val _found = MutableStateFlow<List<PairedDevice>>(emptyList())
    val found: StateFlow<List<PairedDevice>> = _found
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private var pairThenConnect: String? = null

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = deviceExtra(intent) ?: return
                    val name = d.name ?: return
                    if (d.bondState == BluetoothDevice.BOND_BONDED) return
                    _found.value = _found.value.filterNot { it.address == d.address } +
                        PairedDevice(name, d.address)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _searching.value = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    refreshPaired()
                    val d = deviceExtra(intent) ?: return
                    if (d.bondState == BluetoothDevice.BOND_BONDED) {
                        _found.value = _found.value.filterNot { it.address == d.address }
                        if (d.address == pairThenConnect) {
                            pairThenConnect = null
                            main.postDelayed({ connect(d.address) }, 1000)
                        }
                    } else if (d.bondState == BluetoothDevice.BOND_NONE && d.address == pairThenConnect) {
                        pairThenConnect = null
                        _state.value = HidState.Ready(error = "Pairing was cancelled or refused by the TV.")
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val on = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) == BluetoothAdapter.STATE_ON
                    if (on && wanted) start() else if (!on) {
                        registered = false; host = null; _state.value = HidState.BluetoothOff
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun deviceExtra(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    init {
        ContextCompat.registerReceiver(
            context, btReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    // ------------------------------------------------------------ lifecycle

    /** Called by [RemoteService] (and on app open). Safe to call repeatedly. */
    fun start() {
        wanted = true
        val a = adapter ?: run { _state.value = HidState.Unsupported; return }
        if (!a.isEnabled) { _state.value = HidState.BluetoothOff; return }
        refreshPaired()
        val h = hid
        if (h != null) {
            if (!registered && !registering) register(h)
            return
        }
        if (binding) return
        binding = true
        _state.value = HidState.Starting
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                binding = false
                val p = proxy as BluetoothHidDevice
                hid = p
                retries = 0
                register(p)
            }
            override fun onServiceDisconnected(profile: Int) {
                hid = null
                registered = false
                host = null
                if (wanted) _state.value = HidState.Starting
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { binding = false; _state.value = HidState.Unsupported }
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        hid?.let { h ->
            runCatching { host?.let { h.disconnect(it) } }
            runCatching { h.unregisterApp() }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
        host = null
        registered = false
        registering = false
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            main.post { onRegistration(pluggedDevice, isRegistered) }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            main.post {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        prefs.edit().putString("last", device.address).apply()
                        _state.value = HidState.Connected(device.name ?: device.address)
                    }
                    BluetoothProfile.STATE_CONNECTING ->
                        _state.value = HidState.Ready(connecting = device.name ?: device.address)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (host == null || host == device) {
                            host = null
                            if (registered) _state.value = HidState.Ready()
                        }
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val size = when (id.toInt()) {
                HidReports.ID_KEYBOARD -> HidReports.KEYBOARD_SIZE
                HidReports.ID_MOUSE -> HidReports.MOUSE_SIZE
                else -> HidReports.CONSUMER_SIZE
            }
            hid?.replyReport(device, type, id, ByteArray(size))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }
    }

    private fun register(h: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "TV Pointer", "Phone trackpad and keyboard", "TV Pointer",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR,
        )
        registering = true
        val ok = runCatching { h.registerApp(sdp, null, null, callbackExecutor, callback) }.getOrDefault(false)
        if (!ok) {
            registering = false
            retryRegister()
        }
    }

    private fun onRegistration(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
        registering = false
        registered = isRegistered
        if (isRegistered) {
            retries = 0
            if (_state.value !is HidState.Connected) _state.value = HidState.Ready()
            val target = pluggedDevice ?: lastDevice()
            if (target != null && host == null) connect(target.address)
        } else {
            host = null
            if (wanted) retryRegister()
        }
    }

    /** Registration was lost or refused: try again a few times before showing an error. */
    private fun retryRegister() {
        val h = hid ?: return
        if (!wanted) return
        if (retries >= MAX_RETRIES) {
            _state.value = HidState.Ready(
                error = "Couldn't turn on mouse mode. Turn Bluetooth off and on, close other " +
                    "Bluetooth keyboard/mouse apps, then tap Try again.",
            )
            return
        }
        retries++
        _state.value = HidState.Starting
        main.postDelayed({ if (!registered && !registering) register(h) }, 1000L * retries)
    }

    /** Manual retry from the error message. */
    fun retry() {
        retries = 0
        val h = hid
        if (h == null) start() else if (!registered && !registering) register(h)
    }

    fun refreshPaired() {
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        _paired.value = bonded.map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name.lowercase() }
    }

    private fun lastDevice(): BluetoothDevice? {
        val address = prefs.getString("last", null) ?: return null
        return runCatching { adapter?.bondedDevices?.firstOrNull { it.address == address } }.getOrNull()
    }

    fun connect(address: String) {
        val h = hid
        if (h == null || !registered) {
            _state.value = HidState.Ready(error = "Mouse mode isn't on yet. Wait a moment and try again.")
            retry()
            return
        }
        val device = adapter?.getRemoteDevice(address) ?: return
        _state.value = HidState.Ready(connecting = device.name ?: address)
        if (!h.connect(device)) {
            _state.value = HidState.Ready(error = "Couldn't connect to ${device.name ?: address}.")
        }
    }

    fun disconnect() {
        val h = hid ?: return
        host?.let { h.disconnect(it) }
        prefs.edit().remove("last").apply()
    }

    // ------------------------------------------------- search from the phone

    fun startSearch() {
        val a = adapter ?: return
        _found.value = emptyList()
        if (a.isDiscovering) a.cancelDiscovery()
        _searching.value = runCatching { a.startDiscovery() }.getOrDefault(false)
    }

    /** Pair with a device found by search, then connect as mouse/keyboard. */
    fun pairAndConnect(address: String) {
        val a = adapter ?: return
        runCatching { a.cancelDiscovery() }
        _searching.value = false
        val d = a.getRemoteDevice(address)
        if (d.bondState == BluetoothDevice.BOND_BONDED) { connect(address); return }
        pairThenConnect = address
        _state.value = HidState.Ready(connecting = d.name ?: address)
        if (!d.createBond()) {
            pairThenConnect = null
            _state.value = HidState.Ready(error = "Couldn't start pairing with ${d.name ?: address}.")
        }
    }

    // --------------------------------------------------------------- sending

    private fun send(id: Int, report: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        sender.execute { runCatching { h.sendReport(d, id, report) } }
    }

    private fun mouse(buttons: Int, dx: Int = 0, dy: Int = 0, wheel: Int = 0, pan: Int = 0) {
        send(HidReports.ID_MOUSE, byteArrayOf(
            buttons.toByte(), dx.toByte(), dy.toByte(), wheel.toByte(), pan.toByte(),
        ))
    }

    // Fractions left over from rounding, so slow movements still add up.
    private var restX = 0f
    private var restY = 0f
    private var restWheel = 0f
    private var restPan = 0f
    private var heldButtons = 0

    /** Relative cursor movement in TV pointer units. */
    fun move(dx: Float, dy: Float) {
        restX += dx; restY += dy
        var x = restX.roundToInt(); var y = restY.roundToInt()
        restX -= x; restY -= y
        while (x != 0 || y != 0) {
            val sx = x.coerceIn(-127, 127); val sy = y.coerceIn(-127, 127)
            mouse(heldButtons, sx, sy)
            x -= sx; y -= sy
        }
    }

    /** Scroll by finger movement; [step] is the finger distance per wheel notch. */
    fun scroll(dx: Float, dy: Float, step: Float) {
        restWheel += dy / step   // finger down -> content down (wheel up)
        restPan -= dx / step     // finger right -> content right (pan left)
        val w = restWheel.toInt().coerceIn(-127, 127)
        val p = restPan.toInt().coerceIn(-127, 127)
        restWheel -= w; restPan -= p
        if (w != 0 || p != 0) mouse(heldButtons, wheel = w, pan = p)
    }

    fun click() { mouse(1); mouse(0) }

    fun press() { heldButtons = 1; mouse(1) }
    fun release() { heldButtons = 0; mouse(0) }

    private fun consumer(usage: Int) {
        send(HidReports.ID_CONSUMER, byteArrayOf((usage and 0xFF).toByte(), (usage shr 8).toByte()))
        send(HidReports.ID_CONSUMER, ByteArray(HidReports.CONSUMER_SIZE))
    }

    fun back() = consumer(HidReports.USAGE_AC_BACK)
    fun home() = consumer(HidReports.USAGE_AC_HOME)

    private fun key(usage: Int, shift: Boolean = false) {
        val mod = if (shift) HidReports.MOD_SHIFT else 0
        send(HidReports.ID_KEYBOARD, byteArrayOf(mod.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(HidReports.ID_KEYBOARD, ByteArray(HidReports.KEYBOARD_SIZE))
    }

    fun enter() = key(HidReports.KEY_ENTER)
    fun backspace(count: Int = 1) = repeat(count) { key(HidReports.KEY_BACKSPACE) }

    /** Types text; returns how many characters couldn't be typed (non-English letters, emoji). */
    fun type(text: String): Int {
        var skipped = 0
        text.forEach { c ->
            val k = HidReports.keyFor(c)
            if (k == null) skipped++ else key(k.first, k.second)
        }
        return skipped
    }

    val isConnected: Boolean get() = host != null
}
