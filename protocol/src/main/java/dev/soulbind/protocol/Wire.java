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

/**
 * The field names every message uses, in one place.
 *
 * <p>These are contract, not implementation detail: the PHP connector builds
 * the same JSON by hand, and the golden vectors pin it. Naming them here rather
 * than repeating string literals across the codec, the transports and the
 * documentation means a rename is one edit that either compiles everywhere or
 * fails everywhere — never one that half-lands.
 *
 * <p>The envelope itself is deliberately <em>not</em> a Java record with a
 * bound JSON mapper in this module. {@code protocol} stays dependency-light
 * because everything added here is something the PHP side must mirror; the
 * binding lives with each implementation, and these constants plus the
 * documented shape are what the two agree on.
 *
 * <h2>Request</h2>
 *
 * <pre>{@code
 * {"schema": 1, "op": "hello", "id": "<uuid>", "payload": { ... }}
 * }</pre>
 *
 * <h2>Response</h2>
 *
 * <pre>{@code
 * {"schema": 1, "id": "<uuid>", "ok": true,  "payload": { ... }}
 * {"schema": 1, "id": "<uuid>", "ok": false, "error": {"code": "...", "message": "...",
 *                                                      "capability": "..."}}
 * }</pre>
 *
 * <p>{@code id} is echoed unchanged so a response can be matched to its request
 * on a multiplexed connection. It is chosen by the caller and is never
 * interpreted by the server — in particular it is not the idempotency key and
 * not the replay nonce, both of which are separate and mean different things.
 */
public final class Wire {

    private Wire() {
        throw new AssertionError("no instances");
    }

    /** Protocol schema version. Present on every message in both directions. */
    public static final String SCHEMA = "schema";

    /** Operation name, in its {@code dotted.lowercase} wire form. Requests only. */
    public static final String OP = "op";

    /** Caller-chosen correlation id, echoed unchanged. */
    public static final String ID = "id";

    /** Operation-specific body. Absent is equivalent to empty. */
    public static final String PAYLOAD = "payload";

    /** Whether the request succeeded. Responses only. */
    public static final String OK = "ok";

    /** Refusal detail. Present exactly when {@code ok} is false. */
    public static final String ERROR = "error";

    /** Machine-readable refusal reason: an {@link ErrorCode} wire name. */
    public static final String ERROR_CODE = "code";

    /** Human-readable detail. Never matched on by a peer. */
    public static final String ERROR_MESSAGE = "message";

    /**
     * The capability that was missing.
     *
     * <p>Present only with {@link ErrorCode#MISSING_CAPABILITY}. Naming it means
     * an operator can act on the refusal rather than guess which of several
     * capabilities the connector lacked.
     */
    public static final String ERROR_CAPABILITY = "capability";

    // --- signed-transport headers -------------------------------------------

    /** Credential presented as {@code Authorization: Bearer <token>}. */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** Seconds since the epoch, signed. */
    public static final String HEADER_TIMESTAMP = "X-Soulbind-Timestamp";

    /** Single-use value inside the freshness window, signed. */
    public static final String HEADER_NONCE = "X-Soulbind-Nonce";

    /** Lowercase-hex HMAC-SHA256 over the canonical form. */
    public static final String HEADER_SIGNATURE = "X-Soulbind-Signature";
}
