package com.tomclaw.appsend.tvlink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

internal object TvFrames {
    private const val MAX_BYTES = 256 * 1024

    fun read(source: InputStream): String {
        val input = DataInputStream(source)
        val size = input.readInt()
        require(size in 1..MAX_BYTES)
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }

    fun write(destination: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES)
        DataOutputStream(destination).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    fun digest(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)

    fun tokenHash(token: String): String? =
        if (token.matches(Regex("[0-9a-f]{64}"))) {
            digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } else null

    fun matches(expected: ByteArray, supplied: ByteArray): Boolean =
        MessageDigest.isEqual(expected, supplied)
}
