// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.lock

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import io.github.luftaquila.tern.data.AppSettings
import io.github.luftaquila.tern.data.LockSecrets
import io.github.luftaquila.tern.data.LockType
import io.github.luftaquila.tern.data.SecretBox
import io.github.luftaquila.tern.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * App lock state: locked at start and when the app comes back after [AppSettings.lockAfterSeconds]
 * in the background. SSH sessions keep running underneath; only the UI is covered.
 */
class AppLock(private val settings: SettingsRepository) {
    private val _locked = MutableStateFlow(isEnabled(settings.value))
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var stoppedAt = 0L
    private var returningFromPicker = false

    /** Wrong attempts since the last unlock; too many lock the pad for a while. */
    var failures by mutableIntStateOf(0)
        private set
    var lockedOutUntil by mutableLongStateOf(0L)
        private set

    /** Wrong attempts left before the pad locks for a while (0 or less once it has). */
    val attemptsLeft: Int get() = MAX_TRIES - failures

    fun isEnabled(s: AppSettings = settings.value): Boolean = s.lockType != LockType.NONE && s.lockSecret != null

    /** A file picker or similar is about to cover the app: coming back from it is no reason to lock. */
    fun beforeExternalPicker() {
        returningFromPicker = true
    }

    fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
    }

    fun onStart() {
        val s = settings.value
        val pickerReturn = returningFromPicker
        returningFromPicker = false
        if (!isEnabled(s)) {
            _locked.value = false
            return
        }
        if (pickerReturn || stoppedAt == 0L) return
        if (SystemClock.elapsedRealtime() - stoppedAt >= s.lockAfterSeconds * 1000L) _locked.value = true
    }

    /** Unlocks if [secret] (see [LockSecrets.pin] / [LockSecrets.pattern]) is right. */
    suspend fun unlock(secret: String): Boolean {
        val ok = check(secret)
        if (ok) unlocked() else failed()
        return ok
    }

    /** Checks [secret] without changing the lock state (confirming before settings changes). */
    suspend fun check(secret: String): Boolean {
        val verifier = SecretBox.decryptOrNull(settings.value.lockSecret) ?: return false
        // PBKDF2 is slow on purpose: keep it off the main thread.
        return withContext(Dispatchers.Default) { LockSecrets.verify(secret, verifier) }
    }

    fun unlockedByBiometrics() = unlocked()

    /**
     * Checks [secret] before a sensitive action such as exporting a private key. Wrong attempts
     * count toward the same lockout as on the lock screen, so the check cannot be guessed faster.
     */
    suspend fun confirm(secret: String): Boolean {
        val ok = check(secret)
        if (ok) clearFailures() else failed()
        return ok
    }

    /**
     * Sets a new credential, or removes the lock with [LockType.NONE]. [biometric] turns
     * biometric unlock on or off at the same time (null keeps it as it is).
     */
    suspend fun set(type: LockType, secret: String?, pinLength: Int = 0, biometric: Boolean? = null) {
        val stored = if (type == LockType.NONE || secret == null) {
            null
        } else {
            withContext(Dispatchers.Default) { SecretBox.encrypt(LockSecrets.create(secret)) }
        }
        settings.update {
            it.copy(
                lockType = if (stored == null) LockType.NONE else type,
                lockSecret = stored,
                lockPinLength = if (type == LockType.PIN) pinLength else 0,
                lockBiometric = stored != null && (biometric ?: it.lockBiometric),
            )
        }
        unlocked()
    }

    /** Biometric unlock is only an extra way in: it needs a PIN or pattern underneath. */
    fun setBiometric(enabled: Boolean) {
        settings.update { it.copy(lockBiometric = enabled && isEnabled(it)) }
    }

    private fun unlocked() {
        clearFailures()
        _locked.value = false
    }

    private fun clearFailures() {
        failures = 0
        lockedOutUntil = 0L
    }

    private fun failed() {
        failures++
        if (failures >= MAX_TRIES) lockedOutUntil = SystemClock.elapsedRealtime() + LOCKOUT_MS * (failures - MAX_TRIES + 1)
    }

    companion object {
        private const val MAX_TRIES = 5
        private const val LOCKOUT_MS = 30_000L
    }
}
