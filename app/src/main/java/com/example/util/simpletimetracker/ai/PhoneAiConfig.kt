package com.example.util.simpletimetracker.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Phone-only BYOK credentials: encrypted at rest with Android Keystore.
 * NEVER package purchased API keys in APK, logs, GitHub, or Railway.
 */
class PhoneAiConfig(private val context: Context) {
    data class Settings(
        val endpoint: String,
        val model: String,
        val key: String,
        val enabledSince: Long
    )
    private val file = AtomicFile(File(context.noBackupFilesDir, "stt-ai-direct-v1.json"))

    fun load(): Settings? {
        if (!file.baseFile.exists()) return null
        val data = JSONObject(String(file.readFully(), StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = Base64.decode(data.getString("iv"), Base64.NO_WRAP)
        val bytes = Base64.decode(data.getString("ciphertext"), Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, iv))
        val key = String(cipher.doFinal(bytes), StandardCharsets.UTF_8)
        return Settings(data.getString("endpoint"), data.getString("model"), key, data.getLong("enabled_since"))
    }

    fun configured(): Boolean = runCatching { load() != null }.getOrDefault(false)

    fun save(endpoint: String, model: String, newKey: String?) {
        val normalized = validateEndpoint(endpoint)
        val cleanModel = model.trim()
        require(cleanModel.length in 1..128 && !cleanModel.any { it.isISOControl() }) {
            "请输入有效的模型 ID"
        }
        val oldKey = if (newKey.isNullOrBlank()) load()?.key else null
        val key = newKey?.trim()?.takeIf { it.isNotEmpty() } ?: oldKey
        require(key != null && key.length in 8..2048 && !key.any { it.isWhitespace() }) {
            "请输入购买的模型 API Key"
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val encoded = cipher.doFinal(key.toByteArray(StandardCharsets.UTF_8))
        val body = JSONObject().put("endpoint", normalized).put("model", cleanModel)
            .put("enabled_since", System.currentTimeMillis())
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encoded, Base64.NO_WRAP))
        val output = file.startWrite()
        try {
            output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    fun disable() {
        file.delete()
    }

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("timetrak_ai_direct_key_v1", null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder("timetrak_ai_direct_key_v1",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()
        )
        return generator.generateKey()
    }

    companion object {
        fun validateEndpoint(value: String): String {
            val uri = URI(value.trim())
            require(uri.scheme.equals("https", ignoreCase = true)
                    && !uri.host.isNullOrEmpty()
                    && uri.userInfo == null && uri.query == null && uri.fragment == null
                    && uri.port != 0 && uri.port >= -1
                    && uri.path.endsWith("/chat/completions")) {
                "填写完整的 HTTPS Chat Completions 地址，例如 https://.../v1/chat/completions"
            }
            return uri.toASCIIString()
        }
    }
}
