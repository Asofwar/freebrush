package dev.asofwar.freebrush.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(val address: String, val name: String?, val rssi: Int) {
    val looksLikeOclean: Boolean get() = name?.contains("oclean", ignoreCase = true) == true
}

/** Emits BLE devices around; the brush advertises only while it is awake (pick it up or press the button). */
@SuppressLint("MissingPermission") // The UI asks for BLUETOOTH_SCAN before scanning.
fun scanDevices(context: Context): Flow<FoundDevice> = callbackFlow {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    val scanner = adapter?.bluetoothLeScanner
    if (adapter == null || !adapter.isEnabled || scanner == null) {
        close(GattException("Bluetooth выключен"))
        return@callbackFlow
    }
    val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            trySend(FoundDevice(result.device.address, name, result.rssi))
        }

        override fun onScanFailed(errorCode: Int) {
            close(GattException("Ошибка сканирования $errorCode"))
        }
    }
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    scanner.startScan(null, settings, callback)
    awaitClose { runCatching { scanner.stopScan(callback) } }
}
