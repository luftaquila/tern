# AGENTS.md

- Android SSH and SFTP terminal with IME composition input: a JVM terminal emulator, an SSH layer on sshj and a Compose app
- Locations: emulator `terminal`, SSH and SFTP `ssh`, app `app` (IME `input`, terminal view `termview`, sessions `session`, stores `data`, billing `billing`, app lock `ui/lock`, extra keys `ui/terminal/ExtraKeysBar.kt`, snippets `ui/snippets`, export and import `data/Backup.kt`), table generators `terminal/tools/gen_wcwidth.py` and `app/tools/gen_nerd_font_coverage.py`

## AGENTS.md writing rules
- Terse bullets, noun phrases, no ambiguous wording
- Readable by an agent without memory from this file and the repo alone
  - No coined terms; each item clear on its own. Things in code named by their identifiers
- Behavior and formats readable directly from code left out. Core system structure kept
- No rationale, measurements, history, dates or commit hashes. A changed decision rewrites its item; the old one is not kept
- Numbers only when the value itself is the decision. Code constants named by identifier
- Rejected alternatives only as prohibitions, when needed to stop them being proposed again
- One decision per item. Sub-items only for conditions, exceptions and examples; examples from the repo
- New items in their section. No duplication across sections or with `README.md`
- Content matches current code. On a conflict, ask the user which side is right before changing either
- No environment-specific content (local paths, personal toolchain locations)
- No references to private or temporary documents

## System structure
- `terminal`: pure JVM, not thread-safe; every access (feed, resize, rendering reads) under the session `lock`
- `ssh`: pure JVM sshj wrapper; everything except `SshConnection.connect` blocking
  - `SshSecurity.install()` before any sshj use
- Key files read by `KeyFiles.load`, written by `OpenSshKeyWriter` for key generation and `SshKeys.exportOpenSsh`
- Forwards from `SshConnection.forwardLocal/forwardRemote/forwardDynamic`, closed with their connection
  - Device-side listeners on 127.0.0.1 only
- Jump hosts: `connect(..., via = jump)` through `jump`'s direct-tcpip channel; the jump connection closed with the target, or at once when connecting fails
- Agent forwarding: one `AgentChannelOpener` per connection from `openShell(..., agent)`; each private key loaded on its first sign request (`loadPrivateKey`); a key's certificate listed before the key
- User certificates (`SshCredential.PrivateKey.certificate`) offered before the plain key (`CertifiedKey`); a certificate of another key never sent
- Host certificates trusted only from `KnownHostsStore.certificateAuthorities` whose patterns cover the host (`HostPatterns`)
  - Certificate host key algorithms preferred only for such hosts (`TernSshConfig.preferCertificateHostKeys`, `StoreHostKeyVerifier.findExistingAlgorithms`)
  - A host certificate of another CA judged by its plain key; certified host keys not saved to known hosts
- RSA certificates through the `rsa-sha2-512/256-cert-v01` key algorithms of `TernSshConfig`
- Post-quantum key exchange in `ssh/PostQuantumKex.kt` on sshj's `KeyExchangeBase`; shared secret as `RawSecret`
- Terminal sessions and SFTP connected only through `session/HostConnector` (jump chain, stored credentials, remembered passwords per hop, agent keys)
- `SessionService`: special-use foreground service while any session is open
- Secret fields of the JSON stores encrypted with `SecretBox`
- Persisted identifiers fixed: Keystore alias `tern.secret.v1` (a new alias only with `SecretBox.migrateEarlierKeys`), color scheme ids
- Application ID `io.luftaquila.tern` fixed; Kotlin packages and `namespace` stay `io.github.luftaquila.tern`
- No mosh support

## IME invariants
- inputType `TYPE_CLASS_TEXT|NO_SUGGESTIONS|MULTI_LINE`; no `VISIBLE_PASSWORD`, no `TYPE_NULL`, no direct-keys mode
- Committed text never deleted behind the IME; every editable change reported with `imm.updateSelection`
- `ImeTextProcessor`: editable diffed against the remote-line mirror; composing non-ASCII text preedit only, an ASCII prefix sent at once
- Preedit drawn like terminal text, without underline or highlight
- Line-changing input (Enter, arrows, Tab, Ctrl/Alt, paste, mouse) only through `TerminalView.lineChangingInput`
  - Composition committed first, editable reset after; `restartInput` when a composition was active
- Soft composition: the last Hangul char rewritten by Gboard's `replaceText` treated as composing by `TerminalInputConnection`, never reported to the IME
  - Counts as composing for `restartInput`
- Each IME edit method in its own batch
- Pending composition sent without sticky modifiers (`TerminalView.commitComposition`); arming CTRL/ALT commits the composition first
- Leaving the terminal screen only through `exitTerminal`: composition committed and keyboard hidden first
- Predicted echo (`TerminalView.pendingEcho`) consumed as the cursor advances over it, also when the echo arrives in pieces; dropped on any other cursor movement or timeout
- `IME_FLAG_NO_PERSONALIZED_LEARNING` only with `AppSettings.imeIncognito`
- IME call tracing (`TernIme`) in debug builds only; typed text never logged in release builds
- Gboard Korean ignoring the first backspace after a space commit: keyboard behavior, not a bug

