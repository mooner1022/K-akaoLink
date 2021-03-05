package com.mooner.kakaolink

import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and
import kotlin.math.min

/**
 *
 * AESCipher Library that helps you AES encryption
 * @author Kiri.dev
 * imported to Kotlin by mooner1022
 */

object AESCipher {
    /**
     *
     * Turns Response {@link EncryptedBytes}
     * <p>
     * Use examples:
     * <ul>
     *   <li><code>AESCipher.encrypt("The quick brown fox jumps over the lazy dog 👻 👻", "René Über")</code></li>
     * </ul>
     * @param message Message to be encrypted
     * @param passphrase Secret passphrase to be used to encryption
     * @return EncryptedBytes
     * @see EncryptedBytes
     */
    fun encrypt(message: String, passphrase: String): EncryptedBytes {
        var salted = ByteArray(0)
        var dx = ByteArray(0)
        val salt = ByteArray(8)

        SecureRandom().nextBytes(salt)

        while (salted.size < 48) {
            dx = md5(
                addBytes(
                    dx,
                    passphrase.toByteArray(),
                    salt
                )
            )
            salted = addBytes(
                salted,
                dx
            )
        }

        val key = salted.copyOfRange(0, 32)
        val iv = salted.copyOfRange(32, 48)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")

        val finalIV = ByteArray(16)
        val length = min(iv.size, 16)

        System.arraycopy(iv, 0, finalIV, 0, length)

        val ivPS = IvParameterSpec(finalIV)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivPS)
        val bytes = cipher.doFinal(message.toByteArray())
        val saltedBytes = addBytes("Salted__".toByteArray(), salt, bytes)

        return EncryptedBytes(saltedBytes)
    }

    /**
     *
     * Turns Response {@link DecryptedBytes}
     * <p>
     * Use examples:
     * <ul>
     *   <li><code>AESCipher.decrypt("U2FsdGVkX1+tsmZvCEFa/iGeSA0K7gvgs9KXeZKwbCDNCs2zPo+BXjvKYLrJutMK+hxTwl/hyaQLOaD7LLIRo2I5fyeRMPnroo6k8N9uwKk=", "René Über")</code></li>
     * </ul>
     * @param cipherText Encrypted ciphertext to be decrypted
     * @param passphrase Secret passphrase to be used to decryption
     * @return DecryptedBytes
     * @see DecryptedBytes
     */
    fun decrypt(cipherText: String, passphrase: String): DecryptedBytes {
        val cipherData = Base64.getDecoder().decode(cipherText)
        val saltData = cipherData.copyOfRange(8, 16)

        val md = MessageDigest.getInstance("MD5")

        val keys = generateDecryptKey(saltData, passphrase.toByteArray(), md)
        val keySpec = SecretKeySpec(keys[0], "AES")
        val ivPS = IvParameterSpec(keys[1])

        val encrypted = cipherData.copyOfRange(16, cipherData.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivPS)

        val decryptedData = cipher.doFinal(encrypted)

        return DecryptedBytes(decryptedData)
    }

    /**
     *
     * Basic reference https://stackoverflow.com/questions/41432896/cryptojs-aes-encryption-and-java-aes-decryption
     */
    private fun generateDecryptKey(salt: ByteArray?, password: ByteArray, md: MessageDigest): Array<ByteArray> {
        val digestLength = md.digestLength
        val requiredLength = (48 + digestLength - 1) / digestLength * digestLength
        val generatedData = ByteArray(requiredLength)
        var generatedLength = 0

        md.reset()

        while (generatedLength < 48) {
            if (generatedLength > 0) md.update(generatedData, generatedLength - digestLength, digestLength)
            md.update(password)

            if (salt != null) md.update(salt, 0, 8)
            md.digest(generatedData, generatedLength, digestLength)

            generatedLength += digestLength
        }

        val result = arrayOf(
            generatedData.copyOfRange(0, 32),
            generatedData.copyOfRange(32,48)
        )
        generatedData.fill(0,0,generatedData.size)

        return result
    }

    /**
     *
     * @param bytes ByteArray to be added
     * @return Added ByteArray
     */
    private fun addBytes(vararg bytes: ByteArray): ByteArray {
        var len = 0
        for (b in bytes) len += b.size

        val r = ByteArray(len)
        var c = 0
        for (b in bytes) {
            System.arraycopy(b, 0, r, c, b.size)
            c += b.size
        }

        return r
    }

    /**
     *
     * @param input Input to be hashed
     * @return hashed bytearray
     */
    private fun md5(input: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5").apply {
            reset()
            update(input)
        }
        return md.digest()
    }
}

data class EncryptedBytes(
    private val output: ByteArray
) {
    override fun toString(): String {
        return toString("base64")
    }

    /**
     *
     * Turns to {@link String}
     * <p>
     * Use examples:
     * <ul>
     *   <li><code>AESCipher.encrypt(message, secret).toString("base64")</code></li>
     *   <li><code>AESCipher.encrypt(message, secret).toString("hex")</code></li>
     * </ul>
     * @param transformation transformation option
     * @return hex string or base64 string
     * @throws InvalidTransformationException when transformation is neither "hex" or "base64"
     */
    fun toString(transformation: String):String {
        when(transformation) {
            "hex" -> {
                val sb = StringBuilder()
                for (b in output) sb.append(String.format("%02x", b and 0xFF.toByte()))
                return sb.toString()
            }
            "base64" -> {
                val encoder = Base64.getEncoder()
                return encoder.encodeToString(output)
            }
            else -> {
                throw InvalidTransformationException(
                    "Invalid toString transformation",
                    transformation
                )
            }
        }
    }
}

data class DecryptedBytes(
    private val output: ByteArray
) {
    override fun toString(): String {
        return String(output)
    }

    /**
     *
     * Turns to {@link String}
     * <p>
     * Use examples:
     * <ul>
     *   <li><code>AESCipher.decrypt(message, secret).toString()</code></li>
     *   <li><code>AESCipher.decrypt(message, secret).toString("UTF-8")</code></li>
     * </ul>
     * @param charset transformation option
     * @return String
     */
    fun toString(charset:String):String = String(output, Charset.forName(charset))
}

/**
 *
 * Exception thrown when the transformation is invalid.
 */
class InvalidTransformationException(message: String, private val transformation: String) :
    IllegalArgumentException(message) {

    override fun toString(): String {
        return super.toString() + ". TRANSFORMATION=" + transformation
    }
}