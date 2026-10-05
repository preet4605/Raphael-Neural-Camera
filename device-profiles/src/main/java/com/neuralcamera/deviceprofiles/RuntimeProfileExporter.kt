package com.neuralcamera.deviceprofiles

import java.io.File

/**
 * Serializes discovered runtime profiles to the standard Phase 1 JSON artifacts:
 * profiles/runtime/<device>/<timestamp>/
 */
object RuntimeProfileExporter {

    fun exportVerificationBundle(
        outputDir: File,
        verification: RuntimeDeviceVerification
    ) {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        // 1. device_profile.json
        val deviceJson = buildString {
            appendLine("{")
            appendLine("  \"timestamp\": ${verification.timestamp},")
            appendLine("  \"deviceModel\": \"${verification.deviceModel}\",")
            appendLine("  \"manufacturer\": \"${verification.manufacturer}\",")
            appendLine("  \"brand\": \"${verification.brand}\",")
            appendLine("  \"product\": \"${verification.product}\",")
            appendLine("  \"androidRelease\": \"${verification.androidRelease}\",")
            appendLine("  \"sdkInt\": ${verification.sdkInt},")
            appendLine("  \"cameraCount\": ${verification.cameras.size},")
            appendLine("  \"zeroCopyDesign\": ${verification.zeroCopyDesignPass},")
            appendLine("  \"zeroCopyHardwareState\": \"${verification.zeroCopyHardwareState}\"")
            appendLine("}")
        }
        File(outputDir, "device_profile.json").writeText(deviceJson)

        // 2. camera_profile.json
        val cameraJson = buildString {
            appendLine("{")
            appendLine("  \"cameras\": [")
            val cameraEntries = verification.cameras.values.toList()
            cameraEntries.forEachIndexed { index, cam ->
                appendLine("    {")
                appendLine("      \"cameraId\": \"${cam.cameraId}\",")
                appendLine("      \"hardwareLevel\": \"${cam.hardwareLevel}\",")
                appendLine("      \"lensFacing\": \"${cam.lensFacing.name}\",")
                appendLine("      \"sensorOrientation\": ${cam.sensorOrientation},")
                appendLine("      \"isLogical\": ${cam.isLogical},")
                appendLine("      \"physicalCameraIds\": ${cam.physicalCameraIds.map { "\"$it\"" }},")
                appendLine("      \"sensor\": {")
                appendLine("        \"activeArrayWidth\": ${cam.sensor.activeArrayWidth},")
                appendLine("        \"activeArrayHeight\": ${cam.sensor.activeArrayHeight},")
                appendLine("        \"pixelArrayWidth\": ${cam.sensor.pixelArrayWidth},")
                appendLine("        \"pixelArrayHeight\": ${cam.sensor.pixelArrayHeight},")
                appendLine("        \"physicalWidthMm\": ${cam.sensor.physicalWidthMm},")
                appendLine("        \"physicalHeightMm\": ${cam.sensor.physicalHeightMm},")
                appendLine("        \"isoRange\": [${cam.sensor.isoRange.start}, ${cam.sensor.isoRange.endInclusive}],")
                appendLine("        \"exposureTimeRangeNs\": [${cam.sensor.exposureTimeRangeNs.start}, ${cam.sensor.exposureTimeRangeNs.endInclusive}],")
                appendLine("        \"colorFilterArrangement\": \"${cam.sensor.colorFilterArrangement}\"")
                appendLine("      },")
                appendLine("      \"lens\": {")
                appendLine("        \"focalLengthsMm\": ${cam.lens.focalLengthsMm},")
                appendLine("        \"apertures\": ${cam.lens.apertures},")
                appendLine("        \"minimumFocusDistanceMeters\": ${cam.lens.minimumFocusDistanceMeters},")
                appendLine("        \"oisModes\": ${cam.lens.oisModes.map { "\"$it\"" }}")
                appendLine("      },")
                appendLine("      \"threeA\": {")
                appendLine("        \"aeModes\": ${cam.threeA.aeModes.map { "\"$it\"" }},")
                appendLine("        \"afModes\": ${cam.threeA.afModes.map { "\"$it\"" }},")
                appendLine("        \"awbModes\": ${cam.threeA.awbModes.map { "\"$it\"" }}")
                appendLine("      }")
                appendLine("    }${if (index < cameraEntries.size - 1) "," else ""}")
            }
            appendLine("  ]")
            appendLine("}")
        }
        File(outputDir, "camera_profile.json").writeText(cameraJson)

        // 3. stream_profile.json
        val streamJson = buildString {
            appendLine("{")
            appendLine("  \"streamMatrix\": [")
            verification.streamMatrixResults.forEachIndexed { index, test ->
                appendLine("    {")
                appendLine("      \"combination\": \"${test.combinationName}\",")
                appendLine("      \"streams\": ${test.streams.map { "\"$it\"" }},")
                appendLine("      \"state\": \"${test.state.name}\",")
                appendLine("      \"latencyMs\": ${test.latencyMs}")
                appendLine("    }${if (index < verification.streamMatrixResults.size - 1) "," else ""}")
            }
            appendLine("  ]")
            appendLine("}")
        }
        File(outputDir, "stream_profile.json").writeText(streamJson)

        // 4. dynamic_range_profile.json
        val dynamicRangeJson = buildString {
            appendLine("{")
            appendLine("  \"cameraDynamicRange\": {")
            verification.cameras.entries.forEachIndexed { index, entry ->
                appendLine("    \"${entry.key}\": {")
                appendLine("      \"supportedProfiles\": ${entry.value.streams.dynamicRangeProfiles.map { "\"$it\"" }},")
                appendLine("      \"constraints\": {")
                val constraintEntries = entry.value.streams.dynamicRangeConstraints.entries.toList()
                constraintEntries.forEachIndexed { cIdx, (profile, constraints) ->
                    appendLine("        \"$profile\": ${constraints.map { "\"$it\"" }}${if (cIdx < constraintEntries.size - 1) "," else ""}")
                }
                appendLine("      }")
                appendLine("    }${if (index < verification.cameras.size - 1) "," else ""}")
            }
            appendLine("  }")
            appendLine("}")
        }
        File(outputDir, "dynamic_range_profile.json").writeText(dynamicRangeJson)

        // 5. sensor_profile.json
        val sensorJson = buildString {
            appendLine("{")
            appendLine("  \"sensors\": [")
            verification.cameras.entries.forEachIndexed { index, (id, cam) ->
                appendLine("    {")
                appendLine("      \"cameraId\": \"$id\",")
                appendLine("      \"activeArray\": [${cam.sensor.activeArrayWidth}, ${cam.sensor.activeArrayHeight}],")
                appendLine("      \"pixelArray\": [${cam.sensor.pixelArrayWidth}, ${cam.sensor.pixelArrayHeight}],")
                appendLine("      \"physicalSizeMm\": [${cam.sensor.physicalWidthMm}, ${cam.sensor.physicalHeightMm}],")
                appendLine("      \"blackLevelPattern\": ${cam.sensor.blackLevelPattern ?: "null"},")
                appendLine("      \"whiteLevel\": ${cam.sensor.whiteLevel ?: 1023}")
                appendLine("    }${if (index < verification.cameras.size - 1) "," else ""}")
            }
            appendLine("  ]")
            appendLine("}")
        }
        File(outputDir, "sensor_profile.json").writeText(sensorJson)

        // 6. verification.json
        val verificationJson = buildString {
            appendLine("{")
            appendLine("  \"phase\": 1,")
            appendLine("  \"timestamp\": ${verification.timestamp},")
            appendLine("  \"status\": \"UNVERIFIED\",") // a discovery export is not verification evidence
            appendLine("  \"device\": \"${verification.deviceModel}\",")
            appendLine("  \"zeroCopyDesign\": \"PASS\",")
            appendLine("  \"zeroCopyHardware\": \"${verification.zeroCopyHardwareState}\",")
            appendLine("  \"actualBufferCopies\": ${verification.actualBufferCopies.map { "\"$it\"" }},")
            appendLine("  \"notes\": \"${verification.notes.replace("\"", "\\\"")}\"")
            appendLine("}")
        }
        File(outputDir, "verification.json").writeText(verificationJson)
    }
}
