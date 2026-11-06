// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LockSecretsTest {

    @Test
    fun pinRoundTrip() {
        val verifier = LockSecrets.create(LockSecrets.pin("2580"))
        assertTrue(LockSecrets.verify(LockSecrets.pin("2580"), verifier))
        assertFalse(LockSecrets.verify(LockSecrets.pin("2581"), verifier))
        assertFalse(LockSecrets.verify(LockSecrets.pin("258"), verifier))
    }

    @Test
    fun patternRoundTripAndOrderMatters() {
        val verifier = LockSecrets.create(LockSecrets.pattern(listOf(0, 4, 8, 5)))
        assertTrue(LockSecrets.verify(LockSecrets.pattern(listOf(0, 4, 8, 5)), verifier))
        assertFalse(LockSecrets.verify(LockSecrets.pattern(listOf(5, 8, 4, 0)), verifier))
    }

    @Test
    fun pinAndPatternNeverCollide() {
        // "pin:" and "pattern:" prefixes keep a PIN of digits from matching a pattern of the same dots.
        val verifier = LockSecrets.create(LockSecrets.pattern(listOf(1, 2, 3, 4)))
        assertFalse(LockSecrets.verify(LockSecrets.pin("1,2,3,4"), verifier))
    }

    @Test
    fun saltedSoSameSecretDiffers() {
        assertNotEquals(LockSecrets.create(LockSecrets.pin("0000")), LockSecrets.create(LockSecrets.pin("0000")))
    }

    @Test
    fun malformedVerifiersFail() {
        for (v in listOf("", "x", "pbkdf2-sha256\$0\$AA==\$AA==", "md5\$1\$AA==\$AA==", "pbkdf2-sha256\$1000\$!!\$AA==")) {
            assertFalse(LockSecrets.verify(LockSecrets.pin("1234"), v), v)
        }
    }
}
