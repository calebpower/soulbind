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

import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.RequestSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Verifies a signed request: freshness, single use, then signature.
 *
 * <p>Used by the request-lifecycle transport, where there is no connection to
 * authenticate once and every request stands alone. The socket transport
 * authenticates at connect instead — same credential, same registry, different
 * moment.
 */
public final class SignedRequestVerifier {

    /** The outcome. A refusal names its reason so the caller is told what to fix. */
    public sealed interface Outcome {
        record Accepted() implements Outcome {}

        record Refused(ErrorCode code, String message) implements Outcome {}
    }

    private final Duration window;
    private final NonceStore nonces;

    public SignedRequestVerifier(Duration window, NonceStore nonces) {
        this.window = window;
        this.nonces = nonces;
    }

    /**
     * Checks one request.
     *
     * <p>Order matters and is deliberate. Freshness and single-use are checked
     * <em>before</em> the signature, because both are cheap and the signature is
     * a keyed hash over the whole body: doing it first would let anyone force
     * unbounded HMAC work by posting large bodies with no credential at all.
     *
     * <p>This does mean a caller learns "stale" before "bad signature". That is
     * not a disclosure worth defending: the timestamp is a value the caller
     * themselves supplied, and the clock is not a secret.
     *
     * @param connector the connector whose credential signed this, already
     *     resolved by the bearer token
     */
    public Outcome verify(
            ConnectorRecord connector,
            String presentedToken,
            String timestampHeader,
            String nonce,
            String signature,
            String body,
            Instant now) {

        if (timestampHeader == null || nonce == null || signature == null) {
            return new Outcome.Refused(
                    ErrorCode.MALFORMED,
                    "a signed request carries a timestamp, a nonce and a signature");
        }

        long timestamp;
        try {
            timestamp = Long.parseLong(timestampHeader.strip());
        } catch (NumberFormatException e) {
            return new Outcome.Refused(
                    ErrorCode.MALFORMED, "timestamp must be seconds since the epoch");
        }

        long skewSeconds = Math.abs(now.getEpochSecond() - timestamp);
        if (skewSeconds > window.toSeconds()) {
            // Both directions. A timestamp far in the FUTURE is refused too --
            // otherwise a captured request could be given a distant timestamp and
            // stay replayable indefinitely, which is the window with the lid off.
            return new Outcome.Refused(
                    ErrorCode.STALE_TIMESTAMP,
                    "timestamp is " + skewSeconds + "s from server time; the window is "
                            + window.toSeconds() + "s. Check the connector's clock.");
        }

        if (!nonces.recordIfNew(nonce, now)) {
            return new Outcome.Refused(
                    ErrorCode.REPLAYED_NONCE, "this nonce has already been used");
        }

        boolean valid;
        try {
            valid = RequestSigner.verify(
                    presentedToken.getBytes(StandardCharsets.UTF_8),
                    timestamp,
                    nonce,
                    body,
                    signature);
        } catch (IllegalArgumentException e) {
            // A nonce containing the field separator, or an empty key. Both are
            // refusals, not crashes: the caller controls these values.
            return new Outcome.Refused(ErrorCode.MALFORMED, e.getMessage());
        }

        if (!valid) {
            return new Outcome.Refused(
                    ErrorCode.BAD_SIGNATURE, "signature does not match the request body");
        }
        return new Outcome.Accepted();
    }

    /** The configured freshness window. */
    public Duration window() {
        return window;
    }

    /** Convenience for a caller that only wants the refusal. */
    public static Optional<Outcome.Refused> refusalOf(Outcome outcome) {
        return outcome instanceof Outcome.Refused refused
                ? Optional.of(refused)
                : Optional.empty();
    }
}
