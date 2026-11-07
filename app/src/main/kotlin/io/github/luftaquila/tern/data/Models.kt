// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import java.util.UUID

@Serializable
enum class AuthMethod { PASSWORD, KEY }

@Serializable
data class Host(
    val id: String = UUID.randomUUID().toString(),
    val label: String = "",
    val hostname: String = "",
    val port: Int = 22,
    val username: String = "",
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    /** Encrypted with [SecretBox]; null = ask on connect. */
    val encryptedPassword: String? = null,
    val keyId: String? = null,
    val colorIndex: Int = 0,
    /** LANG sent to the server; empty = don't send. */
    val lang: String = DEFAULT_LANG,
    val startupCommand: String = "",
    val termType: String = DEFAULT_TERM,
    val keepAliveSeconds: Int = 30,
    /** Host to connect through first (`ssh -J`); null connects directly. */
    val jumpHostId: String? = null,
    /** Port forwards opened with every terminal session. */
    val forwards: List<ForwardRule> = emptyList(),
    /** Lets programs on the server sign in elsewhere with this app's keys (`ssh -A`). */
    val agentForwarding: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = 0,
) {
    val displayName: String get() = label.ifBlank { hostname }
    val address: String get() = if (port == 22) "$username@$hostname" else "$username@$hostname:$port"

    companion object {
        const val DEFAULT_LANG = "en_US.UTF-8"
        const val DEFAULT_TERM = "xterm-256color"
        val LANG_PRESETS = listOf("en_US.UTF-8", "ko_KR.UTF-8", "C.UTF-8", "")
    }
}

@Serializable
enum class ForwardType { LOCAL, REMOTE, DYNAMIC }

/**
 * A port forward of a host. [port] is listened on: this device's loopback interface for
 * [ForwardType.LOCAL] and [ForwardType.DYNAMIC] (a SOCKS proxy), the server's for
 * [ForwardType.REMOTE]. Connections go to [destHost]:[destPort] as seen from the other side;
 * a SOCKS proxy has no fixed destination.
 */
@Serializable
data class ForwardRule(
    val type: ForwardType = ForwardType.LOCAL,
    val port: Int = 0,
    val destHost: String = "localhost",
    val destPort: Int = 0,
)

@Serializable
data class SshKeyEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val keyType: String,
    val publicKey: String,
    val fingerprint: String,
    /** Private key text encrypted with [SecretBox]. */
    val encryptedPrivateKey: String,
    /** Optional stored passphrase, encrypted with [SecretBox]. */
    val encryptedPassphrase: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class KnownHostEntry(
    val host: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
    val addedAtMillis: Long,
)

@Serializable
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/**
 * Terminal fonts. Bundled ones map to res/font in TerminalFonts; [SYSTEM_MONO] is the device's
 * monospace font (as the fallback: system fonts only); [CUSTOM] is an imported font file.
 */
@Serializable
enum class TerminalFont(val ligatures: Boolean = false) {
    JETBRAINS_MONO(ligatures = true),
    FIRA_CODE(ligatures = true),
    CASCADIA_CODE(ligatures = true),
    SOURCE_CODE_PRO,
    IBM_PLEX_MONO,
    HACK,
    MESLO,
    IOSEVKA(ligatures = true),
    ZERO_X_PROTO(ligatures = true),
    JETENDARD(ligatures = true),
    D2CODING,
    SYSTEM_MONO,
    CUSTOM,
}

/** A font the user imported: [file] in filesDir/fonts, shown as [name]. */
@Serializable
data class ImportedFont(val file: String, val name: String)

