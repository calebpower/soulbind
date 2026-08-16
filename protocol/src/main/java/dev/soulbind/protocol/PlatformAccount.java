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
 * A platform account, as a connector names it.
 *
 * <p>Core does not parse {@code id}, does not validate its shape, and does not
 * know what any particular platform's identifiers look like. It is an opaque
 * string paired with an opaque kind, and keeping it that way is what lets a new
 * platform arrive without a dispatcher change.
 *
 * @param kind the platform kind, learned at registration
 * @param id the account's identifier, as the connector presents it
 * @param display a human-readable name, for operators reading audit. Never used
 *     as an identifier: display names change, and treating one as stable is how
 *     a rename silently reassigns an entitlement.
 */
public record PlatformAccount(String kind, String id, String display) {

    public PlatformAccount {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("platform kind must not be empty");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("platform id must not be empty");
        }
    }

    /** {@code kind:id}, the form audit and refusals use. */
    public String ref() {
        return kind + ":" + id;
    }
}
