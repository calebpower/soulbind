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

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Why a request was refused.
 *
 * <p>Every refusal carries one of these. A refusal without a machine-readable
 * reason forces the other side to match on prose, which breaks the first time
 * the prose is improved.
 *
 * <p>The wire form is stated rather than derived from {@link #name()}, for the
 * same reason as {@link Capability}: renaming a constant must not silently
 * become a protocol change.
 */
public enum ErrorCode {

    /** No credential, or one matching no registered connector. */
    UNKNOWN_CREDENTIAL("unknown-credential"),

    /** Registered, but suspended. */
    SUSPENDED("suspended"),

    /** Registered and active, but lacking the capability this operation needs. */
    MISSING_CAPABILITY("missing-capability"),

    /** The named operation does not exist at this schema version. */
    UNKNOWN_OPERATION("unknown-operation"),

    /** The message declared a schema version this peer does not speak. */
    SCHEMA_MISMATCH("schema-mismatch"),

    /** The message could not be parsed, or a required field was absent. */
    MALFORMED("malformed"),

    /** The signature did not match the body. */
    BAD_SIGNATURE("bad-signature"),

    /** The signed timestamp fell outside the freshness window. */
    STALE_TIMESTAMP("stale-timestamp"),

    /** The nonce has been seen before inside the freshness window. */
    REPLAYED_NONCE("replayed-nonce"),

    /** The request was well-formed and permitted, but its content was rejected. */
    INVALID_REQUEST("invalid-request"),

    /**
     * Something failed that the caller did not cause.
     *
     * <p>Deliberately opaque on the wire. An internal failure that leaks its
     * cause to an unauthenticated peer is an information disclosure; the detail
     * belongs in the audit log and the server's own logs.
     */
    INTERNAL("internal");

    private final String wireName;

    ErrorCode(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<ErrorCode> fromWireName(String s) {
        if (s == null) {
            return Optional.empty();
        }
        // Locale.ROOT: a Turkish default locale turns "I" into a dotless i, so a
        // locale-sensitive lowercase would make error parsing depend on where
        // the peer happens to be running.
        String needle = s.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(c -> c.wireName.equals(needle)).findFirst();
    }
}
