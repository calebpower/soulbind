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

package dev.soulbind.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T1 — HMAC request signing and canonicalisation.
 *
 * <p>Every claim here is a claim about a <b>wire contract</b>, because this
 * class is re-implemented in PHP. The digests below are pinned against an
 * oracle outside this JVM (see {@code docs/protocol.md}); asserting only that
 * {@code sign(x) == sign(x)} would hold just as happily after the canonical
 * form silently changed, which is exactly the change that breaks the other
 * implementation.
 *
 * <p><b>What these tests do not prove:</b> that the PHP implementation agrees.
 * Only the generated corpus in {@code vectors/}, consumed from both languages,
 * carries that claim.
 */
class RequestSignerTest {

    private static final byte[] KEY = "soulbind-test-key".getBytes(StandardCharsets.UTF_8);

    @Nested
    @DisplayName("canonical form")
    class CanonicalForm {

        @Test
        @Tag("charset")
        @DisplayName("is newline-separated timestamp, nonce, body -- in UTF-8")
        void layout() {
            assertHostileCharsetTookEffectWhenAsked();
            assertArrayEquals(
                    "1700000000\nabc123\n{\"a\":1}".getBytes(StandardCharsets.UTF_8),
                    RequestSigner.canonicalBytes(1_700_000_000L, "abc123", "{\"a\":1}"));
        }

