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

    /**
     * The display name every actor is written to core under.
     *
     * <p><b>Four-byte UTF-8, deliberately</b>, drawn from
     * {@code corpus/hostile-inputs.txt}'s astral-plane section — "the classic
     * latin1 tripwire". §11 Tier 6 asks for "astral-plane text from the corpus
     * pushed through the newest text column in every stage", and this is the
     * tier that writes to the most of them.
     *
     * <p>It is also the end-to-end proof of the charset work in 8.18 and 8.24.
     * That work is otherwise asserted by reading {@code information_schema} back
     * — a statement about what the schema DECLARES. This is a statement about
     * what survives a round trip through a real deployment on a server started
     * latin1, which is the claim anybody actually cares about: a person whose
     * name is an emoji can link.
     *
     * <p>Kept short. The column is {@code VARCHAR(191)} and a display name that
     * failed for being too long would look exactly like one that failed for
     * being four-byte.
     */
    private static final String ASTRAL_SUFFIX = " \uD83D\uDE00\uD83E\uDD16";

    @Override
    public String displayFor(Actor actor) {
        return actor.name() + ASTRAL_SUFFIX;
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
                        "display", displayFor(actor)),
                "code");
    }

    @Override
    public Result redeemCode(Actor actor, String code, String platformKind, String platformId) {
        Result result = call(forActor(actor), "code.redeem",
                Map.of("code", code, "platformKind", platformKind,
                        "platformId", platformId, "display", displayFor(actor)),
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
                    item.has("verifiedAtEpochSeconds"),
                    item.has("display") ? item.text("display") : null));
        }
        return Optional.of(new Subject(body.text("subjectId"), identities));
    }

    @Override
    public List<AuditRow> auditSince(long after) {
        // Paged, with the cursor core gained in Phase 10. The first version
        // asked for `limit` and nothing else, because there was no sequence
        // cursor and core's codec refuses unknown properties, so the invented
        // one produced a MALFORMED refusal.
        //
        // That version had a ceiling: MAX_LIMIT is 1000, anything above is
        // clamped, and a run producing more than 1000 audit rows compared its
        // counts against a truncated log and reported a shortfall that was the
        // harness's fault. It detected the ceiling and refused to conclude,
        // which was right, but it also capped how long a run could be. This
        // pages instead, so the length of a run is no longer limited by how
        // much of the log the checker can read.
        List<AuditRow> rows = new ArrayList<>();
        long cursor = after;
        while (true) {
            SoulbindClient.Outcome outcome = admin.call("audit.query",
                    Map.of("limit", 1000, "afterSequence", cursor));
            if (!(outcome instanceof SoulbindClient.Outcome.Ok ok)) {
                // LOUDLY. Returning an empty list here was the first version,
                // and it meant "I could not ask" was indistinguishable from
                // "core has audited nothing" -- so a broken query in the
                // harness reported itself as a missing-audit defect in core.
                // Exactly backwards, and it is what the control caught.
                String why = outcome instanceof SoulbindClient.Outcome.Refused refused
                        ? refused.code() + ": " + refused.message()
                        : ((SoulbindClient.Outcome.Unreachable) outcome).detail();
                throw new IllegalStateException(
                        "could not read the audit log (" + why + "). The run cannot conclude"
                                + " anything about audit completeness without it, and reporting"
                                + " an unreadable log as an empty one would blame core for a"
                                + " fault in this harness.");
            }
            for (Payload item : ok.payload().items("entries")) {
                rows.add(new AuditRow(
                        item.number("sequence"), item.text("actor"),
                        item.text("action"),
                        item.has("subjectId") ? item.text("subjectId") : null));
            }
            if (!ok.payload().flag("more")) {
                break;
            }
            long next = ok.payload().number("lastSequence");
            if (next <= cursor) {
                // Core says there is more but has not advanced the cursor. That
                // is an infinite loop, and the harness hanging is a worse
                // failure report than the harness complaining.
                throw new IllegalStateException(
                        "audit.query reported more rows without advancing the cursor past "
                                + cursor + ". Paging cannot make progress.");
            }
            cursor = next;
        }
        return rows;
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
        // NOTHING is inert against a real core any more, and both exclusions
        // went for different reasons worth keeping apart.
        //
        // `decisions-follow-the-rules` was an OPEN QUESTION (9.10): it fired
        // against unmodified core, and either core was wrong or the tier's
        // model was. 9.11 established the model was stale -- all three seeds
        // shared one identity namespace -- and 9.12 switched it back on and
        // checked it in both directions.
        //
        // `redeemed-codes-stay-redeemed` was a settled narrowing (9.4): there
        // is no operation reporting a code's state, deliberately, since that
        // would be an oracle for guessing codes. The only way to find out is to
        // attempt the redeem, and a CHECKER doing that against a broken core
        // links a phantom identity and corrupts the graph every other invariant
        // asserts about.
        //
        // The way out was never a new operation. It was to stop asking as a
        // checker and start asking as an ACTOR: DOUBLE_REDEEM attempts a spent
        // code deliberately, and because the executor knows the code was spent
        // it knows the answer must be a refusal. An acceptance is recorded as a
        // violation instead of quietly becoming part of the world. Nothing is
        // corrupted by asking a question whose answer you already know.
        return List.of();
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
