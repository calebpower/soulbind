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

package dev.soulbind.core.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.RequestSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signature verifier, exercised directly.
 *
 * <p>Directly rather than through HTTP, because some of what it refuses cannot
 * be expressed as an HTTP request at all — a nonce containing a newline is
 * rejected by every conformant HTTP stack before it reaches a server. The
 * socket transport and the PHP client build the canonical form themselves, so
 * the defence is real; this is where it can be observed.
 */
class SignedRequestVerifierTest {

    private static final Duration WINDOW = Duration.ofSeconds(300);
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final String TOKEN = "a-test-credential";
    private static final String BODY = "{\"op\":\"heartbeat\"}";

    private static final ConnectorRecord CONNECTOR = new ConnectorRecord(
            "id", "name", "hash", ConnectorRecord.Status.ACTIVE,
            Set.of(Capability.CODE_DISPLAY), NOW, null);

    private SignedRequestVerifier verifier() {
        return new SignedRequestVerifier(WINDOW, new NonceStore(WINDOW));
    }

    private String sign(long timestamp, String nonce, String body) {
        return RequestSigner.sign(TOKEN.getBytes(StandardCharsets.UTF_8), timestamp, nonce, body);
    }

    private SignedRequestVerifier.Outcome verify(
            SignedRequestVerifier verifier, String nonce, String signature, String body) {
        return verifier.verify(
                CONNECTOR, TOKEN, String.valueOf(NOW.getEpochSecond()),
                nonce, signature, body, NOW);
    }

    @Test
    @DisplayName("a correctly signed request is accepted")
    void accepted() {
        String nonce = "n1";
        assertInstanceOf(
                SignedRequestVerifier.Outcome.Accepted.class,
                verify(verifier(), nonce, sign(NOW.getEpochSecond(), nonce, BODY), BODY));
    }

    @Test
    @DisplayName("a nonce containing the field separator is refused, not escaped")
    void nonceWithSeparator() {
        // Escaping it would be the wrong fix: two different (nonce, body) pairs
        // could then canonicalise identically, which is a signature forgery
        // wearing a helpful hat. RequestSigner throws; the verifier turns that
        // into a refusal rather than a crash, because the caller controls it.
        SignedRequestVerifier.Outcome outcome = verify(verifier(), "a\nb", "0".repeat(64), BODY);

        SignedRequestVerifier.Outcome.Refused refused = assertInstanceOf(
                SignedRequestVerifier.Outcome.Refused.class, outcome);
        assertEquals(ErrorCode.MALFORMED, refused.code());
        assertTrue(
                refused.message().contains("separator"),
                () -> "the refusal must say why: " + refused.message());
    }

    @Test
    @DisplayName("an empty nonce is refused")
    void emptyNonce() {
        SignedRequestVerifier.Outcome.Refused refused = assertInstanceOf(
                SignedRequestVerifier.Outcome.Refused.class,
                verify(verifier(), "", "0".repeat(64), BODY));
        assertEquals(ErrorCode.MALFORMED, refused.code());
    }

    @Test
    @DisplayName("freshness is checked BEFORE the signature")
    void freshnessBeforeSignature() {
        // The signature is a keyed hash over the whole body. Verifying it first
        // would let anyone force unbounded HMAC work by posting large bodies
        // with a credential they do not hold.
        SignedRequestVerifier.Outcome outcome = verifier().verify(
                CONNECTOR, TOKEN,
                String.valueOf(NOW.getEpochSecond() - 10_000),
                "n1",
                "not-even-a-signature",
                BODY,
                NOW);

        SignedRequestVerifier.Outcome.Refused refused = assertInstanceOf(
                SignedRequestVerifier.Outcome.Refused.class, outcome);
        assertEquals(
                ErrorCode.STALE_TIMESTAMP,
                refused.code(),
                "a stale request must be refused on its timestamp before any HMAC is computed");
    }

    @Test
    @DisplayName("single-use is checked before the signature too")
    void nonceBeforeSignature() {
        SignedRequestVerifier verifier = verifier();
        String nonce = "n1";
        verify(verifier, nonce, sign(NOW.getEpochSecond(), nonce, BODY), BODY);

        SignedRequestVerifier.Outcome.Refused refused = assertInstanceOf(
                SignedRequestVerifier.Outcome.Refused.class,
                verify(verifier, nonce, "garbage", BODY));
        assertEquals(ErrorCode.REPLAYED_NONCE, refused.code());
    }

    @Test
    @DisplayName("a missing header is a refusal, not an exception")
    void missingHeaders() {
        for (String[] headers : new String[][] {
            {null, "n", "s"}, {"1", null, "s"}, {"1", "n", null}
        }) {
            SignedRequestVerifier.Outcome.Refused refused = assertInstanceOf(
                    SignedRequestVerifier.Outcome.Refused.class,
                    verifier().verify(
                            CONNECTOR, TOKEN, headers[0], headers[1], headers[2], BODY, NOW),
                    () -> "accepted a request missing one of the signing headers");
            assertEquals(ErrorCode.MALFORMED, refused.code());
        }
    }

    @Test
    @DisplayName("an empty body signs and verifies")
    void emptyBody() {
        // GET-shaped operations still sign. An empty body must canonicalise the
        // same way on both sides, or those operations fail only in production.
        String nonce = "n1";
        assertInstanceOf(
                SignedRequestVerifier.Outcome.Accepted.class,
                verify(verifier(), nonce, sign(NOW.getEpochSecond(), nonce, ""), ""));
    }
}
