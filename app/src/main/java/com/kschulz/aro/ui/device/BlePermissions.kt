package com.kschulz.aro.ui.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime BLE permissions required across supported Android versions (API 26..34+).
 * Android 12+ (API 31+) requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT; earlier versions
 * require ACCESS_FINE_LOCATION for BLE scanning.
 */
object BlePermissions {

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    fun hasAllPermissions(context: Context): Boolean =
        requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
}
