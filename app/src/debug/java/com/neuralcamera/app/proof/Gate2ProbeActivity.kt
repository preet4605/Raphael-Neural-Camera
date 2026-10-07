package com.neuralcamera.app.proof

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Process
import android.system.Os
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.neuralcamera.app.telemetry.AndroidSystemSampler
import com.neuralcamera.runtime.proof.Gate2Suite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only Gate 2 probe. Runs every variant in this process and writes raw evidence JSON to
 * `<external files>/gate2/run_<time>_pid<pid>/`. Each of the three required runs must be a separate process
 * (force-stop the app in between); tools/proof/run_gate2_adb.sh automates that and then runs the independent checker.
 *
 * `--ez autorun true` starts the suite on launch.
 */
class Gate2ProbeActivity : Activity() {

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
            text = "Run Gate 2 suite"
            setOnClickListener { start() }
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
        append("Gate 2 probe. Force-stop the app between the three required runs.")
        if (intent.getBooleanExtra("autorun", false)) start()
    }

    private fun append(line: String) = runOnUiThread { log.append(line + "\n") }

    private fun start() {
        if (running) return
        running = true
        button.isEnabled = false
        Thread({
            try {
                runSuite()
            } catch (t: Throwable) {
                append("PROBE FAILED: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                running = false
                runOnUiThread { button.isEnabled = true }
            }
        }, "gate2-probe").start()
    }

    private fun runSuite() {
        val nativeDir = applicationInfo.nativeLibraryDir
        // The HTP skel libraries are found by the DSP loader via ADSP_LIBRARY_PATH; set it before any QNN session exists.
        Os.setenv("ADSP_LIBRARY_PATH", "$nativeDir;/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/vendor/dsp", true)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outDir = File(getExternalFilesDir(null), "gate2/run_${stamp}_pid${Process.myPid()}")
        append("output: ${outDir.absolutePath}")
        append("nativeLibraryDir: $nativeDir")

        val suite = Gate2Suite(
            sampler = AndroidSystemSampler(this),
            device = ProbeInfo.device(),
            app = ProbeInfo.app(this),
            processStartElapsedRealtimeMs = Process.getStartElapsedRealtime(),
            nativeLibraryDir = nativeDir
        )
        val result = runBlocking(Dispatchers.Default) { suite.run(outDir, log = { append(it) }) }
        append("done: ${result.reportFiles.size} reports; summary ${result.summaryFile.name}")
        append("Verdicts come from: python3 tools/proof/check_gate2.py <pulled dir>")
    }
}
