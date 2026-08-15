/*
 * Copyright (c) 2026 Caleb L. Power
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.soulbind.core.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Credential minting and hashing. */
class CredentialsTest {

    @Nested
    @DisplayName("minting")
    class Minting {

        @Test
        @DisplayName("carries 256 bits of entropy")
        void entropy() {
            byte[] raw = Base64.getUrlDecoder()
                    .decode(Credentials.mint().plaintext());
            assertEquals(
                    32,
                    raw.length,
                    "a credential shorter than 256 bits is one an attacker can eventually "
                            + "enumerate, and the hashing choice below assumes it cannot be");
        }

        @Test
        @DisplayName("is URL-safe and unpadded")
        void urlSafe() {
            // A credential ends up in config files, environment variables and
            // Authorization headers. '+', '/' and '=' each break at least one of
            // those, usually silently and usually only for some tokens.
            for (int i = 0; i < 200; i++) {
                String token = Credentials.mint().plaintext();
                assertTrue(
                        token.matches("[A-Za-z0-9_-]+"),
                        () -> "token contains a character that does not survive transport: "
                                + token);
            }
        }

        @Test
        @DisplayName("never repeats")
        void distinct() {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                assertTrue(
                        seen.add(Credentials.mint().plaintext()),
                        "two mints collided, which at 256 bits means the source is not random");
            }
        }

        @Test
        @DisplayName("the minted hash is the hash of the minted plaintext")
        void hashMatchesPlaintext() {
            // The pair must be consistent, or bootstrap hands out a credential
            // that authenticates as nothing and the failure surfaces much later.
            Credentials.Minted minted = Credentials.mint();
            assertEquals(Credentials.hash(minted.plaintext()), minted.hash());
        }

        @Test
        @DisplayName("the plaintext is not recoverable from the stored hash")
        void hashIsNotTheToken() {
            Credentials.Minted minted = Credentials.mint();
            assertNotEquals(minted.plaintext(), minted.hash());
            assertFalse(
                    minted.hash().contains(minted.plaintext()),
                    "the stored form must not embed the credential it stands for");
        }
    }

    @Nested
    @DisplayName("hashing")
    class Hashing {

        @Test
        @Tag("charset")
        @DisplayName("is a stable, pinned SHA-256 hex digest")
        void pinnedVector() {
            // Pinned, not self-consistent: asserting only that hash(x) == hash(x)
            // would pass just as happily if the algorithm silently changed, which
            // would invalidate every hash already in a deployed database.
            assertEquals(
                    "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
                    Credentials.hash("foo"));
        }

        @Test
        @Tag("charset")
        @DisplayName("is over UTF-8 bytes, not the platform default charset")
        void utf8() {
            assertHostileCharsetTookEffectWhenAsked();

            // "é" is one byte under ISO-8859-1 and two under UTF-8, so a
            // platform-default charset produces a different digest on a
            // differently-configured host and locks those connectors out.
            //
            // Pinned against `printf 'é' | sha256sum`, i.e. an oracle outside
            // this JVM. Comparing the implementation against itself -- hashing a
            // string round-tripped through UTF-8 bytes, say -- would be a
            // tautology: that round trip is the identity, so the assertion would
            // hold under any charset the implementation happened to pick.
            assertEquals(
                    "4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c",
                    Credentials.hash("\u00e9"));
            assertEquals(
                    "5ce3bbb3520e72079460b7a3b2479cb0a9605f2f9963557dc77d9eb7361fe727",
                    Credentials.hash("caf\u00e9 \u65e5\u672c"),
                    "multi-byte characters outside Latin-1 must digest the same everywhere");
        }

        @Test
        @DisplayName("distinct inputs give distinct digests")
        void distinctInputs() {
            assertNotEquals(Credentials.hash("a"), Credentials.hash("b"));
        }

        @Test
        @DisplayName("an empty or absent credential is rejected, not hashed")
        void rejectsEmpty() {
            // Hashing "" yields a perfectly valid-looking digest. If that ever
            // reached the database, a caller presenting nothing would authenticate.
            assertThrows(IllegalArgumentException.class, () -> Credentials.hash(""));
            assertThrows(IllegalArgumentException.class, () -> Credentials.hash(null));
        }
    }

    @Nested
    @DisplayName("comparison")
    class Comparison {

        @Test
        @DisplayName("equal hashes match, unequal do not")
        void basic() {
            String h = Credentials.hash("token");
            assertTrue(Credentials.hashesMatch(h, Credentials.hash("token")));
            assertFalse(Credentials.hashesMatch(h, Credentials.hash("other")));
        }

        @Test
        @DisplayName("a null on either side never matches")
        void nulls() {
            String h = Credentials.hash("token");
            assertFalse(Credentials.hashesMatch(null, h));
            assertFalse(Credentials.hashesMatch(h, null));
            assertFalse(
                    Credentials.hashesMatch(null, null),
                    "two absent credentials matching would let an unregistered caller in");
        }

        @Test
        @DisplayName("a prefix does not match the whole")
        void prefix() {
            String h = Credentials.hash("token");
            assertFalse(Credentials.hashesMatch(h.substring(0, 16), h));
        }
    }

    /**
     * Asserts the charset-hostility run is really hostile.
     *
     * <p>The {@code charsetHostilityTest} task exists because this JVM's default
     * charset is UTF-8, which makes an explicit-UTF-8 claim unobservable in the
     * ordinary run. If a future JDK ignores {@code file.encoding}, that task
     * would silently become a duplicate of the ordinary run and stop proving
     * anything. This makes that failure loud rather than invisible.
     */
    private static void assertHostileCharsetTookEffectWhenAsked() {
        if (!Boolean.getBoolean("soulbind.hostileCharset")) {
            return; // the ordinary run; nothing claimed about the default charset
        }
        assertNotEquals(
                StandardCharsets.UTF_8,
                Charset.defaultCharset(),
                "the charset-hostility task asked for a non-UTF-8 default charset and did not "
                        + "get one, so this run proves nothing the ordinary run did not. Fix the "
                        + "task rather than deleting this check.");
    }
}
