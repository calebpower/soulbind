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

package dev.soulbind.core.storage;

import dev.soulbind.core.identity.LinkCodeRecord;
import java.time.Instant;
import java.util.Optional;

/** Link code issue and redeem. */
public interface LinkCodeRepository {

    /** Stores a freshly issued code. The code given must already be normalised. */
    void issue(LinkCodeRecord code);

    Optional<LinkCodeRecord> find(String normalisedCode);

    /**
     * Claims a code for exactly one caller.
     *
     * <p><b>This is the single-use mechanism, and it is one statement.</b> The
     * update carries its own predicate — {@code SET redeemed_at = ?, ...
     * WHERE code = ? AND redeemed_at IS NULL} — so the database decides the
     * winner. One row updated means this caller claimed it; zero means somebody
     * else already had, or the code does not exist.
     *
     * <p>A read-then-write would race, and the race is not theoretical: two
     * people typing the same code within the same second is precisely what
     * happens when a code leaks. It would also depend on an isolation level
     * that differs between the two backends, so the version that worked in
     * testing would be the version that failed in deployment.
     *
     * <p>Expiry is deliberately NOT part of the predicate. A caller redeeming an
     * expired code should be told it expired, not told it was already used —
     * those are different problems with different fixes, and collapsing them
     * sends the person to ask the wrong question.
     *
     * @return true if this caller claimed it
     */
    boolean claim(String normalisedCode, String redeemedByConnector, Instant at);

    /**
     * Deletes codes that expired before the given moment.
     *
     * <p>Not a retention policy for anything that matters: an unredeemed code is
     * a secret nobody used, and keeping it forever is a slowly growing table of
     * live-looking secrets. What happened is in audit, which this does not
     * touch.
     *
     * @return how many were removed
     */
    int purgeExpired(Instant before);
}
