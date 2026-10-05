package com.neuralcamera.deviceprofiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeCapabilityModelsTest {

    @Test
    fun testCapabilityStatusTransitions() {
        val record = CapabilityRecord(
            name = "RAW_SENSOR",
            value = true,
            status = CapabilityStatus.DISCOVERED,
            sourceApi = "StreamConfigurationMap.outputFormats",
            tested = true,
            verified = false
        )

        assertEquals("RAW_SENSOR", record.name)
        assertEquals(CapabilityStatus.DISCOVERED, record.status)
        assertTrue(record.tested)
    }

    @Test
    fun testRuntimeProfileExporterGeneratesExpectedArtifacts() {
        val tempDir = File.createTempFile("profile_test", "_dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val dummyVerification = RuntimeDeviceVerification(
                timestamp = 1727222400000L,
                deviceModel = "CPH2745",
                manufacturer = "OnePlus",
                brand = "OnePlus",
                product = "CPH2745IN",
                androidRelease = "16",
                sdkInt = 36,
                cameras = mapOf(
                    "0" to DiscoveredCameraProfile(
                        cameraId = "0",
                        hardwareLevel = "LEVEL_3",
                        lensFacing = LensFacing.BACK_WIDE,
                        sensorOrientation = 90,
                        isLogical = true,
                        physicalCameraIds = listOf("2", "3"),
                        sensor = SensorProfile(
                            activeArrayWidth = 4000,
                            activeArrayHeight = 3000,
                            pixelArrayWidth = 4000,
                            pixelArrayHeight = 3000,
                            physicalWidthMm = 6.4f,
                            physicalHeightMm = 4.8f,
                            isoRange = 100..3200,
                            exposureTimeRangeNs = 10000L..1000000000L,
                            maxFrameDurationNs = 33333333L
                        ),
                        lens = LensProfile(
                            focalLengthsMm = listOf(5.59f),
                            apertures = listOf(1.6f),
                            minimumFocusDistanceMeters = 0.1f,
                            oisModes = listOf("ON", "OFF")
                        ),
                        threeA = ThreeAProfile(
                            aeModes = listOf("AE_MODE_ON"),
                            afModes = listOf("AF_MODE_CONTINUOUS_PICTURE"),
                            awbModes = listOf("AWB_MODE_AUTO"),
                            exposureCompensationRange = -6..6,
                            exposureCompensationStep = 0.333f,
                            aeLockSupported = true,
                            awbLockSupported = true
                        ),
                        streams = StreamMatrixProfile(
                            supportedFormats = setOf("RAW_SENSOR", "JPEG", "YUV_420_888"),
                            outputSizesByFormat = mapOf(
                                "JPEG" to listOf(Pair(4000, 3000), Pair(1920, 1080))
                            )
                        ),
                        android16 = Android16Profile(
                            hybridAeModesSupported = listOf("ISO_PRIORITY"),
                            nightModeIndicatorSupported = true,
                            ultraHdrSupported = true
                        )
                    )
                ),
                logicalPhysicalMappings = emptyMap(),
                streamMatrixResults = listOf(
                    StreamTestResult(
                        combinationName = "Preview + JPEG",
                        streams = listOf("PREVIEW", "JPEG"),
                        state = StreamTestResultState.SUPPORTED,
                        latencyMs = 12L
                    )
                ),
                zeroCopyDesignPass = true,
                zeroCopyHardwareState = "PASS",
                notes = "Hardware validation complete"
            )

            RuntimeProfileExporter.exportVerificationBundle(tempDir, dummyVerification)

            assertTrue(File(tempDir, "device_profile.json").exists())
            assertTrue(File(tempDir, "camera_profile.json").exists())
            assertTrue(File(tempDir, "stream_profile.json").exists())
            assertTrue(File(tempDir, "dynamic_range_profile.json").exists())
            assertTrue(File(tempDir, "sensor_profile.json").exists())
            assertTrue(File(tempDir, "verification.json").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
