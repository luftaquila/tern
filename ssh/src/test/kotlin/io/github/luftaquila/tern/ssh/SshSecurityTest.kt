// Copyright (C) 2026 luftaquila
// SPDX-License-Identifier: GPL-3.0-only
// Additional terms under GPL section 7 apply; see NOTICE.

package io.github.luftaquila.tern.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SshSecurityTest {

    /** Stands in for Android's stripped platform provider, which is also registered as "BC". */
    private class FakePlatformBc : Provider("BC", "1.0", "stripped platform BouncyCastle")

    @Test
    fun replacesForeignProviderNamedBc() {
        val original = Security.getProvider("BC")
        try {
            Security.removeProvider("BC")
            Security.addProvider(FakePlatformBc())
            assertTrue(Security.getProvider("BC") is FakePlatformBc)

            assertTrue(SshSecurity.ensureFullBouncyCastle())
            val provider = Security.getProvider("BC")
            assertEquals<Class<*>>(BouncyCastleProvider::class.java, provider.javaClass)
            // Idempotent: a full BouncyCastle provider is left alone.
            assertFalse(SshSecurity.ensureFullBouncyCastle())
            assertSame(provider, Security.getProvider("BC"))
            // The full provider has what sshj needs for curve25519 / ed25519 / chacha20.
            javax.crypto.KeyAgreement.getInstance("X25519", "BC")
            java.security.Signature.getInstance("Ed25519", "BC")
            javax.crypto.Cipher.getInstance("ChaCha7539", "BC")
        } finally {
            if (Security.getProvider("BC")?.javaClass != BouncyCastleProvider::class.java) {
                Security.removeProvider("BC")
                Security.addProvider(original ?: BouncyCastleProvider())
            }
        }
    }

    @Test
    fun installPinsSshjToBouncyCastle() {
        SshSecurity.install()
        SshSecurity.install()
        assertEquals("BC", SecurityUtils.getSecurityProvider())
        assertEquals<Class<*>>(BouncyCastleProvider::class.java, Security.getProvider("BC").javaClass)
    }
}