        @Test
        @Tag("charset")
        @DisplayName("encodes a non-ASCII body as UTF-8, not platform-default bytes")
        void nonAsciiBody() {
            assertHostileCharsetTookEffectWhenAsked();
            // "café" is 5 bytes under UTF-8 and 4 under ISO-8859-1. A
            // canonicalisation that used the default charset would produce a
            // signature the PHP side -- which always uses UTF-8 -- cannot match,
            // and only for the requests that happen to contain non-ASCII.
            byte[] actual = RequestSigner.canonicalBytes(1L, "n", "café");
            assertArrayEquals(
                    new byte[] {'1', '\n', 'n', '\n', 'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9},
                    actual);
        }

        @Test
        @DisplayName("a body containing newlines is not ambiguous -- it is last")
        void bodyMayContainNewlines() {
            assertArrayEquals(
                    "1\nn\nline1\nline2".getBytes(StandardCharsets.UTF_8),
                    RequestSigner.canonicalBytes(1L, "n", "line1\nline2"));
        }

        @Test
        @DisplayName("an absent body canonicalises as empty, not as \"null\"")
        void nullBody() {
            assertArrayEquals(
                    "1\nn\n".getBytes(StandardCharsets.UTF_8),
                    RequestSigner.canonicalBytes(1L, "n", null));
            assertArrayEquals(
                    RequestSigner.canonicalBytes(1L, "n", ""),
                    RequestSigner.canonicalBytes(1L, "n", null));
        }

        @Test
        @DisplayName("no field boundary can be shifted between two different requests")
        void noBoundaryCollision() {
            // Without a separator, ("12", "3x") and ("123", "x") would sign the
            // same bytes -- an attacker who controls the nonce could then reuse a
            // signature for a different timestamp. This is the assertion that
            // makes the separator load-bearing rather than decorative.
            assertNotEquals(
                    new String(RequestSigner.canonicalBytes(12L, "3x", "b"),
                            StandardCharsets.UTF_8),
                    new String(RequestSigner.canonicalBytes(123L, "x", "b"),
                            StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("a nonce containing the separator is rejected, not silently accepted")
        void nonceWithSeparator() {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> RequestSigner.canonicalBytes(1L, "a\nb", "body"));
            assertTrue(
                    e.getMessage().contains("separator"),
                    () -> "the refusal must say why: " + e.getMessage());
        }

        @Test
        @DisplayName("a separator at the very START is rejected too")
        void separatorAtIndexZero() {
            // The case above puts the separator in the middle. Mutation found
            // that `indexOf(SEPARATOR) >= 0` weakened to `> 0` survived every
            // test -- because a value BEGINNING with the separator was never
            // tried, and index 0 is the one value the two conditions disagree
            // on.
            //
            // What that mutant permits: a nonce of "\nb" and a nonce of "" with
            // a body beginning "b" produce the same canonical bytes, so one
            // signature is valid for two different requests. That is the exact
            // ambiguity this guard exists to prevent, reachable by a caller who
            // chooses their own nonce.
            for (String leading : java.util.List.of("\nb", "\n", "\nnonce")) {
                IllegalArgumentException e = assertThrows(
                        IllegalArgumentException.class,
                        () -> RequestSigner.canonicalBytes(1L, leading, "body"),
                        () -> "a nonce beginning with the separator was accepted: "
                                + leading.replace("\n", "\\n"));
                assertTrue(e.getMessage().contains("separator"), e.getMessage());
            }
        }

        @Test
        @DisplayName("a carriage return in the nonce is ACCEPTED -- deliberately")
        void carriageReturnIsNotTheSeparator() {
            // The separator is LF alone, so a CR creates no ambiguity about
            // where a field ends, and the other implementation signs it.
            //
            // Stated as a test rather than left implicit because it is a
            // CONTRACT, not an accident of how the check was written: if either
            // side starts rejecting CR, it stops being able to verify what the
            // other produces, and a signature that verifies on one connector and
            // not another is the worst kind of intermittent.
            //
            // The nonce is generated client-side as a UUID, so no caller-supplied
            // CR reaches this in practice. On the receiving side a caller does
            // control it -- and SignedRequestVerifier catches
            // IllegalArgumentException and answers MALFORMED, so a hostile nonce
            // is a refusal rather than a 500.
            assertDoesNotThrow(
                    () -> RequestSigner.canonicalBytes(1L, "a\rb", "body"),
                    "CR is not the field separator; rejecting it here would diverge from the "
                            + "other implementation");
        }

        @ParameterizedTest
        @ValueSource(strings = {""})
        @DisplayName("an empty nonce is rejected")
        void emptyNonce(String nonce) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RequestSigner.canonicalBytes(1L, nonce, "body"));
        }

        @Test
        @DisplayName("an absent nonce is rejected")
        void nullNonce() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RequestSigner.canonicalBytes(1L, null, "body"));
        }
    }

    @Nested
    @DisplayName("signing")
    class Signing {

        @Test
        @Tag("charset")
        @DisplayName("is a stable, pinned lowercase-hex HMAC-SHA256")
        void pinnedVector() {
            assertHostileCharsetTookEffectWhenAsked();
            // Pinned against three oracles outside this JVM, all agreeing:
            //   printf '1700000000\nabc123\n{"a":1}' \
            //     | openssl dgst -sha256 -hmac 'soulbind-test-key'
            //   php -r 'echo hash_hmac("sha256", $c, "soulbind-test-key");'
            //   python3 hmac.new(key, canonical, hashlib.sha256).hexdigest()
            // The PHP one is the one that matters: it is the function the other
            // implementation of this class calls. A self-consistent assertion
            // would survive a change to the canonical form, the separator, the
            // hex case or the encoding -- every one of which breaks that side
            // silently, in production, for whoever deployed both.
            assertEquals(
                    "8d6c67e7d2420e18bb54aa175c4b381a661b82bd4668200d4a7f87a0f7bdbe80",
                    RequestSigner.sign(KEY, 1_700_000_000L, "abc123", "{\"a\":1}"));
        }

        @Test
        @DisplayName("hex is lowercase, because the PHP side's hash_hmac() is")
        void lowercaseHex() {
            String sig = RequestSigner.sign(KEY, 1L, "n", "b");
            assertEquals(sig.toLowerCase(java.util.Locale.ROOT), sig);
            assertEquals(64, sig.length(), "SHA-256 is 32 bytes, so 64 hex characters");
        }

        @Test
        @DisplayName("every input field changes the signature")
        void everyFieldMatters() {
            // If any of these collided, that field would be unsigned in practice
            // and an attacker could vary it freely under a captured signature.
            Set<String> signatures = new HashSet<>();
            signatures.add(RequestSigner.sign(KEY, 1L, "n", "b"));
            signatures.add(RequestSigner.sign(KEY, 2L, "n", "b"));
            signatures.add(RequestSigner.sign(KEY, 1L, "m", "b"));
            signatures.add(RequestSigner.sign(KEY, 1L, "n", "c"));
            signatures.add(RequestSigner.sign(
                    "other-key".getBytes(StandardCharsets.UTF_8), 1L, "n", "b"));
            assertEquals(5, signatures.size(), () -> "collision among: " + signatures);
        }

        @Test
        @DisplayName("an empty or absent key is rejected by US, not incidentally by the JCE")
        void rejectsEmptyKey() {
            // Signing with a zero-length key produces a perfectly valid HMAC that
            // anyone can reproduce, so refusing is the only safe behaviour.
            //
            // The message is asserted deliberately. SecretKeySpec ALSO rejects an
            // empty key ("Empty key"), so merely asserting the exception type
            // passes whether or not this class checks anything -- deleting the
            // precondition produced a green run when the assertion stopped
            // there. Pinning our own wording is what distinguishes "soulbind
            // refuses" from "a library we happen to call refuses, today".
            //
            // What this does NOT prove: that a short-but-nonempty key is
            // rejected. It is not -- key length is a deployment concern, and
            // `soulbind doctor` is where that check belongs.
            for (byte[] key : new byte[][] {new byte[0], null}) {
                IllegalArgumentException e = assertThrows(
                        IllegalArgumentException.class,
                        () -> RequestSigner.sign(key, 1L, "n", "b"));
                assertTrue(
                        e.getMessage().contains("signing key"),
                        () -> "expected soulbind's own precondition to fire, got: "
                                + e.getMessage());
            }
        }
    }

    @Nested
    @DisplayName("verification")
    class Verification {

        @Test
        @DisplayName("accepts the signature it produces")
        void roundTrip() {
            String sig = RequestSigner.sign(KEY, 1L, "n", "b");
            assertTrue(RequestSigner.verify(KEY, 1L, "n", "b", sig));
        }

        @Test
        @DisplayName("rejects a signature over any different field")
        void rejectsMismatch() {
            String sig = RequestSigner.sign(KEY, 1L, "n", "b");
            assertFalse(RequestSigner.verify(KEY, 2L, "n", "b", sig));
            assertFalse(RequestSigner.verify(KEY, 1L, "m", "b", sig));
            assertFalse(RequestSigner.verify(KEY, 1L, "n", "c", sig));
            assertFalse(RequestSigner.verify(
                    "other".getBytes(StandardCharsets.UTF_8), 1L, "n", "b", sig));
        }

        @Test
        @DisplayName("rejects an absent, empty or truncated signature")
        void rejectsDegenerate() {
            String sig = RequestSigner.sign(KEY, 1L, "n", "b");
            assertFalse(RequestSigner.verify(KEY, 1L, "n", "b", null));
            assertFalse(RequestSigner.verify(KEY, 1L, "n", "b", ""));
            assertFalse(
                    RequestSigner.verify(KEY, 1L, "n", "b", sig.substring(0, 32)),
                    "a prefix must not verify -- otherwise a one-character signature would");
        }

        @Test
        @DisplayName("is case-sensitive: uppercase hex is not the contract")
        void caseSensitive() {
            // Accepting both cases would make the wire form ambiguous, and the
            // vectors could then pin a form neither side actually enforces.
            String sig = RequestSigner.sign(KEY, 1L, "n", "b");
            assertFalse(
                    RequestSigner.verify(
                            KEY, 1L, "n", "b", sig.toUpperCase(java.util.Locale.ROOT)));
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
