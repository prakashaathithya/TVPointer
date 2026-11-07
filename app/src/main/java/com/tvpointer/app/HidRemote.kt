package com.tvpointer.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
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
 */
@SuppressLint("MissingPermission") // permissions are requested in MainActivity before start()
class HidRemote(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE)

    // One thread so reports always reach the TV in order.
    private val sender = Executors.newSingleThreadExecutor()

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var binding = false

    private val _state = MutableStateFlow<HidState>(HidState.Starting)
    val state: StateFlow<HidState> = _state

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired

    // ------------------------------------------------------------ lifecycle

    fun start() {
        val a = adapter ?: run { _state.value = HidState.Unsupported; return }
        if (!a.isEnabled) { _state.value = HidState.BluetoothOff; return }
        refreshPaired()
        if (hid != null || binding) return
        binding = true
        _state.value = HidState.Starting
        val ok = a.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                binding = false
                val h = proxy as BluetoothHidDevice
                hid = h
                register(h)
            }
            override fun onServiceDisconnected(profile: Int) {
                hid = null
                registered = false
                host = null
                _state.value = HidState.Starting
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) { binding = false; _state.value = HidState.Unsupported }
    }

    fun stop() {
        hid?.let { h ->
            runCatching { host?.let { h.disconnect(it) } }
            runCatching { h.unregisterApp() }
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        }
        hid = null
        host = null
        registered = false
    }

    private fun register(h: BluetoothHidDevice) {
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "TV Pointer", "Phone trackpad and keyboard", "TV Pointer",
            BluetoothHidDevice.SUBCLASS1_COMBO, HidReports.DESCRIPTOR,
        )
        val callback = object : BluetoothHidDevice.Callback() {
            override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
                registered = isRegistered
                if (!isRegistered) {
                    // Some phones block HID registration (another app may hold it).
                    if (_state.value !is HidState.Connected) {
                        _state.value = HidState.Ready(error = "Couldn't turn on mouse mode. Close other remote/keyboard apps and reopen.")
                    }
                    return
                }
                _state.value = HidState.Ready()
                val target = pluggedDevice ?: lastDevice()
                if (target != null) connect(target.address)
            }

            override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        host = device
                        prefs.edit().putString("last", device.address).apply()
                        _state.value = HidState.Connected(device.name ?: device.address)
                    }
                    BluetoothProfile.STATE_CONNECTING ->
                        _state.value = HidState.Ready(connecting = device.name ?: device.address)
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (host == device || _state.value !is HidState.Connected) {
                            host = null
                            _state.value = HidState.Ready()
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
        val ok = h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(), callback)
        if (!ok) _state.value = HidState.Ready(error = "This phone doesn't allow Bluetooth mouse mode.")
    }

    fun refreshPaired() {
        val bonded = runCatching { adapter?.bondedDevices }.getOrNull().orEmpty()
        _paired.value = bonded.map { PairedDevice(it.name ?: it.address, it.address) }
            .sortedBy { it.name.lowercase() }
    }

    private fun lastDevice(): BluetoothDevice? {
        val address = prefs.getString("last", null) ?: return null
        return adapter?.bondedDevices?.firstOrNull { it.address == address }
    }

    fun connect(address: String) {
        val h = hid ?: return
        if (!registered) return
        val device = adapter?.getRemoteDevice(address) ?: return
        _state.value = HidState.Ready(connecting = device.name ?: address)
        if (!h.connect(device)) {
            _state.value = HidState.Ready(error = "Couldn't connect. Pair from the TV first (see steps below).")
        }
    }

    fun disconnect() {
        val h = hid ?: return
        host?.let { h.disconnect(it) }
        prefs.edit().remove("last").apply()
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
