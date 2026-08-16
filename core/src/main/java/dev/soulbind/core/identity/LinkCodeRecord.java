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

package dev.soulbind.core.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A code issued for one platform account, redeemable once.
 *
 * <p>{@code code} is the <b>normalised</b> form, and it is the only form ever
 * stored or compared. Keeping what the user typed and normalising on read would
 * let two codes differing only in case or separators both exist, and the
 * collision would show up as a redeem that silently linked the wrong account.
 *
 * <p>Single use is enforced by the storage layer as an UPDATE carrying its own
 * predicate — {@code SET redeemed_at = ? WHERE code = ? AND redeemed_at IS
 * NULL}. One row updated means this caller redeemed it; zero means somebody
 * else already had. No lock, no read-then-write, and no dependence on an
 * isolation level that differs between backends.
 */
public record LinkCodeRecord(
        String code,
        String issuedByConnector,
        String issuedForKind,
        String issuedForId,
        String issuedForDisplay,
        Instant issuedAt,
        Instant expiresAt,
        Instant redeemedAt,
        String redeemedByConnector) {

    public LinkCodeRecord {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(issuedByConnector, "issuedByConnector");
        Objects.requireNonNull(issuedForKind, "issuedForKind");
        Objects.requireNonNull(issuedForId, "issuedForId");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean isRedeemed() {
        return redeemedAt != null;
    }

    /**
     * Whether the code has expired at the given moment.
     *
     * <p>The instant is a parameter, never {@code Instant.now()} read inside.
     * A TTL boundary that consults a hidden clock cannot be tested at its edges,
     * and the edges are the only interesting part of a TTL.
     */
    public boolean isExpired(Instant at) {
        // Exclusive: a code expiring exactly now is still good. Somebody typing
        // at the last second should succeed, and "expired" reading true at the
        // stroke of the deadline makes the advertised lifetime a lie by one
        // second.
        return at.isAfter(expiresAt);
    }

    /** Usable means: not redeemed, and not expired. */
    public boolean isUsable(Instant at) {
        return !isRedeemed() && !isExpired(at);
    }

    public Optional<String> redeemedBy() {
        return Optional.ofNullable(redeemedByConnector);
    }

    /** {@code kind:id} of the account this code was issued for. */
    public String issuedForRef() {
        return issuedForKind + ":" + issuedForId;
    }
}
