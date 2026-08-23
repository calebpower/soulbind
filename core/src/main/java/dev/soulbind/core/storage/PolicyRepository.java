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

    /**
     * Records a gate the first time a connector declares one. Idempotent.
     *
     * <p><b>{@code description} is applied only when one is supplied.</b> This
     * is called on the {@code decide} path, on every permission check, with no
     * description -- because a connector asking about a gate knows the gate's
     * name and nothing about what an operator wants written down. If a null
     * were allowed to overwrite, the first check after somebody documented a
     * gate would erase it, and the erasure would look like the note was never
     * saved.
     *
     * <p><b>{@code registeredByConnectorId} is never updated either</b>, for a
     * different reason: it means "who first declared this", and rewriting it on
     * each later declaration would quietly redefine it as "who asked most
     * recently".
     */
    void gateSeen(String gateName, String registeredByConnectorId, String description);

    List<String> gates();

    /**
     * A gate's own row -- who declared it and what it is for -- or empty when
     * no connector has ever named it.
     *
     * <p>Separate from {@link #rule(String)} because a gate and its rule are
     * different things with different lifetimes: clearing a rule leaves the
     * gate, and its description, exactly where they were.
     */
    Optional<GateRecord> gate(String gateName);

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

    /**
     * Removes every override on a gate for one target.
     *
     * <p>By TARGET, not by id, and that is the operator-facing shape for the
     * same reason {@code connector.rotate} takes a name: an operator knows the
     * gate and who they admitted, not a uuid this system never showed them.
     * {@code override.get} does not return ids at all, so removal by id was
     * unreachable over the protocol — see DECISIONS 10.26.
     *
     * <p>Exactly one of {@code subjectId} or {@code identityRef} is meaningful,
     * matching what {@code PolicyOverride} enforces on the way in. Both null
     * removes nothing rather than removing everything on the gate, which is the
     * one mistake here that could not be undone.
     *
     * @return how many were removed
     */
    int removeOverridesFor(String gateName, String subjectId, String identityRef);

    /** Deletes overrides that expired before the given moment. */
    int purgeExpiredOverrides(Instant before);
}
