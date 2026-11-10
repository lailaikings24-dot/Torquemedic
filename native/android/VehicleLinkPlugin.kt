package com.torquemedic.vehiclelink
	#
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Bluetooth transport for OBD-II adapters: BLE GATT and Bluetooth Classic SPP. */
@CapacitorPlugin(
    name = "VehicleLink",
    permissions = [
        Permission(alias = "bluetooth", strings = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT]),
        Permission(alias = "location", strings = [Manifest.permission.ACCESS_FINE_LOCATION])
    ]
)
class VehicleLinkPlugin : Plugin() {
    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var classicSocket: android.bluetooth.BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readThread: Thread? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var classicReceiver: BroadcastReceiver? = null
    private var pendingAction: String? = null
    private var pendingArgs: JSObject? = null
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    override fun load() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        adapter = manager.adapter
    }

    @PluginMethod
    fun scan(call: PluginCall) = withPermission(call, "scan") {
        val transport = call.getString("transport", "ble")!!.lowercase()
        if (adapter?.isEnabled != true) return@withPermission call.reject("Bluetooth is disabled")
        when (transport) {
            "ble" -> startBleScan(call)
            "classic" -> startClassicScan(call)
            else -> call.reject("transport must be 'ble' or 'classic'")
        }
    }

    @PluginMethod
    fun stopScan(call: PluginCall) {
        stopBleScan()
        stopClassicScan()
        call.resolve()
    }

    @PluginMethod
    fun connect(call: PluginCall) = withPermission(call, "connect") {
        val address = call.getString("address") ?: return@withPermission call.reject("address is required")
        val device = try { adapter?.getRemoteDevice(address) } catch (_: IllegalArgumentException) { null }
            ?: return@withPermission call.reject("Invalid Bluetooth address")
        when (call.getString("transport", "ble")!!.lowercase()) {
            "ble" -> connectBle(device, call)
            "classic" -> connectClassic(device, call)
            else -> call.reject("transport must be 'ble' or 'classic'")
        }
    }

    @PluginMethod
    fun disconnect(call: PluginCall) {
        running.set(false)
        try { classicSocket?.close() } catch (_: Exception) {}
        classicSocket = null
        input = null
        output = null
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        notifyListeners("disconnected", JSObject())
        call.resolve()
    }

    @PluginMethod
    fun write(call: PluginCall) {
        val value = call.getString("value") ?: return call.reject("value is required")
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = output
        if (out != null) {
            worker.execute {
                try { out.write(bytes); out.flush(); call.resolve() }
                catch (e: Exception) { call.reject("Bluetooth write failed: ${e.message}") }
            }
            return
        }
        val g = gatt
        val characteristic = writeCharacteristic
        if (g == null || characteristic == null) return call.reject("No writable Bluetooth connection")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = g.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (result != BluetoothGatt.GATT_SUCCESS) call.reject("GATT write could not be started ($result)")
            else call.resolve()
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            if (!g.writeCharacteristic(characteristic)) call.reject("GATT write could not be started") else call.resolve()
        }
    }

    @PluginMethod
    fun isConnected(call: PluginCall) {
        call.resolve(JSObject().put("connected", running.get() && (gatt != null || classicSocket?.isConnected == true)))
    }

    private fun withPermission(call: PluginCall, action: String, block: () -> Unit) {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "bluetooth" else "location"
        if (getPermissionState(alias) == PermissionState.GRANTED) { block(); return }
        pendingAction = action
        pendingArgs = JSObject().put("address", call.getString("address")).put("transport", call.getString("transport"))
        requestPermissionForAlias(alias, call, "permissionResult")
    }

    @com.getcapacitor.annotation.PermissionCallback
    private fun permissionResult(call: PluginCall) {
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "bluetooth" else "location"
        if (getPermissionState(alias) != PermissionState.GRANTED) {
            pendingAction = null; pendingArgs = null
            call.reject("Bluetooth permission denied")
            return
        }
        val action = pendingAction
        val args = pendingArgs
        pendingAction = null; pendingArgs = null
        when (action) {
            "scan" -> {
                val transport = args?.getString("transport", "ble") ?: "ble"
                if (transport == "classic") startClassicScan(call) else startBleScan(call)
            }
            "connect" -> {
                val address = args?.getString("address") ?: return call.reject("address is required")
                val device = try { adapter?.getRemoteDevice(address) } catch (_: Exception) { null }
                    ?: return call.reject("Invalid Bluetooth address")
                if (args?.getString("transport", "ble") == "classic") connectClassic(device, call) else connectBle(device, call)
            }
            else -> call.reject("No pending Bluetooth operation")
        }
    }

    private fun startBleScan(call: PluginCall) {
        val scanner = adapter?.bluetoothLeScanner ?: return call.reject("BLE scanning is unavailable")
        stopBleScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) = publishDevice(result.device, "ble", result.rssi)
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { publishDevice(it.device, "ble", it.rssi) } }
            override fun onScanFailed(errorCode: Int) { notifyError("BLE scan failed ($errorCode)") }
        }
        bleScanCallback = cb
        try { scanner.startScan(cb); call.resolve(JSObject().put("scanning", true).put("transport", "ble")) }
        catch (e: SecurityException) { call.reject("Bluetooth scan permission is missing: ${e.message}") }
    }

    private var bleScanCallback: ScanCallback? = null
    private fun stopBleScan() {
        try { bleScanCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) } } catch (_: Exception) {}
        bleScanCallback = null
    }

    private fun startClassicScan(call: PluginCall) {
        val a = adapter ?: return call.reject("Bluetooth is unavailable")
        stopClassicScan()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (BluetoothDevice.ACTION_FOUND == intent.action) {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    publishDevice(device, "classic", rssi)
                }
                if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == intent.action) notifyListeners("scanComplete", JSObject().put("transport", "classic"))
            }
        }
        classicReceiver = receiver
        val filter = IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            if (!a.startDiscovery()) call.reject("Could not start Bluetooth Classic discovery")
            else call.resolve(JSObject().put("scanning", true).put("transport", "classic"))
        } catch (e: SecurityException) { call.reject("Bluetooth scan permission is missing: ${e.message}") }
    }

    private fun stopClassicScan() {
        try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        classicReceiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }
        classicReceiver = null
    }

    private fun publishDevice(device: BluetoothDevice, transport: String, rssi: Int) {
        val item = JSObject().put("address", device.address).put("name", try { device.name ?: "" } catch (_: SecurityException) { "" })
            .put("transport", transport).put("rssi", rssi)
        notifyListeners("deviceFound", item)
    }

    private fun connectBle(device: BluetoothDevice, call: PluginCall) {
        disconnectQuietly()
        try { gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) }
        catch (e: Exception) { call.reject("BLE connection failed: ${e.message}"); return }
        if (gatt == null) call.reject("Could not open BLE connection") else call.resolve(JSObject().put("connecting", true).put("address", device.address))
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { notifyError("GATT connection failed ($status)"); g.close(); if (gatt == g) gatt = null; return }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                running.set(true); g.discoverServices(); notifyListeners("connected", JSObject().put("transport", "ble"))
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                running.set(false); g.close(); if (gatt == g) gatt = null; notifyListeners("disconnected", JSObject().put("transport", "ble"))
            }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { notifyError("Could not discover GATT services"); return }
            writeCharacteristic = null; notifyCharacteristic = null
            for (service in g.services) for (characteristic in service.characteristics) {
                val props = characteristic.properties
                if (writeCharacteristic == null && (props and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) writeCharacteristic = characteristic
                if (notifyCharacteristic == null && (props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) notifyCharacteristic = characteristic
            }
            notifyCharacteristic?.let { c ->
                g.setCharacteristicNotification(c, true)
                c.getDescriptor(cccdUuid)?.let { descriptor ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    else { @Suppress("DEPRECATION") descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(descriptor) }
                }
            }
            if (writeCharacteristic == null) notifyError("Connected, but no writable GATT characteristic was found")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) = publishData(value)
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) { characteristic.value?.let { publishData(it) } }
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { if (status != BluetoothGatt.GATT_SUCCESS) notifyError("GATT write failed ($status)") }
    }

    private fun connectClassic(device: BluetoothDevice, call: PluginCall) {
        disconnectQuietly(); stopClassicScan()
        worker.execute {
            try {
                val socket = device.createRfcommSocketToServiceRecord(sppUuid)
                adapter?.cancelDiscovery(); socket.connect()
                classicSocket = socket; input = socket.inputStream; output = socket.outputStream; running.set(true)
                notifyListeners("connected", JSObject().put("transport", "classic").put("address", device.address))
                startReader(socket.inputStream)
                call.resolve(JSObject().put("connected", true).put("transport", "classic").put("address", device.address))
            } catch (e: Exception) { running.set(false); call.reject("Classic SPP connection failed: ${e.message}") }
        }
    }

    private fun startReader(stream: InputStream) {
        readThread = Thread {
            val buffer = ByteArray(1024)
            try { while (running.get()) { val n = stream.read(buffer); if (n < 0) break; if (n > 0) publishData(buffer.copyOf(n)) } }
            catch (_: Exception) { }
            finally { if (running.getAndSet(false)) notifyListeners("disconnected", JSObject().put("transport", "classic")) }
        }.apply { name = "VehicleLink-reader"; isDaemon = true; start() }
    }

    private fun publishData(bytes: ByteArray) {
        val text = try { String(bytes, Charsets.UTF_8) } catch (_: Exception) { "" }
        notifyListeners("data", JSObject().put("value", text).put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }

    private fun notifyError(message: String) = notifyListeners("error", JSObject().put("message", message))

    private fun disconnectQuietly() {
        running.set(false)
        try { classicSocket?.close() } catch (_: Exception) {}
        classicSocket = null; input = null; output = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null; writeCharacteristic = null; notifyCharacteristic = null
    }

    override fun handleOnDestroy() {
        stopBleScan(); stopClassicScan(); disconnectQuietly(); worker.shutdownNow(); super.handleOnDestroy()
    }
}
