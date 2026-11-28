package com.musicplayer.app.player

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.musicplayer.app.report.ErrorReporter

/**
 * v1.0.16 — car mode receiver (feature 9): listens for A2DP
 * (Bluetooth audio) connection changes. When a CAR-AUDIO device
 * connects, car mode turns on; when it disconnects, car mode turns
 * off. Headphones, speakers and other non-car audio devices do NOT
 * trigger it.
 *
 * Note: reading the device class needs BLUETOOTH_CONNECT on Android
 * 12+ (requested at first launch with the other permissions); on
 * older Android the BLUETOOTH permission is install-time. When the
 * device cannot be read (no permission), nothing happens — car mode
 * never guesses.
 */
class CarModeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return
        val state = intent.getIntExtra(
            BluetoothA2dp.EXTRA_STATE, BluetoothA2dp.STATE_DISCONNECTED
        )
        try {
            val device = intent.getParcelableExtra<BluetoothDevice>(
                BluetoothDevice.EXTRA_DEVICE
            )
            val deviceClass = try {
                device?.bluetoothClass?.deviceClass
            } catch (e: SecurityException) {
                null
            }
            val isCar = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
            when (state) {
                BluetoothA2dp.STATE_CONNECTED -> {
                    if (isCar) CarMode.setActive(context, true)
                }
                BluetoothA2dp.STATE_DISCONNECTED -> {
                    // Only switch off when the disconnected device was
                    // the car (or the device is unknown and car mode is
                    // on — a disconnect with no car class means the car
                    // left, since only cars ever turned it on).
                    if (isCar || device == null) CarMode.setActive(context, false)
                }
            }
        } catch (e: Exception) {
            ErrorReporter.log(
                ErrorReporter.TYPE_ERROR,
                "Car mode receiver failed: ${e.message}"
            )
        }
    }
}
