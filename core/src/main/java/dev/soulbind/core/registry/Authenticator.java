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

import dev.soulbind.core.storage.ConnectorRepository;
import java.util.Optional;

/**
 * Resolves a presented credential to a registered connector.
 *
 * <p>The presented token is hashed and looked up by hash. The plaintext is
 * never stored, never logged, and never compared against a stored plaintext,
 * because there is no stored plaintext.
 */
public final class Authenticator {

    private final ConnectorRepository connectors;

    public Authenticator(ConnectorRepository connectors) {
        this.connectors = connectors;
    }

    /**
     * Resolves a bearer token.
     *
     * <p>Returns empty for an absent, blank or unrecognised credential — one
     * outcome for all three, deliberately. Distinguishing "no credential" from
     * "wrong credential" in the response tells an attacker whether a token they
     * guessed exists, and the operator-facing detail belongs in the audit log
     * rather than in a reply to whoever asked.
     */
    public Optional<ConnectorRecord> authenticate(String presentedToken) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }
        return connectors.findByCredentialHash(Credentials.hash(presentedToken));
    }

    /**
     * Extracts a token from an {@code Authorization} header value.
     *
     * <p>{@code Bearer <token>} only. A bare token without the scheme is not
     * accepted: accepting both means two parsing paths, and the lenient one
     * eventually diverges from what the other implementation sends.
     */
    public static Optional<String> bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        String value = authorizationHeader.strip();
        // The scheme is case-insensitive per RFC 7235; the token is not.
        if (value.length() < 7 || !value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = value.substring(7).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
