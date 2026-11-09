# VehicleLink native Capacitor plugin

This folder contains native BLE / Bluetooth Classic transport code for TorqueMedic. Android supports BLE GATT and Classic SPP; iOS supports BLE through CoreBluetooth. This is native source, not a packaged npm plugin, and the repository currently has no Capacitor app project files, so it must be integrated into an existing Capacitor Android/iOS project before it can build.

## Android integration

1. Copy `android/VehicleLinkPlugin.kt` into the Android app module, retaining package `com.torquemedic.vehiclelink` (or update the package to match your app).
2. Merge `AndroidManifest.additions.xml` permission and feature entries into `android/app/src/main/AndroidManifest.xml`.
3. Register the plugin in `MainActivity`: `registerPlugin(VehicleLinkPlugin::class.java)` (import the class first).
4. Use a recent Capacitor Android version and Android SDK 33+; request Bluetooth permissions at runtime. The plugin requests its declared `bluetooth` alias on Android 12+ and `location` on older Android versions.

## iOS integration

1. Add `ios/VehicleLinkPlugin.swift` to the iOS app target and ensure the plugin class is included in the target.
2. Register it in the Capacitor bridge, for example in `BridgeViewController` with `bridge?.registerPluginInstance(VehicleLinkPlugin())` if your Capacitor version uses manual registration.
3. Merge `Info.plist.additions.xml` keys into the app's existing `Info.plist`.
4. iOS supports BLE only; Apple does not expose generic Bluetooth Classic SPP to ordinary third-party apps. Connect to BLE OBD-II adapters.

## JavaScript API

The native bridge is exposed as `VehicleLink` and provides promise methods `scan({ transport?: 'ble' | 'classic' })`, `stopScan()`, `connect({ address, transport?: 'ble' | 'classic' })`, `disconnect()`, `write({ value })`, and `isConnected()`. Listen for `deviceFound`, `connected`, `disconnected`, `data`, `error`, and `scanComplete` events using Capacitor's `addListener` API. Scan results include `address`, `name`, `transport`, and `rssi`; received data includes UTF-8 `value` and base64 `base64`.

On iOS, `address` is the peripheral UUID reported by the scan event, not a MAC address. The plugin selects the first characteristic that supports writing and the first that supports notify/indicate; adapters with vendor-specific service requirements may need service/characteristic UUID filtering. `write` sends UTF-8 text, so OBD command framing and response parsing belong in the app layer.

## Important limitations

- This plugin provides Bluetooth transport only; it does not implement OBD-II command sequencing, PID decoding, DTC interpretation, or vehicle compatibility guarantees.
- BLE adapters vary. Some require a particular service/characteristic UUID, write mode, or notification setup. Verify against the adapter documentation and test with the actual vehicle.
- Android Bluetooth Classic discovery can find unpaired adapters, but pairing may be required by some devices. Android 12+ system permission prompts and device settings are user-controlled.
- Validate merged manifest and plist entries against the target Capacitor/Xcode/Android Gradle versions before release.
