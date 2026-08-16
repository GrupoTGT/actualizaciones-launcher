package com.grupotgt.launcherkioscotgt.mdm

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString

internal object MdmCrypto {
    private val secureRandom = SecureRandom()

    fun newDeviceSecret(): String = ByteArray(32)
        .also(secureRandom::nextBytes)
        .let(::base64Url)

    fun newNonce(): String = ByteArray(24)
        .also(secureRandom::nextBytes)
        .let(::base64Url)

    fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun fingerprint(secret: String): String {
        val decoded = secret.decodeBase64()?.toByteArray()
            ?: error("Invalid MDM credential encoding")
        return MessageDigest.getInstance("SHA-256")
            .digest(decoded)
            .take(12)
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xff) }
    }

    fun hmacBase64Url(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    fun constantTimeEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        actual.toByteArray(StandardCharsets.UTF_8)
    )

    private fun base64Url(bytes: ByteArray): String = bytes.toByteString()
        .base64Url()
        .trimEnd('=')
}
