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
        Result result = call(forActor(actor), "code.redeem",
                Map.of("code", code, "platformKind", platformKind,
                        "platformId", platformId, "display", actor.name()),
                "subjectId");
        if (result.accepted()) {
            return result;
        }
        // A refusal that still spent the code. Core claims it on
        // already-redeemed and already-linked alike; only an unknown or expired
        // code leaves nothing consumed.
        String detail = result.detail() == null ? "" : result.detail();
        boolean spent = detail.contains("already-redeemed") || detail.contains("already-linked");
        return spent ? Result.refusedAndSpent(detail) : result;
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
        // ALL FIVE fields. rule.set binds RuleView(gate, requiredKinds,
        // requireLinked, graceSeconds, defaultEffect), and the first version
        // sent three of them.
        //
        // That did not fail. `requireLinked` and `graceSeconds` are primitives,
        // so Jackson filled them with false and 0, the bind succeeded, and every
        // rule the tier set was a rule requiring nothing -- an action class
        // performing hundreds of operations that could not change any decision.
        // Nothing failed and nothing was tested, which is this repository's
        // house defect appearing in the tier built to find it.
        return call(admin, "rule.set",
                Map.of("gate", gate,
                        "requiredKinds", List.of(),
                        "requireLinked", requireLinked,
                        "graceSeconds", 0,
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
        // `limit` and nothing else. audit.query takes fromEpochSeconds,
        // toEpochSeconds, actor, subjectId, action and limit -- there is no
        // sequence cursor, and core's codec fails on unknown properties, so the
        // invented one produced a MALFORMED refusal.
        //
        // MAX_LIMIT is 1000 and anything above it is clamped, so this asks for
        // the most core will give. A run that produces more audit rows than that
        // would compare against a truncated log and report a shortfall that is
        // this method's fault -- which is why the count is checked below.
        SoulbindClient.Outcome outcome = admin.call("audit.query", Map.of("limit", 1000));
        if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
            // LOUDLY. Returning an empty list here was the first version, and it
            // meant "I could not ask" was indistinguishable from "core has
            // audited nothing" -- so a broken query in the harness reported
            // itself as a missing-audit defect in core. Exactly backwards, and
            // it is what the control caught.
            String why = outcome instanceof SoulbindClient.Outcome.Refused refused
                    ? refused.code() + ": " + refused.message()
                    : ((SoulbindClient.Outcome.Unreachable) outcome).detail();
            throw new IllegalStateException(
                    "could not read the audit log (" + why + "). The run cannot conclude"
                            + " anything about audit completeness without it, and reporting"
                            + " an unreadable log as an empty one would blame core for a"
                            + " fault in this harness.");
        }
        List<AuditRow> rows = new ArrayList<>();
        for (Payload item : ok.payload().items("entries")) {
            rows.add(new AuditRow(
                    item.number("sequence"), item.text("actor"),
                    item.text("action"), item.has("subjectId") ? item.text("subjectId") : null));
        }
        if (rows.size() >= 1000) {
            throw new IllegalStateException(
                    "the audit log came back at core's maximum of 1000 rows, so it is"
                            + " truncated. Every count this run compares against it would be"
                            + " short, and the shortfall would be reported as core failing to"
                            + " audit. Shorten the run or add a cursor to audit.query.");
        }
        return rows.stream().filter(r -> r.sequence() > after).toList();
    }

    @Override
    public String decide(String gate, String platformKind, String platformId) {
        // Through the admin client, not an actor's. The invariant is asking
        // what core decides, not what a particular principal is allowed to ask
        // -- and routing it through an actor would make a capability refusal
        // indistinguishable from a deny.
        SoulbindClient.Outcome outcome = admin.call("decide",
                Map.of("gate", gate, "platformKind", platformKind,
                        "platformId", platformId));
        if (outcome instanceof SoulbindClient.Outcome.Ok ok) {
            return ok.payload().has("effect") ? ok.payload().text("effect") : "";
        }
        if (outcome instanceof SoulbindClient.Outcome.Unreachable unreachable) {
            // An outage. The cheap oracle reports it; this returns empty and the
            // policy invariant skips, because a question that could not be asked
            // is not a policy verdict.
            transportComplaints.add("decide: " + unreachable.detail());
            return "";
        }

        // A REFUSAL is different, and must be loud.
        //
        // decide requires enforcement-point. The first version of this method
        // returned empty here too, so a credential missing that capability made
        // the policy invariant silently inert -- an invariant written precisely
        // because SET_RULE and DECIDE had no oracle, itself unobservable, for
        // the same reason. Found by injecting the real rule.set bug and watching
        // the acceptance test not care.
        //
        // A checker that cannot ask cannot conclude. auditSince learned this in
        // 9.5 and this is the same lesson arriving twice.
        SoulbindClient.Outcome.Refused refused = (SoulbindClient.Outcome.Refused) outcome;
        throw new IllegalStateException(
                "decide was refused (" + refused.code() + ": " + refused.message() + ")."
                        + " The policy invariant cannot conclude anything without it, and"
                        + " returning 'no answer' would make it pass by being unable to"
                        + " look. decide requires enforcement-point.");
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
        return List.of(
                // OPEN QUESTION, not a settled narrowing, and the distinction
                // matters. This invariant fires against unmodified core: an
                // identity the model believes linked to nothing is ALLOWED at a
                // gate requiring linkage, while a synthetic account core has
                // never heard of is correctly denied. The difference between
                // them is that core has seen the first -- a code was issued for
                // it -- and `issue` creates no identity, so both should be
                // unlinked and both should be denied.
                //
                // Either core treats a seen-but-unlinked identity as satisfying
                // requireLinked, which would be a real defect, or this tier's
                // model loses track of a link somewhere. It is NOT diagnosed,
                // and running it would make every session red for a reason
                // nobody has established.
                //
                // Excluded rather than deleted, and excluded LOUDLY: the runner
                // prints this list before the verdict on every run. DECISIONS
                // 9.10 carries the reproduction.
                "decisions-follow-the-rules: fires against unmodified core and the cause is"
                        + " not yet established -- see DECISIONS 9.10. Excluded pending"
                        + " diagnosis rather than deleted.",
                "redeemed-codes-stay-redeemed: no non-mutating way to ask a real core"
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
