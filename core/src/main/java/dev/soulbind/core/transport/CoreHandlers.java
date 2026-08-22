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

package dev.soulbind.core.transport;

import dev.soulbind.core.registry.Authorizer.Operation;
import dev.soulbind.core.registry.Credentials;
import dev.soulbind.core.audit.AuditEntry;
import dev.soulbind.core.audit.AuditPage;
import dev.soulbind.core.audit.AuditQuery;
import dev.soulbind.core.events.EventRecord;
import dev.soulbind.core.identity.Identity;
import dev.soulbind.core.identity.LinkCodeRecord;
import dev.soulbind.core.identity.LinkingService;
import dev.soulbind.core.identity.RedeemThrottle;
import dev.soulbind.core.policy.GateEvaluator;
import dev.soulbind.core.policy.GateTransitions;
import dev.soulbind.core.storage.AuditRepository;
import dev.soulbind.core.storage.ConnectorRepository;
import dev.soulbind.core.storage.IdentityRepository;
import dev.soulbind.core.storage.EventRepository;
import dev.soulbind.core.storage.PolicyRepository;
import dev.soulbind.core.storage.RuntimeConfigRepository;
import dev.soulbind.protocol.AttestRequest;
import dev.soulbind.protocol.AuditEntryView;
import dev.soulbind.protocol.AuditPushRequest;
import dev.soulbind.protocol.AuditQueryRequest;
import dev.soulbind.policy.Decision;
import dev.soulbind.policy.Effect;
import dev.soulbind.policy.PolicyOverride;
import dev.soulbind.policy.Rule;
import dev.soulbind.policy.PolicyEngine;
import dev.soulbind.policy.SubjectSnapshot;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.CodeIssueRequest;
import dev.soulbind.protocol.CodeIssueResponse;
import dev.soulbind.protocol.CodeRedeemRequest;
import dev.soulbind.protocol.CodeRedeemResponse;
import dev.soulbind.protocol.DecideRequest;
import dev.soulbind.protocol.DecideResponse;
import dev.soulbind.protocol.EventAckRequest;
import dev.soulbind.protocol.EventPollRequest;
import dev.soulbind.protocol.EventPollResponse;
import dev.soulbind.protocol.EventView;
import dev.soulbind.protocol.EventType;
import dev.soulbind.protocol.IdentityView;
import dev.soulbind.protocol.OverrideView;
import dev.soulbind.protocol.RuleView;
import dev.soulbind.protocol.SubjectInspectRequest;
import dev.soulbind.protocol.UnlinkRequest;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.HelloRequest;
import dev.soulbind.protocol.HelloResponse;
import dev.soulbind.protocol.HeartbeatResponse;
import dev.soulbind.protocol.SchemaVersion;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.TreeSet;

/** The operations core implements at this phase. */
public final class CoreHandlers {

    private CoreHandlers() {
        throw new AssertionError("no instances");
    }

