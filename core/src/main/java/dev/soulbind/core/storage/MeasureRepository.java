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

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * Named numeric observations connectors report about platform accounts.
 *
 * <p><b>One row per (identityRef, name), overwritten.</b> This is not a time
 * series and must not become one: core computing windows over stored samples
 * would force retention, and retention forces a sweep, and nothing in this
 * system re-evaluates on a timer. The reporter computes its own window and
 * reports the answer; core stores it with enough provenance to judge it.
 *
 * <p><b>There is deliberately no purge method.</b> Two exist elsewhere --
 * {@code PolicyRepository.purgeExpiredOverrides} and
 * {@code LinkCodeRepository.purgeExpired} -- and production calls neither, which
 * is a pile this interface declines to add to. It does not need one: overwriting
 * bounds the table by the number of live (identity, name) pairs, staleness is
 * decided at read time by the engine that owns the expiry rule, and orphans are
 * removed by {@link #forget} inside the unlink that orphans them.
 */
public interface MeasureRepository {

    /**
     * Records an observation, replacing any previous one for this pair.
     *
     * @param observedAt core's clock, never the caller's
     */
    void report(
            String identityRef,
            String name,
            long value,
            long windowSeconds,
            Instant observedAt,
            String reportedBy);

    /**
     * Everything held for these references, optionally narrowed to one name.
     *
     * <p>Takes a collection because the caller already holds the subject's whole
     * identity list, and one query beats one per identity.
     *
     * @param name a single measure, or null for all of them
     */
    List<MeasureRecord> forRefs(Collection<String> identityRefs, String name);

    /**
     * Removes every measure for a reference, and answers how many.
     *
     * <p>Called from the unlink that orphans them. Section 6 of the
     * specification says re-linking an account creates a NEW identity precisely
     * so history is not resurrected -- and a surviving measure row is exactly
     * such a resurrection, an entitlement outliving the identity that earned it.
     */
    int forget(String identityRef);
}
