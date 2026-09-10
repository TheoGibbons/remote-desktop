package co.remotedesktop

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption shared with the Windows app. Keys are derived from the
 * session key with HKDF-SHA256; frames are AES-256-GCM with the channel byte as
 * associated data. The relay only ever sees ciphertext + routing metadata.
 *
 * Wire format: nonce(12) || ciphertext || tag(16).
 * Channels: 0 = JSON control, 1 = video, 2 = file chunk.
 *
 * Verified against server/crypto.mjs and Crypto.cs via [selfTest].
 */
object Crypto {

    private val SALT = "remote-desktop/v1".toByteArray()
    private val INFO_ENC = "e2ee-key".toByteArray()
    private val INFO_PAIR = "pair-id".toByteArray()

    const val CH_JSON: Byte = 0
    const val CH_VIDEO: Byte = 1
    const val CH_FILE: Byte = 2
    const val CH_PATCH: Byte = 3 // dirty-rect screen patch
    const val CH_H264: Byte = 4  // H.264 access unit

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** HKDF-SHA256 (RFC 5869). */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** @return Pair(32-byte AES key, lowercase-hex pairing id sent to the server). */
    fun deriveKeys(sessionKey: String): Pair<ByteArray, String> {
        val ikm = sessionKey.toByteArray()
        val encKey = hkdf(ikm, SALT, INFO_ENC, 32)
        val pairId = hkdf(ikm, SALT, INFO_PAIR, 32).toHex()
        return Pair(encKey, pairId)
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, channel: Byte): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return encryptWithNonce(key, plaintext, channel, nonce)
    }

    private fun encryptWithNonce(key: ByteArray, plaintext: ByteArray, channel: Byte, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(byteArrayOf(channel))
        val ctTag = cipher.doFinal(plaintext) // ciphertext || 16-byte tag
        return nonce + ctTag
    }

    /** @return plaintext, or null if malformed or authentication fails. */
    fun decrypt(key: ByteArray, blob: ByteArray, channel: Byte): ByteArray? {
        if (blob.size < 12 + 16) return null
        return try {
            val nonce = blob.copyOfRange(0, 12)
            val ctTag = blob.copyOfRange(12, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(byteArrayOf(channel))
            cipher.doFinal(ctTag)
        } catch (e: Exception) {
            null
        }
    }

    /** Verifies this implementation against the shared cross-language vectors. */
    fun selfTest(): Boolean {
        val key = "correct horse battery staple"
        val expectEncKey = "198b7e020fa0c8fdfeb3514e6b402eb3ea5af30addadc19153965eeec0e8dabf"
        val expectPairId = "9a81be09e574b5a3078107db507c21722e8ea1f9f0a7bf762d6894a5dd371626"
        val expectBlob =
            "000102030405060708090a0b11316fc47766f9c1dec528cf275cf3fa7d82e9c7f09830195d76f8782e7999f9150b6d97be135331"
        val plaintext = "{\"type\":\"mouse\",\"x\":0.5}"

        val (enc, pid) = deriveKeys(key)
        if (enc.toHex() != expectEncKey) return false
        if (pid != expectPairId) return false

        val nonce = ByteArray(12) { it.toByte() } // 00 01 02 ... 0b
        val blob = encryptWithNonce(enc, plaintext.toByteArray(), CH_JSON, nonce)
        if (blob.toHex() != expectBlob) return false

        val dec = decrypt(enc, blob, CH_JSON) ?: return false
        if (String(dec) != plaintext) return false
        if (decrypt(enc, blob, CH_VIDEO) != null) return false // AAD binding
        return true
    }
}
