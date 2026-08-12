package com.example.meshsocial.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Runtime permission requirements for BLE scan + advertise.
 *
 * Android 12+ (S): nearby-devices permissions BLUETOOTH_SCAN / BLUETOOTH_ADVERTISE /
 * BLUETOOTH_CONNECT. The manifest declares BLUETOOTH_SCAN with neverForLocation,
 * so no location permission is needed on those versions.
 * Android 11 and lower: fine location is required to resolve scan results.
 */
object BlePermissions {
    fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missing(context: Context): List<String> =
        requiredPermissions().filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
}
