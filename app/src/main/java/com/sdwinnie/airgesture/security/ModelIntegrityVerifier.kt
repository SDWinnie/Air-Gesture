package com.sdwinnie.airgesture.security

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Loads the bundled MediaPipe Hand Landmarker model strictly into volatile DirectByteBuffer RAM
 * and verifies its cryptographic SHA-256 digest before handing it to the native runtime.
 * Never extracts or writes temporary model files to flash storage.
 */
object ModelIntegrityVerifier {

    const val ASSET_FILE_NAME = "hand_landmarker.task"
    const val EXPECTED_SIZE_BYTES = 7819105
    const val EXPECTED_SHA256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"

    fun loadVerifiedDirectBuffer(context: Context): ByteBuffer {
        val bytes = context.assets.open(ASSET_FILE_NAME).use { input ->
            input.readBytes()
        }

        if (bytes.size != EXPECTED_SIZE_BYTES) {
            bytes.fill(0)
            throw SecurityException(
                "Bundled model byte length mismatch: expected $EXPECTED_SIZE_BYTES, actual ${bytes.size}"
            )
        }

        val digestBytes = MessageDigest.getInstance("SHA-256").digest(bytes)
        val actualHex = buildString(digestBytes.size * 2) {
            for (b in digestBytes) {
                append(String.format("%02x", b.toInt() and 0xFF))
            }
        }

        if (!actualHex.equals(EXPECTED_SHA256, ignoreCase = true)) {
            bytes.fill(0)
            throw SecurityException("Bundled model SHA-256 integrity check failed")
        }

        val directBuffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        directBuffer.put(bytes)
        directBuffer.rewind()
        // Zero temporary heap byte array immediately after copying into direct native RAM
        bytes.fill(0)
        return directBuffer
    }
}
