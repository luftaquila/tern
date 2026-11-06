// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.session

import io.github.luftaquila.tern.ssh.HostKeyInfo
import io.github.luftaquila.tern.ssh.HostKeyStatus
import io.github.luftaquila.tern.ssh.KeyboardInteractivePrompt
import io.github.luftaquila.tern.ssh.KnownHost
import io.github.luftaquila.tern.ssh.SshInteraction
import io.github.luftaquila.tern.ssh.SshTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A question the SSH layer is waiting on; the UI renders it as a dialog and answers it. */
sealed class PendingPrompt {
    abstract fun cancel()

    class HostKey(
        val info: HostKeyInfo,
        val status: HostKeyStatus,
        val previous: List<KnownHost>,
        private val result: CompletableDeferred<Boolean>,
    ) : PendingPrompt() {
        fun answer(trust: Boolean) = result.complete(trust)
        override fun cancel() { result.complete(false) }
    }

    class Password(
        val target: SshTarget,
        val attempt: Int,
        private val result: CompletableDeferred<Pair<String, Boolean>?>,
    ) : PendingPrompt() {
        fun answer(password: String, save: Boolean) = result.complete(password to save)
        override fun cancel() { result.complete(null) }
    }

    class Passphrase(
        val keyName: String,
        val attempt: Int,
        private val result: CompletableDeferred<String?>,
    ) : PendingPrompt() {
        fun answer(passphrase: String) = result.complete(passphrase)
        override fun cancel() { result.complete(null) }
    }

    class KeyboardInteractive(
        val name: String,
        val instruction: String,
        val prompts: List<KeyboardInteractivePrompt>,
        private val result: CompletableDeferred<List<String>?>,
    ) : PendingPrompt() {
        fun answer(responses: List<String>) = result.complete(responses)
        override fun cancel() { result.complete(null) }
    }
}

/** [SshInteraction] that parks each question in [pending] until the UI answers it. */
class PromptBridge : SshInteraction {
    private val _pending = MutableStateFlow<PendingPrompt?>(null)
    val pending: StateFlow<PendingPrompt?> = _pending.asStateFlow()

    /** Password the user asked to remember during the last connection attempt. */
    @Volatile
    var passwordToSave: String? = null

    override suspend fun confirmHostKey(info: HostKeyInfo, status: HostKeyStatus, previous: List<KnownHost>): Boolean {
        val d = CompletableDeferred<Boolean>()
        return ask(PendingPrompt.HostKey(info, status, previous, d), d)
    }

    override suspend fun requestPassword(target: SshTarget, attempt: Int): String? {
        val d = CompletableDeferred<Pair<String, Boolean>?>()
        val answer = ask(PendingPrompt.Password(target, attempt, d), d)
        passwordToSave = answer?.takeIf { it.second }?.first
        return answer?.first
    }

    override suspend fun requestPassphrase(keyName: String, attempt: Int): String? {
        val d = CompletableDeferred<String?>()
        return ask(PendingPrompt.Passphrase(keyName, attempt, d), d)
    }

    override suspend fun keyboardInteractive(
        name: String,
        instruction: String,
        prompts: List<KeyboardInteractivePrompt>,
    ): List<String>? {
        val d = CompletableDeferred<List<String>?>()
        return ask(PendingPrompt.KeyboardInteractive(name, instruction, prompts, d), d)
    }

    fun cancelPending() {
        _pending.value?.cancel()
    }

    private suspend fun <T> ask(prompt: PendingPrompt, result: CompletableDeferred<T>): T {
        _pending.value = prompt
        try {
            return result.await()
        } finally {
            _pending.compareAndSet(prompt, null)
        }
    }
}
