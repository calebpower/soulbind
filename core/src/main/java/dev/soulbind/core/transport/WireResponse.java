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

import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;

/**
 * A response, before it becomes bytes on any particular transport.
 *
 * <p>Both transports produce these and one codec renders them, so a refusal
 * reads identically whether it arrived over a socket or a request. Two
 * renderings would drift, and the one exercised less would drift further.
 *
 * @param ok whether the request succeeded
 * @param payload the operation's result, or null on refusal
 * @param code the machine-readable reason, or null on success
 * @param message human-readable detail; never matched on by a peer
 * @param capability the missing capability, present only with
 *     {@link ErrorCode#MISSING_CAPABILITY}
 */
public record WireResponse(
        boolean ok, Object payload, ErrorCode code, String message, Capability capability) {

    public static WireResponse ok(Object payload) {
        return new WireResponse(true, payload, null, null, null);
    }

    public static WireResponse error(ErrorCode code, String message) {
        return new WireResponse(false, null, code, message, null);
    }

    /** A missing-capability refusal, which names what was missing so it can be acted on. */
    public static WireResponse missingCapability(Capability required) {
        return new WireResponse(
                false,
                null,
                ErrorCode.MISSING_CAPABILITY,
                "this credential does not hold the capability this operation requires",
                required);
    }
}