/** App lock credential; biometrics are an extra way to unlock, never the only one. */
@Serializable
enum class LockType { NONE, PIN, PATTERN }

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val colorSchemeId: String = "default-dark",
    val font: TerminalFont = TerminalFont.JETBRAINS_MONO,
    /** File name (inside filesDir/fonts) of the imported font used with [TerminalFont.CUSTOM]. */
    val customFontFile: String? = null,
    /** Display name of the single imported font of settings version 1; see [importedFonts]. */
    val customFontName: String? = null,
    /** Every imported font, in import order. */
    val importedFonts: List<ImportedFont> = emptyList(),
    /**
     * Font for characters the main font lacks, before Android's system fonts. [TerminalFont.SYSTEM_MONO]
     * leaves them to the system fonts; [TerminalFont.CUSTOM] uses [fallbackFontFile].
     */
    val fallbackFont: TerminalFont = TerminalFont.JETENDARD,
    val fallbackFontFile: String? = null,
    /** Programming ligatures (`->`, `!=`, ...) in fonts that have them; off by default. */
    val ligatures: Boolean = false,
    val fontSizeSp: Float = 13f,
    val cursorBlink: Boolean = true,
    val scrollbackLines: Int = 1000,
    /** Touch scrolling speed relative to the default (also scales flings); see TerminalView.SCROLL_BASE. */
    val scrollSpeed: Float = 1f,
    val keepScreenOn: Boolean = true,
    val vibrateOnBell: Boolean = true,
    val backspaceSendsCtrlH: Boolean = false,
    /**
     * Ask the keyboard not to learn from terminal input (IME_FLAG_NO_PERSONALIZED_LEARNING).
     * Gboard then runs in incognito mode, which also disables voice typing.
     */
    val imeIncognito: Boolean = false,
    /** Offer the post-quantum hybrid SSH key exchanges (ML-KEM-768 / sntrup761 + X25519) first. */
    val postQuantumKex: Boolean = true,
    /** Send `LC_TERMINAL=iTerm2` so programs use inline images (iTerm2 protocol). */
    val advertiseInlineImages: Boolean = true,
    val lockType: LockType = LockType.NONE,
    /** [SecretBox]-encrypted [LockSecrets] verifier of the PIN or pattern. */
    val lockSecret: String? = null,
    /** Digits in the PIN, so the lock screen can check it as soon as it is complete. */
    val lockPinLength: Int = 0,
    val lockBiometric: Boolean = false,
    /** Seconds the app may stay in the background before it locks again (0 = right away). */
    val lockAfterSeconds: Int = 0,
    val showExtraKeys: Boolean = true,
    val extraKeys: ExtraKeysConfig = ExtraKeysConfig.DEFAULT,
    /** Whether rows after the first are shown. */
    val extraKeysExpanded: Boolean = false,
    /** Extra key size: 1 = 40 dp tall keys. */
    val extraKeysScale: Float = 1f,
    /**
     * [SETTINGS_VERSION] the settings were last written with. Missing in older files (0), whose
     * values [migrated] brings up to date; fresh installs start at the current version.
     */
    val settingsVersion: Int = 0,
) {
    /** These settings with the changes made since [settingsVersion] applied. */
    fun migrated(): AppSettings {
        var s = this
        if (s.settingsVersion < 1) {
            s = s.copy(
                // Scroll speed used to be absolute with 0.75 as the default; now 1 = that default.
                scrollSpeed = ((s.scrollSpeed / 0.75f) * 10).roundToInt().div(10f).coerceIn(MIN_SCROLL_SPEED, MAX_SCROLL_SPEED),
                scrollbackLines = if (s.scrollbackLines == 5000) 1000 else s.scrollbackLines,
                fontSizeSp = s.fontSizeSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP),
                extraKeys = ExtraKeysConfig.migrate(s.extraKeys),
            )
        }
        if (s.settingsVersion < 2) {
            // Version 1 kept a single imported font.
            val file = s.customFontFile
            if (file != null && s.importedFonts.none { it.file == file }) {
                s = s.copy(importedFonts = s.importedFonts + ImportedFont(file, s.customFontName ?: file.substringBeforeLast('.')))
            }
            s = s.copy(customFontName = null)
        }
        if (s.settingsVersion < 3) {
            // Ligatures were on by default in version 2.
            s = s.copy(ligatures = false)
        }
        return s.copy(settingsVersion = SETTINGS_VERSION)
    }

    companion object {
        const val SETTINGS_VERSION = 3
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 28f
        const val MIN_KEY_SCALE = 0.7f
        const val MAX_KEY_SCALE = 1.5f
        const val MIN_SCROLL_SPEED = 0.5f
        const val MAX_SCROLL_SPEED = 2.5f

        /** Slider step of the scroll speed (10 %), giving it as many steps as the font size slider. */
        const val SCROLL_SPEED_STEP = 0.1f
    }
}
