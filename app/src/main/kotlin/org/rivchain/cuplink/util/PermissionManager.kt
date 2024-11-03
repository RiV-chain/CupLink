package org.rivchain.cuplink.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionManager {
    @JvmStatic
    fun havePostNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) ==
                PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun haveCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) ==
                PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun haveMicrophonePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) ==
                PackageManager.PERMISSION_GRANTED
    }

    @JvmStatic
    fun haveDrawOverlaysPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            !Settings.canDrawOverlays(context)
        } else {
            true
        }
    }
}