    /**
     * Builds the handler table.
     *
     * <p>Keyed by {@link Operation}, so an operation that exists in the
     * authorization table but not here is reported as unimplemented rather than
     * as unknown — the distinction matters to whoever is debugging it.
     */
    public static Map<Operation, Dispatcher.Handler> build(
            ConnectorRepository connectors,
            AuditRepository audit,
            IdentityRepository identities,
            PolicyRepository policy,
            EventRepository events,
            RuntimeConfigRepository runtimeConfig,
            LinkingService linking,
            GateEvaluator gateEvaluator,
            RedeemThrottle throttle,
            Codec codec,
            Clock clock,
            int signatureWindowSeconds) {

        Map<Operation, Dispatcher.Handler> handlers = new LinkedHashMap<>();

        // The same emitter LinkingService uses, over the same evaluator. Not a
        // second notion of "what an effector is told": one definition, two
        // callers, which is the whole point of GateTransitions.
        GateTransitions transitions = new GateTransitions(
                new dev.soulbind.core.events.EventEmitter(events, clock),
                identities,
                gateEvaluator);

        handlers.put(Operation.HELLO, (connector, payload) -> {
            var request = codec.bind(payload, HelloRequest.class);
            if (request.isEmpty()) {
                return WireResponse.error(
                        ErrorCode.MALFORMED, "hello payload could not be read");
            }

            // The intersection, not the claim. A connector saying it can do
            // something does not make it so; core answers with what the
            // credential was actually granted, so the connector learns the truth
            // at handshake rather than discovering it one refusal at a time.
            Set<Capability> granted = new TreeSet<>(request.get().parsedCapabilities());
            granted.retainAll(connector.capabilities());

            connectors.touchLastSeen(connector.id(), clock.instant());

            return WireResponse.ok(new HelloResponse(
                    SchemaVersion.CURRENT,
                    connector.id(),
                    granted.stream().map(Capability::wireName).toList(),
                    request.get().unrecognisedCapabilities(),
                    clock.instant().getEpochSecond()));
        });

        handlers.put(Operation.HEARTBEAT, (connector, payload) -> {
            // Deliberately a write to liveness and nothing else. A heartbeat
            // that touched identity would make "when did we last hear from it"
            // and "what is it allowed to do" the same row, and a flapping
            // connector would rewrite its own permissions.
            connectors.touchLastSeen(connector.id(), clock.instant());
            return WireResponse.ok(new HeartbeatResponse(
                    clock.instant().getEpochSecond(), signatureWindowSeconds));
        });

        handlers.put(Operation.CONNECTOR_ROTATE, (connector, payload) -> {
            var request = codec.bind(payload, ConnectorRotateRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.CONNECTOR_ROTATE, ConnectorRotateRequest.class);
            }
            String name = request.get().name();
            if (name == null || name.isBlank()) {
                return WireResponse.error(
                        ErrorCode.MALFORMED, "which connector to rotate must be named");
            }

            var target = connectors.findByName(name);
            if (target.isEmpty()) {
                // Named rather than "not found": an operator rotating a
                // credential in a hurry has usually mistyped, and the useful
                // answer says which name did not exist.
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "no connector is registered as '" + name + "'");
            }

            Credentials.Minted minted = Credentials.mint();
            if (!connectors.rotateCredential(target.get().id(), minted.hash())) {
                return WireResponse.error(
                        ErrorCode.INTERNAL, "the connector vanished while rotating it");
            }

            // Audited BEFORE the plaintext goes out. A rotation that reached the
            // caller and never reached the log is a credential change nobody can
            // account for afterwards -- and this is the operation most likely to
            // be performed during an incident, which is exactly when the log is
            // read.
            audit.append(new AuditEntry(
                    0L, clock.instant(),
                    "connector:" + connector.id(),
                    "connector.rotated",
                    null, null, null,
                    Map.of("connector", name, "connectorId", target.get().id())));

            // The plaintext is returned ONCE and stored nowhere, exactly as at
            // registration. Core keeps the hash; if this response is lost, the
            // remedy is another rotation.
            return WireResponse.ok(Map.of(
                    "connector", name,
                    "credential", minted.plaintext()));
        });

        handlers.put(Operation.CONNECTOR_LIST, (connector, payload) ->
                WireResponse.ok(Map.of("connectors", connectors.list().stream()
                        .map(c -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("id", c.id());
                            row.put("name", c.name());
                            row.put("status", c.status().name().toLowerCase(java.util.Locale.ROOT));
                            row.put("capabilities",
                                    c.capabilities().stream().map(Capability::wireName).sorted()
                                            .toList());
                            row.put("registeredAt", c.registeredAt().toString());
                            row.put("lastSeenAt",
                                    c.lastSeenAt() == null ? null : c.lastSeenAt().toString());
                            // No credential hash. It is not a secret in the sense
                            // the plaintext is, but it is the thing an attacker
                            // would want to confirm a guess against, and nobody
                            // reading a connector list needs it.
                            return row;
                        })
                        .toList())));

        handlers.put(Operation.AUDIT_PUSH, (connector, payload) -> {
            var request = codec.bind(payload, AuditPushRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.AUDIT_PUSH, AuditPushRequest.class);
            }
            if (request.get().action() == null
                    || request.get().action().isBlank()) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "an audit entry must name an action");
            }

            // The actor is the CONNECTOR, decided here, never taken from the
            // payload -- which is why AuditPushRequest has no actor field at
            // all. A connector able to name its own actor could attribute its
            // actions to another connector, or to a person, and an audit log
            // whose attribution the subject controls is not evidence.
            AuditEntry appended = audit.append(new AuditEntry(
                    0L,
                    clock.instant(),
                    "connector:" + connector.id(),
                    request.get().action(),
                    request.get().subjectId(),
                    request.get().identityRef(),
                    request.get().gate(),
                    request.get().detail()));

            return WireResponse.ok(Map.of("sequence", appended.sequence()));
        });

        handlers.put(Operation.AUDIT_QUERY, (connector, payload) -> {
            var request = codec.bind(payload, AuditQueryRequest.class);
            if (request.isEmpty()) {
                return WireResponse.error(
                        ErrorCode.MALFORMED, "audit query could not be read");
            }
            AuditQueryRequest q = request.get();

            // The limit is bounded by AuditQuery whatever arrives here, and the
            // bounding is deliberately NOT done in this handler: putting it in
            // the query type means every caller of the repository gets it,
            // including ones written later that forget to ask.
            AuditPage page = audit.page(new AuditQuery(
                    q.fromEpochSeconds() == null
                            ? null : Instant.ofEpochSecond(q.fromEpochSeconds()),
                    q.toEpochSeconds() == null
                            ? null : Instant.ofEpochSecond(q.toEpochSeconds()),
                    q.actor(),
                    q.subjectId(),
                    q.action(),
                    q.limit() == null ? AuditQuery.DEFAULT_LIMIT : q.limit(),
                    q.afterSequence()));

            // "more" and "lastSequence" are on EVERY audit response, not only
            // exports. Without them a caller cannot tell the whole log from the
            // first page of it, and the limit is silently clamped at
            // AuditQuery.MAX_LIMIT, so asking for everything and believing the
            // answer was the easiest mistake this operation offered. Together
            // they are the export: pass lastSequence back as afterSequence
            // until more is false.
            return WireResponse.ok(Map.of(
                    "entries",
                    page.entries().stream().map(CoreHandlers::toView).toList(),
                    "more", page.more(),
                    "lastSequence", page.lastSequence()));
        });

        handlers.put(Operation.CODE_ISSUE, (connector, payload) -> {
            var request = codec.bind(payload, CodeIssueRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.CODE_ISSUE, CodeIssueRequest.class);
            }
            if (blank(request.get().platformKind())
                    || blank(request.get().platformId())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST,
                        "a code is issued for a platform account: give kind and id");
            }
            LinkCodeRecord issued = linking.issue(
                    connector.id(),
                    request.get().platformKind(),
                    request.get().platformId(),
                    request.get().display());

            return WireResponse.ok(new CodeIssueResponse(
                    issued.code(), issued.expiresAt().getEpochSecond()));
        });

        handlers.put(Operation.CODE_REDEEM, (connector, payload) -> {
            var request = codec.bind(payload, CodeRedeemRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.CODE_REDEEM, CodeRedeemRequest.class);
            }
            if (blank(request.get().platformKind())
                    || blank(request.get().platformId())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST,
                        "a redeem needs the code and the account redeeming it");
            }

            // BEFORE the redeem, and keyed on the account rather than the
            // connector: throttling the connector would punish everybody on a
            // platform for one abuser. See RedeemThrottle for why only
            // "no such code" counts as a guess.
            String redeemingRef =
                    request.get().platformKind() + ":" + request.get().platformId();
            if (!throttle.allow(redeemingRef, clock.instant())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST,
                        "too many wrong codes from this account; wait "
                                + throttle.window().toMinutes()
                                + " minutes and try again. If you are typing a code you were"
                                + " given, ask for a fresh one -- the old one may have"
                                + " expired.");
            }

            LinkingService.Result result = linking.redeem(
                    connector.id(),
                    request.get().code(),
                    request.get().platformKind(),
                    request.get().platformId(),
                    request.get().display());

            if (result instanceof LinkingService.Result.Denied refusal
                    && refusal.refusal() == LinkingService.Refusal.UNKNOWN_CODE) {
                // ONLY this refusal. Expired, already-redeemed and
                // already-linked all mean the caller had a real code and
                // something else was wrong; counting those would throttle
                // people who are not guessing at all.
                throttle.recordGuess(redeemingRef, clock.instant());
            } else if (result instanceof LinkingService.Result.Linked) {
                // Somebody who mistypes twice and then gets it right is not a
                // threat, and carrying their failures forward would eventually
                // lock out a person for being human.
                throttle.clear(redeemingRef);
            }

            if (result instanceof LinkingService.Result.Denied denied) {
                // The refusal reason travels as the message, and the code is
                // INVALID_REQUEST rather than a bespoke one per refusal: the
                // caller's action is the same in every case -- tell the person
                // -- and a protocol code per refusal would be a vocabulary the
                // PHP side has to mirror for no gain.
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST,
                        denied.refusal().name().toLowerCase(java.util.Locale.ROOT)
                                .replace('_', '-') + ": " + denied.detail());
            }

            LinkingService.Result.Linked linked = (LinkingService.Result.Linked) result;
            return WireResponse.ok(new CodeRedeemResponse(
                    linked.subject().id(), viewsOf(identities, linked.subject().id())));
        });

        handlers.put(Operation.ATTEST, (connector, payload) -> {
            var request = codec.bind(payload, AttestRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.ATTEST, AttestRequest.class);
            }
            if (blank(request.get().platformKind())
                    || blank(request.get().platformId())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "attest names a platform account");
            }
            var attested = linking.attest(
                    connector.id(),
                    request.get().platformKind(),
                    request.get().platformId(),
                    request.get().display(),
                    request.get().proofMethod());

            return WireResponse.ok(new IdentityView(
                    attested.platformKind(),
                    attested.platformId(),
                    attested.display(),
                    attested.flags(),
                    attested.proofMethod(),
                    attested.verifiedAt() == null ? null : attested.verifiedAt().getEpochSecond(),
                    attested.createdAt().getEpochSecond()));
        });

        handlers.put(Operation.IDENTITY_UNLINK, (connector, payload) -> {
            var request = codec.bind(payload, UnlinkRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.IDENTITY_UNLINK, UnlinkRequest.class);
            }
            if (blank(request.get().platformKind())
                    || blank(request.get().platformId())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "unlink names a platform account");
            }
            boolean removed = linking.unlink(
                    connector.id(), request.get().platformKind(), request.get().platformId());
            return WireResponse.ok(Map.of("removed", removed));
        });

        handlers.put(Operation.SUBJECT_INSPECT, (connector, payload) -> {
            var request = codec.bind(payload, SubjectInspectRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.SUBJECT_INSPECT, SubjectInspectRequest.class);
            }
            if (blank(request.get().platformKind())
                    || blank(request.get().platformId())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "inspect names a platform account");
            }
            var subject = identities.subjectOf(
                    request.get().platformKind(), request.get().platformId());
            if (subject.isEmpty()) {
                return WireResponse.ok(Map.of("linked", false));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("linked", true);
            body.put("subjectId", subject.get().id());
            body.put("status", subject.get().status().name()
                    .toLowerCase(java.util.Locale.ROOT));
            body.put("identities", viewsOf(identities, subject.get().id()));
            return WireResponse.ok(body);
        });

        handlers.put(Operation.DECIDE, (connector, payload) -> {
            var request = codec.bind(payload, DecideRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.DECIDE, DecideRequest.class);
            }
            if (blank(request.get().gate())
                    || blank(request.get().platformKind())
                    || blank(request.get().platformId())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "decide needs a gate and a platform account");
            }
            DecideRequest q = request.get();

            // Recorded on first use, like platform kinds. A connector asking
            // about a gate is a connector declaring that the gate exists, and
            // an operator cannot write a rule for a gate they cannot see.
            policy.gateSeen(q.gate(), connector.id(), null);

            // The SAME snapshot the requirements-met emission is computed
            // from. It used to be built here, and a second copy of this is how
            // an effector comes to grant a role for a gate that decide refuses.
            SubjectSnapshot snapshot =
                    gateEvaluator.snapshotFor(q.platformKind(), q.platformId());

            Decision decision = PolicyEngine.decide(
                    snapshot,
                    policy.rule(q.gate()).orElse(null),
                    policy.overridesFor(q.gate()),
                    clock.instant());

            return WireResponse.ok(new DecideResponse(
                    decision.effect().wireName(),
                    decision.reason().wireName(),
                    decision.detail(),
                    decision.ttlSeconds(),
                    decision.missingKinds()));
        });

        handlers.put(Operation.EVENT_SUBSCRIBE, (connector, payload) -> {
            var request = codec.bind(payload, EventPollRequest.class);
            if (request.isEmpty()) {
                return WireResponse.error(ErrorCode.MALFORMED, "poll request could not be read");
            }

            long from = request.get().after() == null
                    ? events.cursorOf(connector.id())
                    : Math.max(0L, request.get().after());

            int limit = effectivePageSize(request.get().limit());

            List<EventRecord> page = events.after(from, limit);

            // The cursor is NOT advanced here. Advancing on send would turn a
            // delivery lost in flight into an event nobody ever receives, which
            // is the whole failure the outbox exists to prevent.
            return WireResponse.ok(new EventPollResponse(
                    page.stream().map(CoreHandlers::toView).toList(),
                    events.cursorOf(connector.id()),
                    events.highestSequence()));
        });

        handlers.put(Operation.EVENT_ACK, (connector, payload) -> {
            var request = codec.bind(payload, EventAckRequest.class);
            if (request.isEmpty()) {
                return WireResponse.error(ErrorCode.MALFORMED, "ack could not be read");
            }
            long position = events.acknowledge(
                    connector.id(), request.get().through(), clock.instant());
            return WireResponse.ok(Map.of("cursor", position));
        });

        handlers.put(Operation.RULE_GET, (connector, payload) -> {
            var request = codec.bind(payload, GateRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.RULE_GET, GateRequest.class);
            }
            if (blank(request.get().gate())) {
                return WireResponse.error(ErrorCode.INVALID_REQUEST, "rule.get names a gate");
            }
            return policy.rule(request.get().gate())
                    .map(r -> WireResponse.ok(toView(r)))
                    // No rule is not an error: a gate nobody configured is a
                    // gate nobody asked for, and reporting that as a failure
                    // would make "is this gate governed?" unanswerable without
                    // catching something.
                    .orElseGet(() -> WireResponse.ok(Map.of("gate", request.get().gate(),
                            "configured", false)));
        });

        handlers.put(Operation.RULE_SET, (connector, payload) -> {
            var request = codec.bind(payload, RuleView.class);
            if (request.isEmpty()) {
                return unreadable(Operation.RULE_SET, RuleView.class);
            }
            if (blank(request.get().gate())) {
                return WireResponse.error(ErrorCode.INVALID_REQUEST, "rule.set names a gate");
            }
            RuleView view = request.get();

            Effect effect = Effect.fromConfigName(view.defaultEffect())
                    // An unreadable effect DENIES. A rule this build cannot
                    // parse must not open a gate, and refusing the write
                    // outright would leave the operator unable to fix a gate
                    // that is currently wrong.
                    .orElse(Effect.DENY);

            Rule rule;
            try {
                rule = new Rule(
                        view.gate(),
                        Set.copyOf(view.requiredKinds()),
                        view.requireLinked(),
                        view.graceSeconds(),
                        effect);
            } catch (IllegalArgumentException e) {
                return WireResponse.error(ErrorCode.INVALID_REQUEST, e.getMessage());
            }

            policy.gateSeen(view.gate(), connector.id(), null);
            policy.setRule(rule, clock.instant(), "connector:" + connector.id());

            audit.append(new AuditEntry(
                    0L, clock.instant(), "connector:" + connector.id(), "rule.changed",
                    null, null, view.gate(),
                    Map.of("requiredKinds", view.requiredKinds(),
                            "requireLinked", view.requireLinked(),
                            "graceSeconds", view.graceSeconds(),
                            "defaultEffect", effect.wireName())));

            // Emitted so connectors caching decisions for this gate learn their
            // cache is suspect. Without it a rule change takes effect only as
            // fast as the shortest TTL, which is not what an operator pressing
            // enter believes they just did.
            events.append(EventRecord.of(
                    EventType.RULE_CHANGED, null, null, view.gate(), Map.of(),
                    clock.instant()));

            return WireResponse.ok(toView(rule));
        });

        handlers.put(Operation.OVERRIDE_GET, (connector, payload) -> {
            var request = codec.bind(payload, GateRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.OVERRIDE_GET, GateRequest.class);
            }
            if (blank(request.get().gate())) {
                return WireResponse.error(ErrorCode.INVALID_REQUEST, "override.get names a gate");
            }
            return WireResponse.ok(Map.of(
                    "overrides",
                    policy.overridesFor(request.get().gate()).stream()
                            .map(CoreHandlers::toView).toList()));
        });

        handlers.put(Operation.OVERRIDE_SET, (connector, payload) -> {
            var request = codec.bind(payload, OverrideView.class);
            if (request.isEmpty()) {
                return unreadable(Operation.OVERRIDE_SET, OverrideView.class);
            }
            if (blank(request.get().gate())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "override.set names a gate");
            }
            OverrideView view = request.get();

            PolicyOverride override;
            try {
                override = new PolicyOverride(
                        view.gate(),
                        blank(view.subjectId()) ? null : view.subjectId(),
                        blank(view.identityRef()) ? null : view.identityRef(),
                        Effect.fromConfigName(view.effect()).orElse(Effect.DENY),
                        view.reason(),
                        view.expiresAtEpochSeconds() == null
                                ? null
                                : Instant.ofEpochSecond(view.expiresAtEpochSeconds()));
            } catch (IllegalArgumentException e) {
                // The record's own constructor enforces "a reason" and "exactly
                // one target". Reporting its message means the operator is told
                // which rule they broke rather than that something was invalid.
                return WireResponse.error(ErrorCode.INVALID_REQUEST, e.getMessage());
            }

            // An override changes what GateEvaluator answers, so it changes
            // what effectors should be doing -- and this emitted nothing at
            // all, so a subject admitted by hand never had a role or group
            // applied and one revoked by hand kept theirs. DECISIONS 10.26.
            List<String> affected = transitions.targetsOf(
                    override.subjectId(), override.identityRef());
            Map<String, Set<String>> gatesBefore = transitions.before(affected);

            String id = policy.addOverride(
                    override, clock.instant(), "connector:" + connector.id());

            audit.append(new AuditEntry(
                    0L, clock.instant(), "connector:" + connector.id(), "override.set",
                    override.subjectId(), override.identityRef(), view.gate(),
                    Map.of("effect", override.effect().wireName(),
                            "reason", override.reason())));

            transitions.emit(gatesBefore, affected);

            return WireResponse.ok(Map.of("id", id));
        });

        handlers.put(Operation.OVERRIDE_REMOVE, (connector, payload) -> {
            var request = codec.bind(payload, OverrideView.class);
            if (request.isEmpty()) {
                return unreadable(Operation.OVERRIDE_REMOVE, OverrideView.class);
            }
            OverrideView view = request.get();
            if (blank(view.gate())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "override.remove names a gate");
            }
            // The same "exactly one target" rule as override.set, stated here
            // rather than borrowed from PolicyOverride's constructor: this
            // request carries no effect and no reason, so it cannot build one.
            boolean bySubject = !blank(view.subjectId());
            boolean byIdentity = !blank(view.identityRef());
            if (bySubject == byIdentity) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST,
                        "override.remove names exactly one of subjectId or identityRef");
            }

            // The values, not `bySubject ? ... : null`. The check above has
            // already established that exactly one of the two is non-blank, so
            // the ternaries could only ever turn a blank into a null -- which
            // every reader of them treats identically. Four equivalent mutants
            // lived in that redundancy; deleting it is better than writing four
            // notes explaining why they cannot be killed. DECISIONS 10.35.
            String subjectId = view.subjectId();
            String identityRef = view.identityRef();

            List<String> affected = transitions.targetsOf(subjectId, identityRef);
            Map<String, Set<String>> gatesBefore = transitions.before(affected);

            int removed = policy.removeOverridesFor(view.gate(), subjectId, identityRef);

            // Audited even when nothing matched. "The operator tried to remove
            // an override that was not there" is a fact worth having when
            // somebody later asks why a gate still admits them.
            audit.append(new AuditEntry(
                    0L, clock.instant(), "connector:" + connector.id(), "override.removed",
                    subjectId, identityRef, view.gate(),
                    Map.of("removed", Integer.toString(removed))));

            transitions.emit(gatesBefore, affected);

            return WireResponse.ok(Map.of("removed", removed));
        });

        handlers.put(Operation.CONFIG_GET, (connector, payload) ->
                WireResponse.ok(Map.of("config", runtimeConfig.all())));

        handlers.put(Operation.CONFIG_SET, (connector, payload) -> {
            var request = codec.bind(payload, ConfigSetRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.CONFIG_SET, ConfigSetRequest.class);
            }
            if (blank(request.get().key())) {
                return WireResponse.error(ErrorCode.INVALID_REQUEST, "config.set names a key");
            }
            runtimeConfig.set(
                    request.get().key(), request.get().value(),
                    clock.instant(), "connector:" + connector.id());

            audit.append(new AuditEntry(
                    0L, clock.instant(), "connector:" + connector.id(), "config.changed",
                    null, null, null,
                    // The KEY, never the value. Runtime config can hold
                    // something an operator would not want in an audit log any
                    // more than in a config file.
                    Map.of("key", request.get().key())));

            events.append(EventRecord.of(
                    EventType.CONFIG_CHANGED, null, null, null,
                    Map.of("key", request.get().key()), clock.instant()));

            return WireResponse.ok(Map.of("key", request.get().key()));
        });

        handlers.put(Operation.IDENTITY_DESCRIBE, (connector, payload) -> {
            var request = codec.bind(payload, SubjectInspectRequest.class);
            if (request.isEmpty()) {
                return unreadable(Operation.IDENTITY_DESCRIBE, SubjectInspectRequest.class);
            }
            if (blank(request.get().platformKind())
                    || blank(request.get().platformId())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST, "describe names a platform account");
            }
            // Identical shape to subject.inspect, deliberately: the DIFFERENCE
            // is which capability reaches it, not what it returns. A connector
            // asking about an account it vouches for learns nothing it would not
            // learn when a link completes.
            var subject = identities.subjectOf(
                    request.get().platformKind(), request.get().platformId());
            if (subject.isEmpty()) {
                return WireResponse.ok(Map.of("linked", false));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("linked", true);
            body.put("subjectId", subject.get().id());
            body.put("identities", viewsOf(identities, subject.get().id()));
            return WireResponse.ok(body);
        });

        return Map.copyOf(handlers);
    }

    /** Events per page when a caller does not say. */
    private static final int DEFAULT_EVENT_PAGE = 100;

    /**
     * The ceiling, whatever a caller asks for.
     *
     * <p>An unbounded page against a long-lived outbox is a way to exhaust the
     * server's memory from an authenticated endpoint -- the same reasoning as
     * the audit query limit, and the same answer.
     */
    private static final int MAX_EVENT_PAGE = 1000;

    /**
     * The page size actually used.
     *
     * <p>Extracted so the ceiling is observable without creating a thousand
     * events to press against it. A mutation removing the clamp passed every
     * delivery test, because those ask for more than exists and the ceiling
     * never binds — the bound was only reachable at a scale no test wanted to
     * build.
     */
    static int effectivePageSize(Integer requested) {
        if (requested == null) {
            return DEFAULT_EVENT_PAGE;
        }
        return Math.min(MAX_EVENT_PAGE, Math.max(1, requested));
    }

    /** The ceiling, exposed for the test that asserts it binds. */
    static int maxEventPage() {
        return MAX_EVENT_PAGE;
    }

    private static EventView toView(EventRecord event) {
        return new EventView(
                event.sequence(),
                event.type().wireName(),
                event.subjectId(),
                event.identityRef(),
                event.gate(),
                event.payload(),
                event.idempotencyKey(),
                event.createdAt().getEpochSecond());
    }

    private static RuleView toView(Rule rule) {
        return new RuleView(
                rule.gateName(),
                List.copyOf(rule.requiredKinds()),
                rule.requireLinked(),
                rule.graceSeconds(),
                rule.defaultEffect().wireName());
    }

    private static OverrideView toView(PolicyOverride override) {
        return new OverrideView(
                override.gateName(),
                override.subjectId(),
                override.identityRef(),
                override.effect().wireName(),
                override.reason(),
                override.expiresAt() == null ? null : override.expiresAt().getEpochSecond());
    }

    /** A request naming only a gate. */
    private record GateRequest(String gate) {}

    private record ConfigSetRequest(String key, String value) {}

    /** Which connector to rotate. By NAME: an operator knows the name, not the uuid. */
    private record ConnectorRotateRequest(String name) {}

    /**
     * The payload could not be read as the shape this operation expects.
     *
     * <p>Distinct from a field being blank, and the distinction is not
     * pedantry. Every handler here used to answer {@code "<op> names a gate"}
     * for <b>both</b> — an unparseable payload and a genuinely missing gate —
     * because {@code request.isEmpty()} and {@code blank(field)} shared one
     * branch.
     *
     * <p>So a caller who sent a field this build does not know was told the
     * gate was missing, while the gate sat there in the request. That happened:
     * a harness added a {@code detail} field to {@code rule.set}, the bind
     * failed, and the reply said {@code rule.set names a gate}. It sends
     * somebody to check the one thing that is definitely correct.
     *
     * <p>Names the shape rather than the offending field, because the codec
     * reports failure without saying which key was at fault — claiming to know
     * would be a second wrong answer dressed as a better one.
     */
    private static WireResponse unreadable(Operation operation, Class<?> shape) {
        return WireResponse.error(
                ErrorCode.INVALID_REQUEST,
                operation.wireName() + " could not read the payload as " + shape.getSimpleName()
                        + ". A field this build does not recognise is refused rather than "
                        + "ignored, so a typo cannot be mistaken for a deliberate omission.");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static List<IdentityView> viewsOf(IdentityRepository identities, String subjectId) {
        return identities.identitiesOf(subjectId).stream()
                .map(i -> new IdentityView(
                        i.platformKind(),
                        i.platformId(),
                        i.display(),
                        i.flags(),
                        i.proofMethod(),
                        i.verifiedAt() == null ? null : i.verifiedAt().getEpochSecond(),
                        i.createdAt().getEpochSecond()))
                .toList();
    }

    private static AuditEntryView toView(AuditEntry entry) {
        return new AuditEntryView(
                entry.sequence(),
                entry.at().getEpochSecond(),
                entry.actor(),
                entry.action(),
                entry.subjectId(),
                entry.identityRef(),
                entry.gate(),
                entry.detail());
    }

    /** The operations this build implements, for the doctor and for tests. */
    public static List<Operation> implemented() {
        return List.of(
                Operation.HELLO,
                Operation.HEARTBEAT,
                Operation.CONNECTOR_LIST,
                Operation.CONNECTOR_ROTATE,
                Operation.AUDIT_PUSH,
                Operation.AUDIT_QUERY,
                Operation.CODE_ISSUE,
                Operation.CODE_REDEEM,
                Operation.ATTEST,
                Operation.DECIDE,
                Operation.EVENT_SUBSCRIBE,
                Operation.EVENT_ACK,
                Operation.RULE_GET,
                Operation.RULE_SET,
                Operation.OVERRIDE_GET,
                Operation.OVERRIDE_SET,
                Operation.OVERRIDE_REMOVE,
                Operation.CONFIG_GET,
                Operation.CONFIG_SET,
                Operation.IDENTITY_UNLINK,
                Operation.SUBJECT_INSPECT);
    }
}
