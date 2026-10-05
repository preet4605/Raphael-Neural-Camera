package com.neuralcamera.app.proof

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.neuralcamera.cameracore.proof.Gate1
import com.neuralcamera.cameracore.proof.RawBurstConfig
import com.neuralcamera.cameracore.proof.RawBurstRecorder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only Gate 1 probe: captures an 8-frame full-resolution RAW_SENSOR burst as an ordinary app and writes
 * gate1_report.json, frames.csv and one DNG per frame to `<external files>/gate1/run_<time>_pid<pid>/`.
 * Each of the three required runs must be a separate process; tools/proof/run_gate1_adb.sh automates that and then runs
 * the independent checker (tools/proof/check_gate1.py). Extras: `--ez autorun true`, `--es camera <id>`, `--ei maxImages <n>`.
 * A run writes about 800 MB of DNG files.
 */
class Gate1ProbeActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var button: Button
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        button = Button(this).apply {
            text = "Run Gate 1 RAW burst"
            setOnClickListener { requestAndStart() }
        }
        log = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
        }
        root.addView(button)
        root.addView(
            ScrollView(this).apply { addView(log) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
        append("Gate 1 probe. Force-stop the app between the three required runs. Each run writes ~800 MB.")
        if (intent.getBooleanExtra("autorun", false)) requestAndStart()
    }

    private fun append(line: String) = runOnUiThread { log.append(line + "\n") }

    private fun requestAndStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        } else {
            start()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) start()
        else append("CAMERA permission denied; cannot run.")
    }

    private fun start() {
        if (running) return
        running = true
        runOnUiThread { button.isEnabled = false }
        Thread({
            try {
                runBurst()
            } catch (t: Throwable) {
                append("PROBE FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running = false
                runOnUiThread { button.isEnabled = true }
            }
        }, "gate1-probe").start()
    }

    private fun runBurst() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "gate1/run_${stamp}_pid${Process.myPid()}")
        append("output: ${outDir.absolutePath}")
        val evidence = RawBurstRecorder(this)
            .record(
                RawBurstConfig(
                    cameraId = intent.getStringExtra("camera"),
                    outputDir = outDir,
                    maxImages = intent.getIntExtra("maxImages", Gate1.MIN_FRAMES)
                ),
                Process.getStartElapsedRealtime()
            ) { append(it) }
            .copy(device = ProbeInfo.device())
        Gate1.write(evidence, outDir)
        val criteria = Gate1.evaluate(evidence)
        criteria.forEach { append("[${if (it.passed) "ok" else "!!"}] ${it.name}: ${it.detail.take(100)}") }
        append("app-side evaluation all passed: ${criteria.all { it.passed }} (informational)")
        append("Verdicts come from: python3 tools/proof/check_gate1.py <pulled dir>")
    }

    private companion object {
        const val REQUEST_CAMERA = 1
    }
}
