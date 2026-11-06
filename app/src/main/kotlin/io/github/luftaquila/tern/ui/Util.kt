// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ui

import android.content.Context
import io.github.luftaquila.tern.R
import io.github.luftaquila.tern.ssh.KeyPassphraseException
import io.github.luftaquila.tern.ssh.SshAuthenticationException
import io.github.luftaquila.tern.ssh.SshCancelledException
import io.github.luftaquila.tern.ssh.SshHostKeyRejectedException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A short, user-facing description of a connection/transfer failure. */
fun describeError(context: Context, error: Throwable): String {
    var e: Throwable? = error
    // Unwrap to the most specific known cause.
    while (e != null) {
        when (e) {
            is SshCancelledException -> return context.getString(R.string.error_cancelled)
            is SshHostKeyRejectedException -> return context.getString(R.string.error_host_key_rejected)
            is SshAuthenticationException -> return context.getString(R.string.error_auth_failed)
            is KeyPassphraseException -> return context.getString(R.string.error_key_passphrase)
            is UnknownHostException -> return context.getString(R.string.error_unknown_host)
            is ConnectException -> return context.getString(R.string.error_connection_refused)
            is NoRouteToHostException -> return context.getString(R.string.error_no_route)
            is SocketTimeoutException -> return context.getString(R.string.error_timeout)
        }
        if (e.cause === e) break
        e = e.cause
    }
    val msg = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    return context.getString(R.string.error_generic, msg)
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return if (value >= 100) String.format(Locale.US, "%.0f %s", value, units[i])
    else String.format(Locale.US, "%.1f %s", value, units[i])
}

fun formatDate(epochSeconds: Long): String {
    if (epochSeconds <= 0) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSeconds * 1000))
}

/** "rwxr-xr-x" from POSIX permission bits. */
fun formatPermissions(mode: Int): String {
    if (mode < 0) return ""
    val chars = "rwxrwxrwx"
    val sb = StringBuilder(9)
    for (i in 0 until 9) {
        val bit = 1 shl (8 - i)
        sb.append(if (mode and bit != 0) chars[i] else '-')
    }
    return sb.toString()
}
