package com.neuralcamera.deviceprofiles

/**
 * Per-field comparison of a declared (committed) device profile against what the device itself reported at runtime.
 *
 * MATCHES_METADATA means the device's own Camera2/Build metadata agrees with the declared value. It is metadata
 * agreement only: a listed RAW format is not a captured RAW burst (Gate 1) and a listed stream is not a working
 * session. CONTRADICTED means the device reported something else; the declared value must not be relied on.
 */
enum class FieldVerdict { MATCHES_METADATA, CONTRADICTED, NOT_CHECKED }

data class FieldCheck(val field: String, val declared: String, val discovered: String?, val verdict: FieldVerdict)

data class ProfileVerificationReport(val profileId: String, val checks: List<FieldCheck>) {
    val contradicted: List<FieldCheck> get() = checks.filter { it.verdict == FieldVerdict.CONTRADICTED }
    val notChecked: List<FieldCheck> get() = checks.filter { it.verdict == FieldVerdict.NOT_CHECKED }

    /** True only when every declared field was checked and none contradicted. */
    val fullyMatches: Boolean get() = checks.isNotEmpty() && contradicted.isEmpty() && notChecked.isEmpty()

    fun summary(): String =
        "$profileId: ${checks.size - contradicted.size - notChecked.size} match, ${contradicted.size} contradicted, ${notChecked.size} not checked"
}

object ProfileVerifier {

    /** With no runtime data every field is NOT_CHECKED: the committed profile stays unverified. */
    fun verify(profile: DeviceProfile, discovered: RuntimeDeviceVerification?): ProfileVerificationReport {
        val checks = ArrayList<FieldCheck>()
        fun check(field: String, declared: Any, actual: Any?, defaulted: Boolean = false) {
            val verdict = when {
                actual == null || defaulted -> FieldVerdict.NOT_CHECKED
                actual.toString() == declared.toString() -> FieldVerdict.MATCHES_METADATA
                else -> FieldVerdict.CONTRADICTED
            }
            checks += FieldCheck(field, declared.toString(), actual?.toString(), verdict)
        }

        check("sdkInt", profile.osVersionSdk, discovered?.sdkInt)
        for ((id, cam) in profile.cameras.toSortedMap()) {
            val d = discovered?.cameras?.get(id)
            val p = "camera[$id]"
            if (discovered != null && d == null) {
                checks += FieldCheck("$p.present", "true", "false", FieldVerdict.CONTRADICTED)
                continue
            }
            val defaulted = d?.defaultedKeys.orEmpty()
            // Camera2 reports only front/back/external; the wide/ultrawide/tele split is not in LENS_FACING.
            check("$p.facingSide", side(cam.facing), d?.lensFacing?.let(::side), "LENS_FACING" in defaulted)
            check("$p.activeArray", "${cam.sensorActiveArraySize.first}x${cam.sensorActiveArraySize.second}",
                d?.let { "${it.sensor.activeArrayWidth}x${it.sensor.activeArrayHeight}" }, "SENSOR_INFO_ACTIVE_ARRAY_SIZE" in defaulted)
            check("$p.isoRange", "${cam.isoRange.start}..${cam.isoRange.endInclusive}",
                d?.let { "${it.sensor.isoRange.start}..${it.sensor.isoRange.endInclusive}" }, "SENSOR_INFO_SENSITIVITY_RANGE" in defaulted)
            check("$p.exposureTimeRangeNs", "${cam.exposureTimeRangeNs.start}..${cam.exposureTimeRangeNs.endInclusive}",
                d?.let { "${it.sensor.exposureTimeRangeNs.start}..${it.sensor.exposureTimeRangeNs.endInclusive}" }, "SENSOR_INFO_EXPOSURE_TIME_RANGE" in defaulted)
            check("$p.rawFormatListed", cam.hasRawSupport, d?.let { "RAW_SENSOR" in it.streams.supportedFormats })
            check("$p.physicalCameraIds", cam.capabilities.physicalCameraIds.sorted(), d?.physicalCameraIds?.sorted())
        }
        discovered?.cameras?.keys?.filter { it !in profile.cameras }?.sorted()?.forEach { id ->
            checks += FieldCheck("camera[$id].present", "false", "true", FieldVerdict.CONTRADICTED)
        }
        return ProfileVerificationReport(profile.profileId, checks)
    }

    private fun side(f: LensFacing) = if (f == LensFacing.FRONT) "FRONT" else "BACK"

    /** Tab-separated report for committing next to the raw runtime export. */
    fun toTsv(report: ProfileVerificationReport): String = buildString {
        appendLine("field\tdeclared\tdiscovered\tverdict")
        for (c in report.checks) appendLine("${c.field}\t${c.declared}\t${c.discovered ?: ""}\t${c.verdict}")
    }
}
