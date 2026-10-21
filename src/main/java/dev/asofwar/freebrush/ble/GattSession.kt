package dev.asofwar.freebrush.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

class GattException(message: String) : Exception(message)

/** A notification received from the device. */
class Notification(val characteristic: UUID, val value: ByteArray)

/**
 * Coroutine wrapper around [BluetoothGatt].
 *
 * Android allows only one outstanding GATT operation at a time, so every operation takes [opLock]
 * and waits for its callback before the next one starts.
 */
@SuppressLint("MissingPermission") // Callers check BLUETOOTH_CONNECT before creating a session.
class GattSession(private val context: Context, private val device: BluetoothDevice) {

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var pending: CompletableDeferred<Any?>? = null
    private var connected = CompletableDeferred<Unit>()

    @Volatile
    var isConnected = false
        private set

    private val _notifications = MutableSharedFlow<Notification>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifications: SharedFlow<Notification> = _notifications

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection state=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                isConnected = true
                connected.complete(Unit)
            } else {
                isConnected = false
                val error = GattException("Соединение потеряно (status=$status)")
                connected.completeExceptionally(error)
                pending?.completeExceptionally(error)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            complete(status, Unit)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            complete(status, mtu)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            complete(status, value)
        }

        @Deprecated("Used on Android 12")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            complete(status, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            complete(status, Unit)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            complete(status, Unit)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emit(characteristic.uuid, value)
        }

        @Deprecated("Used on Android 12")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            emit(characteristic.uuid, characteristic.value?.copyOf() ?: ByteArray(0))
        }
    }

    private fun emit(uuid: UUID, value: ByteArray) {
        Log.d(TAG, "notify ${uuid.short()} ${value.toHex()}")
        _notifications.tryEmit(Notification(uuid, value))
    }

    private fun complete(status: Int, result: Any?) {
        val op = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) op.complete(result)
        else op.completeExceptionally(GattException("GATT status $status"))
    }

    suspend fun connect(timeoutMs: Long = 15_000) {
        connected = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        withTimeout(timeoutMs) { connected.await() }
    }

    private suspend fun <T> op(name: String, timeoutMs: Long = 5_000, start: (BluetoothGatt) -> Boolean): T =
        opLock.withLock {
            val g = gatt ?: throw GattException("Нет соединения")
            val deferred = CompletableDeferred<Any?>()
            pending = deferred
            try {
                if (!start(g)) throw GattException("$name: операция не запущена")
                @Suppress("UNCHECKED_CAST")
                withTimeout(timeoutMs) { deferred.await() } as T
            } finally {
                pending = null
            }
        }

    suspend fun discoverServices() {
        op<Unit>("discover", 15_000) { it.discoverServices() }
    }

    suspend fun requestMtu(mtu: Int): Int = op("mtu") { it.requestMtu(mtu) }

    fun hasCharacteristic(service: UUID, characteristic: UUID): Boolean =
        gatt?.getService(service)?.getCharacteristic(characteristic) != null

    private fun characteristic(service: UUID, characteristic: UUID): BluetoothGattCharacteristic =
        gatt?.getService(service)?.getCharacteristic(characteristic)
            ?: throw GattException("Нет характеристики ${characteristic.short()}")

    suspend fun read(service: UUID, uuid: UUID): ByteArray {
        val c = characteristic(service, uuid)
        return op("read ${uuid.short()}") { it.readCharacteristic(c) }
    }

    suspend fun write(service: UUID, uuid: UUID, value: ByteArray, withResponse: Boolean = true) {
        val c = characteristic(service, uuid)
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        Log.d(TAG, "write ${uuid.short()} ${value.toHex()}")
        op<Unit>("write ${uuid.short()}") { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    /** Enables notifications; returns false when the characteristic has no CCCD to write. */
    suspend fun enableNotifications(service: UUID, uuid: UUID): Boolean {
        val c = characteristic(service, uuid)
        val g = gatt ?: throw GattException("Нет соединения")
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(CCCD) ?: return false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op<Unit>("cccd ${uuid.short()}") { gg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gg.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gg.writeDescriptor(cccd)
            }
        }
        return true
    }

    fun close() {
        isConnected = false
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
    }

    companion object {
        private const val TAG = "FreeBrushGatt"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

fun UUID.short(): String = toString().substring(0, 8).let { if (it.startsWith("0000")) it.substring(4) else toString().takeLast(5) }

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
