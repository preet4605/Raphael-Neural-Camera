package com.neuralcamera.deviceprofiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic runtime reports (test fixtures, not device data). */
class ProfileVerifierTest {
    private val profile = PredefinedDeviceProfiles.ONEPLUS_15

    private fun discoveredFrom(p: DeviceProfile, mutate: (String, DiscoveredCameraProfile) -> DiscoveredCameraProfile = { _, d -> d }) =
        RuntimeDeviceVerification(
            timestamp = 0, deviceModel = p.deviceModel, manufacturer = p.manufacturer, brand = "", product = "",
            androidRelease = "16", sdkInt = p.osVersionSdk,
            cameras = p.cameras.mapValues { (id, c) ->
                mutate(id, DiscoveredCameraProfile(
                    cameraId = id, hardwareLevel = "LEVEL_3", lensFacing = c.facing, sensorOrientation = 90,
                    isLogical = c.capabilities.isLogical, physicalCameraIds = c.capabilities.physicalCameraIds,
                    sensor = SensorProfile(c.sensorActiveArraySize.first, c.sensorActiveArraySize.second, 0, 0, 0f, 0f,
                        c.isoRange, c.exposureTimeRangeNs, 0L),
                    lens = LensProfile(emptyList(), emptyList(), 0f),
                    threeA = ThreeAProfile(emptyList(), emptyList(), emptyList(), 0..0, 0f, true, true),
                    streams = StreamMatrixProfile(if (c.hasRawSupport) setOf("RAW_SENSOR", "YUV_420_888") else setOf("YUV_420_888"), emptyMap()),
                    android16 = Android16Profile()
                ))
            },
            logicalPhysicalMappings = emptyMap(), streamMatrixResults = emptyList()
        )

    @Test
    fun noRuntimeDataLeavesEveryFieldNotChecked() {
        val r = ProfileVerifier.verify(profile, null)
        assertTrue(r.checks.all { it.verdict == FieldVerdict.NOT_CHECKED })
        assertFalse(r.fullyMatches)
    }

    @Test
    fun agreeingMetadataMatchesEveryField() {
        val r = ProfileVerifier.verify(profile, discoveredFrom(profile))
        assertTrue(r.summary(), r.fullyMatches)
    }

    @Test
    fun differentActiveArrayAndMissingRawAreContradicted() {
        val d = discoveredFrom(profile) { id, c ->
            if (id != "0") c else c.copy(
                sensor = c.sensor.copy(activeArrayWidth = 4096, activeArrayHeight = 3072),
                streams = c.streams.copy(supportedFormats = setOf("YUV_420_888"))
            )
        }
        val r = ProfileVerifier.verify(profile, d)
        assertEquals(setOf("camera[0].activeArray", "camera[0].rawFormatListed"), r.contradicted.map { it.field }.toSet())
        assertEquals("4096x3072", r.contradicted.first { it.field == "camera[0].activeArray" }.discovered)
    }

    @Test
    fun missingAndUndeclaredCamerasAreContradicted() {
        val base = discoveredFrom(profile)
        val extra = base.cameras.getValue("0").copy(cameraId = "7")
        val d = base.copy(cameras = base.cameras - "1" + ("7" to extra))
        val fields = ProfileVerifier.verify(profile, d).contradicted.map { it.field }
        assertTrue(fields.contains("camera[1].present"))
        assertTrue(fields.contains("camera[7].present"))
    }

    @Test
    fun keysTheDeviceDidNotReportAreNotChecked() {
        val d = discoveredFrom(profile) { id, c -> if (id == "0") c.copy(defaultedKeys = setOf("SENSOR_INFO_SENSITIVITY_RANGE")) else c }
        val r = ProfileVerifier.verify(profile, d)
        assertEquals(FieldVerdict.NOT_CHECKED, r.checks.first { it.field == "camera[0].isoRange" }.verdict)
        assertFalse(r.fullyMatches)
        assertTrue(ProfileVerifier.toTsv(r).lines().first().startsWith("field\tdeclared"))
    }
}
