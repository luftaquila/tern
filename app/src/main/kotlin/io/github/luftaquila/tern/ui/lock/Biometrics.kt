// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui.lock

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings

/** The platform biometric prompt (Android 9+); fingerprint or face, whatever the device has enrolled. */
object Biometrics {

    enum class Status { READY, NOT_ENROLLED, UNAVAILABLE }

    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun status(context: Context): Status {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Status.UNAVAILABLE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 9 cannot tell whether anything is enrolled; the prompt reports it.
            return if (hasFingerprint(context)) Status.READY else Status.UNAVAILABLE
        }
        val manager = context.getSystemService(BiometricManager::class.java) ?: return Status.UNAVAILABLE
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.canAuthenticate(AUTHENTICATORS)
        } else {
            @Suppress("DEPRECATION")
            manager.canAuthenticate()
        }
        return when (result) {
            BiometricManager.BIOMETRIC_SUCCESS -> Status.READY
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> Status.NOT_ENROLLED
            else -> Status.UNAVAILABLE
        }
    }

    fun available(context: Context): Boolean = status(context) == Status.READY

    /** Whether the device has a fingerprint sensor; labels then say "fingerprint" instead of "biometrics". */
    fun hasFingerprint(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)

    /** Opens the system screen for enrolling a fingerprint (or face), or the security settings. */
    fun openEnrollment(context: Context) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, AUTHENTICATORS))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                @Suppress("DEPRECATION")
                add(Intent(Settings.ACTION_FINGERPRINT_ENROLL))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
        for (intent in intents) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    /**
     * Shows the prompt. [onSuccess] and [onError] run on the main thread; [onError] gets the
     * system's message for real errors (lockout, no sensor, ...), not for cancelling. Cancel
     * the prompt with the returned signal.
     */
    fun prompt(
        context: Context,
        title: String,
        subtitle: String?,
        negative: String,
        onSuccess: () -> Unit,
        onError: (CharSequence) -> Unit = {},
    ): CancellationSignal? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val executor = context.mainExecutor
        val builder = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setNegativeButton(negative, executor) { _, _ -> }
        if (subtitle != null) builder.setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setAllowedAuthenticators(AUTHENTICATORS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setConfirmationRequired(false)
        val signal = CancellationSignal()
        builder.build().authenticate(
            signal,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    when (errorCode) {
                        BiometricPrompt.BIOMETRIC_ERROR_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED -> Unit
                        else -> onError(errString)
                    }
                }
            },
        )
        return signal
    }
}