## Rendering
- `TerminalView` paints: primary, fallback (`AppSettings.fallbackFont`, `SYSTEM_MONO` for system fonts only), symbol (Noto Sans Symbols 2), icon (Symbols Nerd Font for code points in `NerdFontCoverage`)
- Glyph coverage of a paint's own font through `hasOwnGlyph`, not `Paint.hasGlyph`
- Inline images as negative `ImageCell` values per covered cell; text extraction skips them (`ImageCell.isImage`)
- Image rows drawn across the image's full width while any of the row's cells remains (`TerminalView.drawImages`)
- kitty graphics (`APC G`, `KittyGraphics.kt`): direct transmission only; placements as `ImageCell` tiles, virtual placements (U=1) only through Unicode placeholders
- Placeholder cells (`KittyPlaceholder.CODE_POINT`) never drawn as text; `KittyPlaceholder.resolve` gives each cell's image and position, the image fitted and centered in its placement
- kitty replies only with `i` or `I` in the command; `q` silences them
- XTVERSION never answered with another terminal's name
- PTY pixel size: columns × cell width by rows × cell height, no padding; cell size queries unanswered while unknown
- Terminal view sized for the keyboard's final position (`WindowInsets.imeAnimationTarget`), not the animated inset; cut off by its box while the keyboard moves
- Emulator resized on every view size change; the remote told (`TerminalBackend.reportSize`) only once the view size stops changing (`TerminalView.SIZE_REPORT_DELAY_MS`), never per animation frame
- `TerminalView.onDraw` clipped to its bounds; no `canvas.drawColor`
- Box drawing, block elements and Powerline glyphs from `BoxDrawing.kt`, not the font
- Cells with combining marks NFC-normalized before drawing
- Bundled fonts unmodified, each with its license in `app/src/main/assets/licenses` and the licenses screen
- `NerdFontCoverage.kt` regenerated with the icon font

## Extra keys
- Bar and editor preview from the same `ExtraKeysBarLayout`, `KeyRow` and `KeyCap`, sized by `KeyMetrics`
- Bar keys and the snippet panel (`SnippetPanel`) on raw pointer input, never `clickable`; taps fire on release
- Editor taps and drags in one gesture detector on the preview, not per key
- Text keys and snippets sent through `ExtraKeyActions.sendText`: without control characters through `sendTypedText`, otherwise `sendRaw`
- `ExtraKeyDef.Text`: one character or one of `ExtraKeysConfig.MACROS`; any other command key a snippet key (`ExtraKeyDef.SnippetRef`)
  - Macro keys of older layouts turned into snippet keys at startup (`SnippetRepository.adoptMacroKeys`)
- Deleting a snippet removes its keys (`SnippetRepository.delete`)

## Hosts
- One group per host (`Host.group`), groups differing only in case shown as one
- Tag field splitting comma-separated tags only when they are added, never while typing

## Backup
- Export file `BackupFile`: hosts and snippets readable; keys and saved passwords only in `BackupFile.secrets`, sealed with the export password (`Backup.seal`)
- No `SecretBox` ciphertext in an export file
- Export only after `ConfirmLock` when the app lock is on
- Import (`Backup.merge`): hosts and snippets with an existing id replaced, others added; keys matched by fingerprint; a host without a password in the file keeps its saved one
- Settings, known hosts, trusted CAs and the extra-keys layout not exported

## Billing
- Donations only (`Products.DONATIONS`), each consumed once paid
- No feature behind a purchase
- `container.lock.beforeExternalPicker()` before the Play purchase sheet and file pickers

## App lock
- Lock pad in its own full-screen window (`LockWindow`) above sheets and dialogs; `PromptDialog` shows nothing while locked
- PIN and pattern verifiers from `data/LockSecrets`, stored `SecretBox`-encrypted in `AppSettings.lockSecret`
- Biometrics only in addition to a PIN or pattern (`AppLock.setBiometric`)
- `ConfirmLock` before sensitive actions (private key export), sharing the lock screen's lockout (`AppLock.confirm`)

## Settings
- `AppSettings` stored with defaults encoded; a default changed for existing users needs an `AppSettings.SETTINGS_VERSION` bump and a conversion in `AppSettings.migrated()`
- Removed enum values loaded as the field's default (`coerceInputValues` in `JsonStore.JSON`); such fields need a sensible default

## UI
- en and ko string resources with the same keys, except `app_name`
- App name only from `R.string.app_name`, kept out of sentences
- No Hangul special cases (font groups, samples)
- No middle dot, em/en dashes or arrow chains as separators
- Descriptions as short complete sentences (Korean 합니다체); in settings rows a period only with two sentences
- Font size and scroll speed sliders with the same step count

## License
- GPL-3.0-only (`LICENSE`) with section 7 terms in `NOTICE`
- No permission for others to link the Google Play Billing Library; contributors grant it only to luftaquila (`CONTRIBUTING.md`)
- Copyright and SPDX header in every `.kt`, `.kts` and `.py` file, also written by the generators
- `app/src/main/assets/licenses/Tern.txt`: `NOTICE` followed by `LICENSE`, regenerated when either changes

## Working rules
- Behavior changes update this file; `README.md` only for the pitch
- `docs/privacy.md` and the store listing match the app; no claims for untested keyboards, devices or flows
- AGP built-in Kotlin; no `org.jetbrains.kotlin.android` plugin
- Release signing from the `ternUpload*` Gradle properties, outside the repo
- Tags and releases only on user request

## Verification
- `./gradlew test`; `./gradlew :app:assembleRelease` for R8
- Tests on behavior seen outside the code: screen contents, bytes a program or the server receives, the remote line after IME input, settings after migration
  - No tests pinning internals or timing
- Passing JVM tests do not verify keyboard behavior; IME changes checked on a device with Gboard
- kitty placeholders inside tmux checked with tmux `allow-passthrough on` and the `RGB` terminal feature
- UI text checked on a device for a lone word wrapped onto a second line
