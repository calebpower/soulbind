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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request signing for the webhook/poll transport.
 *
 * <p>Signs {@code (timestamp, nonce, body)} with HMAC-SHA256. The recipient
 * rejects stale timestamps and replayed nonces, so a captured request is
 * useful to an attacker only inside the freshness window and only once.
 *
 * <p><b>This class is re-implemented in PHP.</b> Everything about it is
 * therefore a contract, not an implementation detail: the canonical form, the
 * separator, the encoding, the hex case. Golden vectors in {@code vectors/}
 * are the oracle proving the two agree, and they are run twice — once
 * normally, once under a hostile default charset — because a canonicalisation
 * that quietly depends on the platform encoding passes the first run and fails
 * on somebody else's machine.
 */
public final class RequestSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * The field separator inside the canonical string.
     *
     * <p>A newline, chosen because it cannot appear in a timestamp or a nonce
     * and so cannot be used to shift a boundary. Concatenating without a
     * separator would let {@code ("12", "3...")} and {@code ("123", "...")}
     * produce the same signed bytes — a canonicalisation collision, which is a
     * signature forgery in disguise.
     */
    private static final char SEPARATOR = '\n';

    private RequestSigner() {
        throw new AssertionError("no instances");
    }

    /**
     * The exact bytes that get signed.
     *
     * <p>Exposed because the vectors pin it: a change here is a change to the
     * wire contract, and the test that would catch it must be able to see the
     * canonical form rather than only the resulting digest.
     *
     * @throws IllegalArgumentException if a field contains the separator, which
     *     would make the canonical form ambiguous
     */
    public static byte[] canonicalBytes(long timestampSeconds, String nonce, String body) {
        requireNoSeparator("nonce", nonce);
        // The body may legitimately contain newlines -- it is JSON, and it is
        // last, so no boundary can be shifted by its content.
        String canonical = timestampSeconds + String.valueOf(SEPARATOR)
                + nonce + String.valueOf(SEPARATOR)
                + (body == null ? "" : body);

        // UTF-8 explicitly. Never the platform default: that is precisely the
        // dependency the hostile-charset vector run exists to catch.
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /** Lowercase hex HMAC-SHA256 over the canonical form. */
    public static String sign(byte[] key, long timestampSeconds, String nonce, String body) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("signing key must not be empty");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(canonicalBytes(timestampSeconds, nonce, body));
            // Lowercase hex, stated rather than incidental: the PHP side's
            // hash_hmac() returns lowercase, and "whichever case the library
            // happens to produce" is not a contract.
            return HexFormat.of().formatHex(digest);
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is required of every conformant JRE. If it is missing,
            // the runtime is broken in a way this code cannot sensibly handle.
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * Constant-time comparison of a presented signature against the expected one.
     *
     * <p>{@link MessageDigest#isEqual} rather than {@link String#equals}: string
     * comparison returns early at the first differing character, and that timing
     * difference is enough to recover a signature byte by byte.
     */
    public static boolean verify(
            byte[] key, long timestampSeconds, String nonce, String body, String presented) {
        if (presented == null) {
            return false;
        }
        String expected = sign(key, timestampSeconds, nonce, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireNoSeparator(String field, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        if (value.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException(
                    field + " must not contain the field separator; it would make the "
                            + "canonical form ambiguous and allow two different requests to "
                            + "produce identical signed bytes");
        }
    }
}
