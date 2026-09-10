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

package dev.soulbind.connector.plan.playtime;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

/**
 * How engaged a player is, and who is worth asking about.
 *
 * <p><b>Everything returns an empty optional rather than a zero when it cannot
 * answer, and that is the whole design of this interface.</b> A source that
 * answered zero on failure would be indistinguishable from one describing
 * somebody who has stopped playing — and the consequence is not a missing grant
 * but a mass revocation: every holder dropping to the floor at once, on the
 * sweep after somebody renames a column.
 *
 * <p>Empty means "I could not answer", never "the answer is none".
 */
public interface ActivitySource {

    /**
     * The dashboard's own activity index for a player, as of an instant.
     *
     * <p>Deliberately the HOST's number rather than one computed here. It
     * considers three separate weeks, curves each against a playtime threshold
     * so that returns diminish, and averages them — so consistency scores above
     * bingeing, and somebody who stops slides down over three weeks instead of
     * falling off a cliff when a window rolls past. Reimplementing that here
     * would be a second definition of "active" that drifts from the one every
     * dashboard page already shows.
     *
     * @return the index, 0 to 5, or empty if it could not be determined
     */
    OptionalDouble indexOf(UUID player, Instant at);

    /**
     * Everyone with a recorded session since an instant.
     *
     * <p>Half of the population a sweep must cover. The other half — everyone
     * last reported above the floor — is the caller's, because only the caller
     * knows what it said last time.
     *
     * @return the players, or empty if they could not be determined
     */
    Optional<Set<UUID>> playersSeenSince(Instant since);
}
