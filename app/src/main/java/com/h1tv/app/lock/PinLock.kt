package com.h1tv.app.lock

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.h1tv.app.App
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 電視版啟動 PIN 碼。
 * - PIN 和救援碼都只存加鹽雜湊（PBKDF2），不存明碼
 * - 連續輸錯 5 次要等 30 秒，之後每錯一次等待時間加倍（最多 15 分鐘），重開 App 也一樣要等
 * - 忘記 PIN：輸入設定時拿到的 8 位數救援碼，就能重設 PIN
 * - App 整個退到背景超過 1 分鐘，回來要重新輸入
 */
object PinLock {
    const val PIN_LENGTH = 4
    const val RECOVERY_LENGTH = 8
    private const val FREE_ATTEMPTS = 5
    private const val BASE_LOCKOUT_MS = 30_000L
    private const val MAX_LOCKOUT_MS = 15 * 60_000L
    private const val RELOCK_AFTER_MS = 60_000L
    private const val ITERATIONS = 10_000

    private val sp by lazy { App.instance.getSharedPreferences("pin_lock", Context.MODE_PRIVATE) }

    val enabled: Boolean get() = sp.contains("pin_hash")

    /** 第一次開 App 有沒有問過要不要設定 PIN */
    var prompted: Boolean
        get() = sp.getBoolean("prompted", false)
        set(value) = sp.edit().putBoolean("prompted", value).apply()

    /** 目前要不要顯示鎖定畫面 */
    var locked by mutableStateOf(false)
        private set

    private var installed = false
    private var backgroundAt = 0L

    /** MainActivity 建立時呼叫：剛開 App 就鎖，並監聽整個 App 進出背景 */
    fun install() {
        if (installed) return
        installed = true
        locked = enabled
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                backgroundAt = SystemClock.elapsedRealtime()
            }

            override fun onStart(owner: LifecycleOwner) {
                if (enabled && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= RELOCK_AFTER_MS) {
                    locked = true
                }
            }
        })
    }

    // ---------- 驗證 ----------

    enum class Result { OK, WRONG, LOCKED_OUT }

    /** 還要等幾毫秒才能再試（0 = 可以輸入） */
    fun lockoutRemainingMs(): Long {
        val until = sp.getLong("lockout_until", 0L)
        val now = System.currentTimeMillis()
        // 使用者把系統時間往回調，就以現在重新起算，避免永遠鎖住或被繞過
        if (until - now > MAX_LOCKOUT_MS) {
            sp.edit().putLong("lockout_until", now + MAX_LOCKOUT_MS).apply()
            return MAX_LOCKOUT_MS
        }
        return (until - now).coerceAtLeast(0L)
    }

    fun checkPin(pin: String): Result = check(pin, "pin_hash", "pin_salt")

    fun checkRecovery(code: String): Result = check(code, "recovery_hash", "recovery_salt")

    private fun check(input: String, hashKey: String, saltKey: String): Result {
        if (lockoutRemainingMs() > 0) return Result.LOCKED_OUT
        val salt = sp.getString(saltKey, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return Result.WRONG
        val expected = sp.getString(hashKey, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return Result.WRONG
        return if (MessageDigest.isEqual(hash(input, salt), expected)) {
            sp.edit().putInt("failures", 0).remove("lockout_until").apply()
            Result.OK
        } else {
            val failures = sp.getInt("failures", 0) + 1
            val edit = sp.edit().putInt("failures", failures)
            if (failures >= FREE_ATTEMPTS) {
                val wait = (BASE_LOCKOUT_MS shl (failures - FREE_ATTEMPTS).coerceAtMost(5)).coerceAtMost(MAX_LOCKOUT_MS)
                edit.putLong("lockout_until", System.currentTimeMillis() + wait)
            }
            edit.apply()
            Result.WRONG
        }
    }

    // ---------- 設定 ----------

    /** 設定（或變更）PIN。第一次設定時會產生新的救援碼並回傳，要顯示給使用者抄下來 */
    fun setPin(pin: String): String? {
        val edit = sp.edit()
        putHash(edit, pin, "pin_hash", "pin_salt")
        val newCode = if (!sp.contains("recovery_hash")) generateRecoveryCode().also {
            putHash(edit, it, "recovery_hash", "recovery_salt")
        } else null
        edit.putInt("failures", 0).remove("lockout_until").putBoolean("prompted", true).apply()
        return newCode
    }

    /** 解鎖流程整個走完（包括用救援碼重設、看完新救援碼）才呼叫 */
    fun unlock() {
        locked = false
    }

    /** 產生新的救援碼（舊的作廢） */
    fun regenerateRecoveryCode(): String {
        val code = generateRecoveryCode()
        val edit = sp.edit()
        putHash(edit, code, "recovery_hash", "recovery_salt")
        edit.apply()
        return code
    }

    fun disable() {
        sp.edit()
            .remove("pin_hash").remove("pin_salt")
            .remove("recovery_hash").remove("recovery_salt")
            .putInt("failures", 0).remove("lockout_until")
            .apply()
        locked = false
    }

    private fun putHash(edit: android.content.SharedPreferences.Editor, value: String, hashKey: String, saltKey: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        edit.putString(saltKey, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(hashKey, Base64.encodeToString(hash(value, salt), Base64.NO_WRAP))
    }

    private fun hash(value: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(value.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
    }

    private fun generateRecoveryCode(): String {
        val r = SecureRandom()
        return buildString { repeat(RECOVERY_LENGTH) { append(r.nextInt(10)) } }
    }

    /** 12345678 → 1234-5678 */
    fun formatRecovery(code: String) = code.chunked(4).joinToString("-")
}
