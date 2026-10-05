package com.neuralcamera.app.proof

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/** Real device and app properties recorded in every proof report. */
internal object ProbeInfo {
    fun device(): Map<String, String> = linkedMapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "product" to Build.PRODUCT,
        "board" to Build.BOARD,
        "hardware" to Build.HARDWARE,
        "fingerprint" to Build.FINGERPRINT,
        "sdkInt" to Build.VERSION.SDK_INT.toString(),
        "release" to Build.VERSION.RELEASE,
        "securityPatch" to Build.VERSION.SECURITY_PATCH,
        "supportedAbis" to Build.SUPPORTED_ABIS.joinToString(),
        "socModel" to (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "n/a"),
        "socManufacturer" to (if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "n/a")
    )

    fun app(context: Context): Map<String, String> = linkedMapOf(
        "packageName" to context.packageName,
        "versionName" to (context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "n/a"),
        "debuggable" to ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0).toString()
    )
}
