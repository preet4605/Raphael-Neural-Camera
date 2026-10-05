package com.neuralcamera.runtime

import java.security.MessageDigest

/** Reads committed model artifacts from the module's classpath resources (`/models/...`). */
object ModelArtifacts {
    fun readResource(name: String): ByteArray {
        val stream = ModelArtifacts::class.java.getResourceAsStream("/models/$name")
            ?: throw IllegalStateException("Missing model resource /models/$name")
        return stream.use { it.readBytes() }
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
