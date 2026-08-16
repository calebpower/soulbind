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
import dev.soulbind.core.audit.AuditEntry;
import dev.soulbind.core.audit.AuditQuery;
import dev.soulbind.core.identity.Identity;
import dev.soulbind.core.identity.LinkCodeRecord;
import dev.soulbind.core.identity.LinkingService;
import dev.soulbind.core.storage.AuditRepository;
import dev.soulbind.core.storage.ConnectorRepository;
import dev.soulbind.core.storage.IdentityRepository;
import dev.soulbind.core.storage.PolicyRepository;
import dev.soulbind.protocol.AttestRequest;
import dev.soulbind.protocol.AuditEntryView;
import dev.soulbind.protocol.AuditPushRequest;
import dev.soulbind.protocol.AuditQueryRequest;
import dev.soulbind.policy.Decision;
import dev.soulbind.policy.PolicyEngine;
import dev.soulbind.policy.SubjectSnapshot;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.CodeIssueRequest;
import dev.soulbind.protocol.CodeIssueResponse;
import dev.soulbind.protocol.CodeRedeemRequest;
import dev.soulbind.protocol.CodeRedeemResponse;
import dev.soulbind.protocol.DecideRequest;
import dev.soulbind.protocol.DecideResponse;
import dev.soulbind.protocol.IdentityView;
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
            LinkingService linking,
            Codec codec,
            Clock clock,
            int signatureWindowSeconds) {

        Map<Operation, Dispatcher.Handler> handlers = new LinkedHashMap<>();

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
            if (request.isEmpty() || request.get().action() == null
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
            List<AuditEntry> entries = audit.query(new AuditQuery(
                    q.fromEpochSeconds() == null
                            ? null : Instant.ofEpochSecond(q.fromEpochSeconds()),
                    q.toEpochSeconds() == null
                            ? null : Instant.ofEpochSecond(q.toEpochSeconds()),
                    q.actor(),
                    q.subjectId(),
                    q.action(),
                    q.limit() == null ? AuditQuery.DEFAULT_LIMIT : q.limit()));

            return WireResponse.ok(Map.of(
                    "entries",
                    entries.stream().map(CoreHandlers::toView).toList()));
        });

        handlers.put(Operation.CODE_ISSUE, (connector, payload) -> {
            var request = codec.bind(payload, CodeIssueRequest.class);
            if (request.isEmpty() || blank(request.get().platformKind())
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
            if (request.isEmpty() || blank(request.get().platformKind())
                    || blank(request.get().platformId())) {
                return WireResponse.error(
                        ErrorCode.INVALID_REQUEST,
                        "a redeem needs the code and the account redeeming it");
            }

            LinkingService.Result result = linking.redeem(
                    connector.id(),
                    request.get().code(),
                    request.get().platformKind(),
                    request.get().platformId(),
                    request.get().display());

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
            if (request.isEmpty() || blank(request.get().platformKind())
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
            if (request.isEmpty() || blank(request.get().platformKind())
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
            if (request.isEmpty() || blank(request.get().platformKind())
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
            if (request.isEmpty() || blank(request.get().gate())
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

            var subject = identities.subjectOf(q.platformKind(), q.platformId());
            String ref = q.platformKind() + ":" + q.platformId();

            SubjectSnapshot snapshot;
            if (subject.isEmpty()) {
                snapshot = SubjectSnapshot.unlinked(ref, clock.instant());
            } else {
                List<Identity> graph = identities.identitiesOf(subject.get().id());
                Set<String> verified = new TreeSet<>();
                Instant firstSeen = clock.instant();
                for (Identity identity : graph) {
                    if (identity.isVerified()) {
                        verified.add(identity.platformKind());
                    }
                    if (identity.createdAt().isBefore(firstSeen)) {
                        firstSeen = identity.createdAt();
                    }
                }
                // firstSeen from the graph, not from the caller: grace computed
                // from a connector-supplied time is grace anybody can extend.
                snapshot = new SubjectSnapshot(
                        subject.get().id(), ref, verified, graph.size(), firstSeen);
            }

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

        return Map.copyOf(handlers);
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
                Operation.AUDIT_PUSH,
                Operation.AUDIT_QUERY,
                Operation.CODE_ISSUE,
                Operation.CODE_REDEEM,
                Operation.ATTEST,
                Operation.DECIDE,
                Operation.IDENTITY_UNLINK,
                Operation.SUBJECT_INSPECT);
    }
}
