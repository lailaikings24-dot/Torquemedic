import Capacitor
import CoreBluetooth
import Foundation

@objc(VehicleLinkPlugin)
public class VehicleLinkPlugin: CAPPlugin, CAPBridgedPlugin, CBCentralManagerDelegate, CBPeripheralDelegate {
    public let identifier = "VehicleLinkPlugin"
    public let jsName = "VehicleLink"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "scan", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "stopScan", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "connect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "disconnect", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "write", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "isConnected", returnType: CAPPluginReturnPromise)
    ]

    private var central: CBCentralManager!
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var activePeripheral: CBPeripheral?
    private var writable: CBCharacteristic?
    private var notifiable: CBCharacteristic?
    private var scanCall: CAPPluginCall?
    private var connectCall: CAPPluginCall?
    private var ready = false

    public override func load() {
        central = CBCentralManager(delegate: self, queue: DispatchQueue.main)
    }

    @objc func scan(_ call: CAPPluginCall) {
        guard call.getString("transport", "ble") == "ble" else {
            call.reject("iOS supports BLE only; Bluetooth Classic SPP is not available to third-party apps.")
            return
        }
        guard central.state == .poweredOn else { call.reject("Bluetooth is not powered on"); return }
        scanCall = call
        central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        call.resolve(["scanning": true, "transport": "ble"])
    }

    @objc func stopScan(_ call: CAPPluginCall) {
        central.stopScan(); scanCall = nil; call.resolve()
    }

    @objc func connect(_ call: CAPPluginCall) {
        guard call.getString("transport", "ble") == "ble" else {
            call.reject("iOS supports BLE only; Bluetooth Classic SPP is not available to third-party apps.")
            return
        }
        guard central.state == .poweredOn else { call.reject("Bluetooth is not powered on"); return }
        guard let raw = call.getString("address"), let id = UUID(uuidString: raw) else {
            call.reject("address must be the peripheral UUID returned by scan")
            return
        }
        let candidates = central.retrievePeripherals(withIdentifiers: [id])
        guard let peripheral = peripherals[id] ?? candidates.first else { call.reject("Peripheral not found; scan for it first"); return }
        if let old = activePeripheral { central.cancelPeripheralConnection(old) }
        connectCall = call
        activePeripheral = peripheral
        peripheral.delegate = self
        central.connect(peripheral, options: nil)
    }

    @objc func disconnect(_ call: CAPPluginCall) {
        if let peripheral = activePeripheral { central.cancelPeripheralConnection(peripheral) }
        activePeripheral = nil; writable = nil; notifiable = nil; ready = false
        notifyListeners("disconnected", data: [:])
        call.resolve()
    }

    @objc func write(_ call: CAPPluginCall) {
        guard ready, let peripheral = activePeripheral, let characteristic = writable else {
            call.reject("No writable BLE connection")
            return
        }
        guard let value = call.getString("value"), let bytes = value.data(using: .utf8) else {
            call.reject("value is required")
            return
        }
        let type: CBCharacteristicWriteType = characteristic.properties.contains(.write) ? .withResponse : .withoutResponse
        peripheral.writeValue(bytes, for: characteristic, type: type)
        call.resolve()
    }

    @objc func isConnected(_ call: CAPPluginCall) { call.resolve(["connected": ready]) }

    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn {
            notifyListeners("error", data: ["message": "Bluetooth is unavailable (state \(central.state.rawValue))"])
        }
    }

    public func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                               advertisementData: [String: Any], rssi RSSI: NSNumber) {
        peripherals[peripheral.identifier] = peripheral
        notifyListeners("deviceFound", data: [
            "address": peripheral.identifier.uuidString,
            "name": peripheral.name ?? advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? "",
            "transport": "ble",
            "rssi": RSSI.intValue
        ])
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices(nil)
        notifyListeners("connected", data: ["transport": "ble", "address": peripheral.identifier.uuidString])
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectCall?.reject("BLE connection failed: \(error?.localizedDescription ?? "unknown error")")
        connectCall = nil
        notifyListeners("error", data: ["message": error?.localizedDescription ?? "BLE connection failed"])
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if activePeripheral?.identifier == peripheral.identifier {
            activePeripheral = nil; writable = nil; notifiable = nil; ready = false
        }
        notifyListeners("disconnected", data: ["transport": "ble"])
        if let error { notifyListeners("error", data: ["message": error.localizedDescription]) }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error { notifyError(error.localizedDescription); return }
        peripheral.services?.forEach { peripheral.discoverCharacteristics(nil, for: $0) }
    }

    public func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let error { notifyError(error.localizedDescription); return }
        for characteristic in service.characteristics ?? [] {
            if writable == nil && (characteristic.properties.contains(.write) || characteristic.properties.contains(.writeWithoutResponse)) { writable = characteristic }
            if notifiable == nil && (characteristic.properties.contains(.notify) || characteristic.properties.contains(.indicate)) {
                notifiable = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
            }
        }
        if writable != nil {
            ready = true
            connectCall?.resolve(["connected": true, "transport": "ble", "address": peripheral.identifier.uuidString])
            connectCall = nil
        }
    }

    public func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { notifyError(error.localizedDescription); return }
        guard let data = characteristic.value else { return }
        notifyListeners("data", data: ["value": String(data: data, encoding: .utf8) ?? "", "base64": data.base64EncodedString()])
    }

    public func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error { notifyError("BLE write failed: \(error.localizedDescription)") }
    }

    private func notifyError(_ message: String) { notifyListeners("error", data: ["message": message]) }
}
