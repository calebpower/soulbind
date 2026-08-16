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

import dev.soulbind.policy.PolicyOverride;
import dev.soulbind.policy.Rule;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Rules, overrides and the gates they govern. */
public interface PolicyRepository {

    /** Records a gate the first time a connector declares one. Idempotent. */
    void gateSeen(String gateName, String registeredByConnectorId, String description);

    List<String> gates();

    /** The rule governing a gate, or empty when none does. */
    Optional<Rule> rule(String gateName);

    /** Sets the rule for a gate, replacing any existing one. */
    void setRule(Rule rule, Instant at, String updatedVia);

    /** Removes a gate's rule, which makes the gate open. */
    boolean clearRule(String gateName);

    List<Rule> rules();

    /**
     * Every override for a gate, expired ones included.
     *
     * <p>Expired ones are returned rather than filtered, because the engine
     * filters them itself -- and a repository that filtered too would be a
     * second place the expiry rule lives, with its own idea of whether the
     * boundary is inclusive.
     */
    List<PolicyOverride> overridesFor(String gateName);

    /** Adds an override and returns its id. */
    String addOverride(PolicyOverride override, Instant at, String createdBy);

    boolean removeOverride(String overrideId);

    /** Deletes overrides that expired before the given moment. */
    int purgeExpiredOverrides(Instant before);
}
