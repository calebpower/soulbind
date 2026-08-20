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

package dev.soulbind.sim;

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.Payload;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.HttpTransport;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A real core, over the connector SDK.
 *
 * <p>Both sides — {@link CoreDriver} to act, {@link CoreView} to observe —
 * because the tier needs them against the same server and separating them into
 * two objects would mean two connections and two chances to be looking at
 * different states.
 *
 * <p><b>Every actor gets its own client, with its own credential.</b> §11 asks
 * for "independent identities with their own rotating credentials", and a
 * single shared client would make the whole run one principal — which is the
 * one thing a capability model cannot be tested through. It would also make the
 * stale-credential class meaningless.
 *
 * <p><b>Every response passes the cheap oracle.</b> A 5xx or an unparseable
 * envelope is recorded here rather than at the call site, so no invariant has
 * to remember to check and no operation can quietly skip it — that is §11's
 * "nothing can escape the cheap checks, and it costs nothing".
 */
public final class SdkCore implements CoreDriver, CoreView {

    private final Map<String, SoulbindClient> clients = new LinkedHashMap<>();
    private final SoulbindClient admin;
    private final SoulbindClient retired;
    private final List<String> transportComplaints = new ArrayList<>();

    /**
     * @param coreUrl where core is listening
     * @param credentialsByActor one credential per actor name
     * @param adminCredential a credential holding config-management, for rules
     * @param retiredCredential a credential core will refuse; see
     *     {@link CoreDriver#withRetiredCredential}
     */
    public SdkCore(
            String coreUrl,
            Map<String, String> credentialsByActor,
            String adminCredential,
            String retiredCredential) {

        Clock clock = Clock.systemUTC();
        for (Map.Entry<String, String> entry : credentialsByActor.entrySet()) {
            clients.put(entry.getKey(), client(coreUrl, entry.getValue(), clock));
        }
        this.admin = client(coreUrl, adminCredential, clock);
        this.retired = client(coreUrl, retiredCredential, clock);
    }

    private static SoulbindClient client(String coreUrl, String credential, Clock clock) {
        return new SoulbindClient(
                new HttpTransport(coreUrl, credential, clock), credential, clock,
                new DecisionCache());
    }

    private SoulbindClient forActor(Actor actor) {
        SoulbindClient client = clients.get(actor.name());
        if (client == null) {
            throw new IllegalStateException(
                    "no credential for actor '" + actor.name() + "'. Every actor is a"
                            + " separate principal; falling back to a shared one would make"
                            + " the whole run one principal and the capability model"
                            + " untestable.");
        }
        return client;
    }

    /** Calls, recording anything the cheap oracle should hear about. */
    private CoreDriver.Result call(
            SoulbindClient client, String operation, Object payload, String valueField) {

        SoulbindClient.Outcome outcome = client.call(operation, payload);
        if (outcome instanceof SoulbindClient.Outcome.Unreachable unreachable) {
            transportComplaints.add(operation + ": " + unreachable.detail());
            return CoreDriver.Result.refused("unreachable: " + unreachable.detail());
        }
        if (outcome instanceof SoulbindClient.Outcome.Refused refused) {
            // A refusal is an ANSWER, not a fault. Most of them are correct --
            // a spent code, a retired credential, an unlinked identity at a gate
            // that requires one -- and recording them as transport complaints
            // would make the cheap oracle fire on a healthy run.
            return CoreDriver.Result.refused(refused.code() + ": " + refused.message());
        }
        Payload body = ((SoulbindClient.Outcome.Ok) outcome).payload();
        return CoreDriver.Result.ok(
                valueField == null || !body.has(valueField) ? null : body.text(valueField));
    }

    // --- CoreDriver ----------------------------------------------------------

    @Override
    public Result issueCode(Actor actor, String platformKind, String platformId) {
        return call(forActor(actor), "code.issue",
                Map.of("platformKind", platformKind, "platformId", platformId,
                        "display", actor.name()),
                "code");
    }

    @Override
    public Result redeemCode(Actor actor, String code, String platformKind, String platformId) {
        return call(forActor(actor), "code.redeem",
                Map.of("code", code, "platformKind", platformKind,
                        "platformId", platformId, "display", actor.name()),
                "subjectId");
    }

    @Override
    public Result describe(Actor actor, String platformKind, String platformId) {
        return call(forActor(actor), "identity.describe",
                Map.of("platformKind", platformKind, "platformId", platformId), "subjectId");
    }

    @Override
    public Result decide(Actor actor, String gate, String platformKind, String platformId) {
        return call(forActor(actor), "decide",
                Map.of("gate", gate, "platformKind", platformKind, "platformId", platformId),
                "effect");
    }

    @Override
    public Result setRule(Actor actor, String gate, boolean requireLinked) {
        return call(admin, "rule.set",
                Map.of("gate", gate, "requiredKinds", List.of(),
                        "defaultEffect", requireLinked ? "deny" : "allow"),
                "gate");
    }

    @Override
    public Result setConfig(Actor actor, String key, String value) {
        return call(admin, "config.set", Map.of("key", key, "value", value), "key");
    }

    @Override
    public Result withRetiredCredential(Actor actor, String platformKind, String platformId) {
        return call(retired, "identity.describe",
                Map.of("platformKind", platformKind, "platformId", platformId), "subjectId");
    }

    // --- CoreView ------------------------------------------------------------

    @Override
    public Optional<Subject> describe(String platformKind, String platformId) {
        SoulbindClient.Outcome outcome = admin.call("identity.describe",
                Map.of("platformKind", platformKind, "platformId", platformId));
        if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
            if (outcome instanceof SoulbindClient.Outcome.Unreachable unreachable) {
                transportComplaints.add("identity.describe: " + unreachable.detail());
            }
            return Optional.empty();
        }
        Payload body = ok.payload();
        if (!body.has("subjectId")) {
            return Optional.empty();
        }
        List<Identity> identities = new ArrayList<>();
        for (Payload item : body.items("identities")) {
            identities.add(new Identity(
                    item.text("platformKind"), item.text("platformId"),
                    item.has("verifiedAtEpochSeconds")));
        }
        return Optional.of(new Subject(body.text("subjectId"), identities));
    }

    @Override
    public List<AuditRow> auditSince(long after) {
        SoulbindClient.Outcome outcome =
                admin.call("audit.query", Map.of("sinceSequence", after, "limit", 1000));
        if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
            return List.of();
        }
        List<AuditRow> rows = new ArrayList<>();
        for (Payload item : ok.payload().items("entries")) {
            rows.add(new AuditRow(
                    item.number("sequence"), item.text("actor"),
                    item.text("action"), item.has("subjectId") ? item.text("subjectId") : null));
        }
        return rows;
    }

    @Override
    public boolean codeRedeemable(String code) {
        // NOT probed, and this method is inert against a real core -- see
        // inertInvariants() below for why, and for why saying so is better than
        // answering.
        return false;
    }

    @Override
    public List<String> inertInvariants() {
        return List.of("redeemed-codes-stay-redeemed: no non-mutating way to ask a real core"
                + " whether a code is still redeemable. Attempting the redeem IS the check,"
                + " and against a broken core it would link a phantom identity and corrupt"
                + " the graph the rest of the run asserts about. Single use is proven under"
                + " real concurrency by the Phase 2 gate, which is the stronger test;"
                + " DECISIONS 9.4.");
    }

    @Override
    public boolean reachable() {
        return transportComplaints.isEmpty();
    }

    @Override
    public List<String> transportComplaints() {
        return List.copyOf(transportComplaints);
    }
}
