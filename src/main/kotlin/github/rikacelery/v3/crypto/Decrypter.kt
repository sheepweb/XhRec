package github.rikacelery.v3.crypto

import java.util.*

object Decrypter {

    fun decode(encryptedB64: String, keyB64: String): String {
        val keyBytes = Base64.getDecoder().decode(keyB64)
        val encrypted = Base64.getDecoder().decode(encryptedB64)
        val decrypted = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            decrypted[i] = (encrypted[i].toInt() xor (keyBytes[i % keyBytes.size].toInt() and 0xFF)).toByte()
        }
        return String(decrypted, Charsets.UTF_8)
    }
}
