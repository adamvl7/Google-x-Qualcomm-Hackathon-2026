package com.fitform.app.util

import android.os.Build
import android.util.Log

object DeviceInfo {
    private const val TAG = "PerformanceLab"

    fun isProbablyEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val product = Build.PRODUCT.orEmpty()

        return fingerprint.contains("generic", ignoreCase = true) ||
            fingerprint.contains("unknown", ignoreCase = true) ||
            fingerprint.contains("vbox", ignoreCase = true) ||
            fingerprint.contains("test-keys", ignoreCase = true) ||
            model.contains("google_sdk", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK built for", ignoreCase = true) ||
            manufacturer.contains("Genymotion", ignoreCase = true) ||
            hardware.contains("goldfish", ignoreCase = true) ||
            hardware.contains("ranchu", ignoreCase = true) ||
            hardware.contains("vbox86", ignoreCase = true) ||
            product.contains("sdk", ignoreCase = true) ||
            product.contains("emulator", ignoreCase = true) ||
            product.contains("simulator", ignoreCase = true)
    }

    fun isGalaxyS25Ultra(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return manufacturer.equals("samsung", ignoreCase = true) &&
            (model.contains("S25 Ultra", ignoreCase = true) || model.contains("S938", ignoreCase = true))
    }

    fun deviceChipLabel(): String = when {
        isProbablyEmulator() -> "Emulator · UI test mode"
        isGalaxyS25Ultra() -> "Physical device · Snapdragon 8 Elite"
        else -> "Physical device · ${Build.MODEL}"
    }

    fun logDeviceInfo() {
        Log.i(
            TAG,
            "isEmulator=${isProbablyEmulator()}, model=${Build.MODEL}, " +
                "manufacturer=${Build.MANUFACTURER}, hardware=${Build.HARDWARE}, " +
                "product=${Build.PRODUCT}",
        )
    }
}
