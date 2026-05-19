package github.rikacelery.v3.crypto

import org.jsoup.Connection
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

object Decrypter {

    suspend fun decode(encryptedB64: String, key: String): String {
        val encrypted = Base64.getDecoder().decode(encryptedB64.reversed())
        val key = Base64.getDecoder().decode(key)
        val decrypted = ByteArray(encrypted.size)
        for (i in encrypted.indices) {
            decrypted[i] = (encrypted[i].toInt() xor (key[i % key.size].toInt() and 0xFF)).toByte()
        }
        return String(decrypted, Charsets.UTF_8)
    }
}
