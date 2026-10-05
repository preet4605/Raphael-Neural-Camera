package com.neuralcamera.app.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.neuralcamera.runtime.proof.SystemSample
import com.neuralcamera.runtime.proof.SystemSampler

/**
 * Samples what an unprivileged app can read: PowerManager thermal status/headroom, battery temperature, process CPU
 * time and memory. Anything unavailable on the device stays null; nothing is estimated.
 */
class AndroidSystemSampler(context: Context) : SystemSampler {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    override fun sample(iteration: Int): SystemSample {
        val memoryInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val systemMemory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val runtime = Runtime.getRuntime()
        val headroom = try {
            power.getThermalHeadroom(HEADROOM_FORECAST_SECONDS).takeIf { !it.isNaN() }
        } catch (e: Exception) {
            null
        }
        return SystemSample(
            iteration = iteration,
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
            thermalStatus = try {
                power.currentThermalStatus
            } catch (e: Exception) {
                null
            },
            thermalHeadroom = headroom,
            batteryTempC = batteryTemperatureC(),
            processCpuTimeMs = Process.getElapsedCpuTime(),
            pssKb = memoryInfo.totalPss.toLong(),
            nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
            javaHeapKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024,
            availMemKb = systemMemory.availMem / 1024,
            lowMemory = systemMemory.lowMemory
        )
    }

    private fun batteryTemperatureC(): Float? {
        val intent: Intent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10f
    }

    private companion object {
        const val HEADROOM_FORECAST_SECONDS = 10
    }
}
