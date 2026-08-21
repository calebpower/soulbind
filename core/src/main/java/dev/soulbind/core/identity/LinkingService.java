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

package dev.soulbind.core.identity;

import dev.soulbind.core.audit.AuditEntry;
import dev.soulbind.core.events.EventEmitter;
import dev.soulbind.core.policy.GateEvaluator;
import dev.soulbind.core.policy.GateTransitions;
import dev.soulbind.core.storage.AuditRepository;
import dev.soulbind.core.storage.IdentityRepository;
import dev.soulbind.core.storage.LinkCodeRepository;
import dev.soulbind.core.storage.PlatformKindRepository;
import dev.soulbind.protocol.EventType;
import dev.soulbind.protocol.LinkCode;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Issuing and redeeming link codes.
 *
 * <p><b>Symmetric by construction.</b> Either side of a link can be the one that
 * displays a code or the one that accepts it — core never learns which pairing
 * is "normal", because it never sees a pairing at all. It sees a code issued for
 * one platform account and later redeemed by another, and both sides are the
 * same shape.
 *
 * <p>That symmetry is not a convenience. It is what stops a chat platform
 * becoming the de-facto root of identity simply because it was implemented
 * first.
 */
public final class LinkingService {

    /** Why a redeem did not link anything. Each is a different fix for the person. */
    public enum Refusal {
        /** No such code. Includes a code that expired long ago and was purged. */
        UNKNOWN_CODE,
        /** The code existed but its lifetime has passed. */
        EXPIRED,
        /** Somebody already redeemed it — possibly this same caller, twice. */
        ALREADY_REDEEMED,
        /** The redeeming account is the one the code was issued for. */
        SAME_ACCOUNT,
        /** One of the two accounts already belongs to somebody. */
        ALREADY_LINKED
    }

    /** The outcome of a redeem. */
    public sealed interface Result {
        record Linked(Subject subject, Identity issued, Identity redeemed) implements Result {}

        record Denied(Refusal refusal, String detail) implements Result {}
    }

    private final EventEmitter events;
    private final IdentityRepository identities;
    private final LinkCodeRepository codes;
    private final PlatformKindRepository kinds;
    private final AuditRepository audit;

    private final GateTransitions transitions;
    private final Clock clock;
    private final Duration ttl;

    public LinkingService(
            EventEmitter events,
            IdentityRepository identities,
            LinkCodeRepository codes,
            PlatformKindRepository kinds,
            AuditRepository audit,
            GateEvaluator gates,
            Clock clock,
            Duration ttl) {
        this.events = events;
        this.identities = identities;
        this.codes = codes;
        this.kinds = kinds;
        this.audit = audit;
        this.transitions = new GateTransitions(events, identities, gates);
        this.clock = clock;
        this.ttl = ttl;
    }


    /**
     * Emits the per-gate transitions a mutation just caused.
     *
     * <p>The specification (§7) has core emit {@code subject.requirements-met}
     * "per gate whose requirements just became satisfied" as part of the same
     * transaction as the link.
     *
     * <p><b>Delegated.</b> The logic used to live here, which meant only the
     * operations this service owns ever told an effector anything — and setting
     * an operator override, which changes exactly the same answer, told nobody.
     * See {@link GateTransitions} and DECISIONS 10.26.
     *
     * @param before what each identity ref satisfied prior to the change
     * @param refs the identities to re-evaluate, as {@code kind:id}
     */
    private void emitGateTransitions(Map<String, Set<String>> before, Collection<String> refs) {
        transitions.emit(before, refs);
    }

    /** Every identity ref on a subject, for fan-out to each platform's effector. */
    private List<String> refsOf(String subjectId) {
        return transitions.refsOf(subjectId);
    }

    /** What each of these refs satisfies right now, for diffing afterwards. */
    private Map<String, Set<String>> gatesSatisfiedBy(Collection<String> refs) {
        return transitions.before(refs);
    }

    /**
     * Mints a code for a platform account.
     *
     * <p>The caller vouches for the account locally — it knows who ran the
     * command. Core does not verify that and could not: it has no way to
     * authenticate a platform account itself, which is precisely why connectors
     * exist.
     */
    public LinkCodeRecord issue(
            String issuingConnectorId, String platformKind, String platformId, String display) {

        kinds.seen(platformKind, issuingConnectorId);

        LinkCodeRecord record = new LinkCodeRecord(
                LinkCode.generate(),
                issuingConnectorId,
                platformKind,
                platformId,
                display,
                clock.instant(),
                clock.instant().plus(ttl),
                null,
                null);

        codes.issue(record);

        audit.append(AuditEntry.of(
                clock.instant(),
                "connector:" + issuingConnectorId,
                "code.issued",
                // The CODE ITSELF is not audited. It is a live secret until it
                // is redeemed or expires, and an audit log readable by anyone
                // holding config-management would otherwise be a list of
                // working codes.
                Map.of("issuedFor", platformKind + ":" + platformId)));

        return record;
    }

    /**
     * Redeems a code on behalf of a second platform account.
     *
     * <p>The order here is deliberate and each step exists because the one
     * before it cannot be trusted to have happened:
     *
     * <ol>
     *   <li>normalise, because what a person typed is not what was issued;
     *   <li>read, to distinguish "no such code" from "expired" from "used";
     *   <li><b>claim</b>, which is the single-use decision and is atomic;
     *   <li>only then link, because until the claim succeeds this caller has no
     *       right to the code at all.
     * </ol>
     */
    public Result redeem(
            String redeemingConnectorId,
            String typedCode,
            String platformKind,
            String platformId,
            String display) {

        Optional<String> normalised = LinkCode.normalise(typedCode);
        if (normalised.isEmpty()) {
            // Rejected, never repaired. A code containing characters outside the
            // alphabet is a typo, and guessing which character was meant would
            // silently redeem a DIFFERENT code -- linking the wrong account with
            // no error anybody could see.
            return new Result.Denied(
                    Refusal.UNKNOWN_CODE, "that is not a code this system issues");
        }
        String code = normalised.get();

        Optional<LinkCodeRecord> found = codes.find(code);
        if (found.isEmpty()) {
            return new Result.Denied(Refusal.UNKNOWN_CODE, "no such code");
        }
        LinkCodeRecord record = found.get();

        if (record.isRedeemed()) {
            return new Result.Denied(Refusal.ALREADY_REDEEMED, "that code has been used");
        }
        if (record.isExpired(clock.instant())) {
            return new Result.Denied(
                    Refusal.EXPIRED, "that code has expired; ask for a new one");
        }
        if (record.issuedForKind().equals(platformKind)
                && record.issuedForId().equals(platformId)) {
            // Linking an account to itself would create a subject with one
            // identity and the appearance of a completed link. The person would
            // believe they were linked and no gate would agree.
            return new Result.Denied(
                    Refusal.SAME_ACCOUNT, "that code was issued for this same account");
        }

        // Snapshotted BEFORE the claim, because everything after it changes the
        // answer. Both sides: linking can complete requirements for the account
        // that issued the code as readily as for the one redeeming it.
        Map<String, Set<String>> gatesBefore = gatesSatisfiedBy(List.of(
                record.issuedForKind() + ":" + record.issuedForId(),
                platformKind + ":" + platformId));

        // THE decision. Everything above is a better error message; this is the
        // line that makes a code single-use, and it is atomic.
        if (!codes.claim(code, redeemingConnectorId, clock.instant())) {
            return new Result.Denied(
                    Refusal.ALREADY_REDEEMED, "that code was used a moment ago");
        }

        kinds.seen(platformKind, redeemingConnectorId);

        Optional<Subject> issuedSide =
                identities.subjectOf(record.issuedForKind(), record.issuedForId());
        Optional<Subject> redeemedSide = identities.subjectOf(platformKind, platformId);

        if (issuedSide.isPresent() && redeemedSide.isPresent()) {
            // Both already belong to somebody. Merging is not offered -- see
            // IdentityRepository -- so this is a refusal rather than a guess.
            // The code stays claimed: it was used, and re-offering it would let
            // the same collision be retried indefinitely.
            return new Result.Denied(
                    Refusal.ALREADY_LINKED,
                    "both accounts are already linked, to "
                            + (issuedSide.get().id().equals(redeemedSide.get().id())
                                    ? "each other"
                                    : "different people"));
        }

        Subject subject = issuedSide.or(() -> redeemedSide)
                .orElseGet(() -> identities.createSubject(clock.instant()));

        Identity issuedIdentity = issuedSide.isPresent()
                ? identities.findIdentity(record.issuedForKind(), record.issuedForId())
                        .orElseThrow()
                : identities.bind(
                        subject.id(), record.issuedForKind(), record.issuedForId(),
                        record.issuedForDisplay(), Map.of(), "link-code",
                        clock.instant(), clock.instant());

        Identity redeemedIdentity = redeemedSide.isPresent()
                ? identities.findIdentity(platformKind, platformId).orElseThrow()
                : identities.bind(
                        subject.id(), platformKind, platformId, display, Map.of(),
                        "link-code", clock.instant(), clock.instant());

        audit.append(new AuditEntry(
                0L,
                clock.instant(),
                "connector:" + redeemingConnectorId,
                "identity.linked",
                subject.id(),
                redeemedIdentity.ref(),
                null,
                Map.of(
                        "issuedBy", record.issuedByConnector(),
                        "issuedFor", issuedIdentity.ref(),
                        "proofMethod", "link-code")));

        // Emitted AFTER the audit row and after the binds, in the same call
        // path as the change itself. An event written somewhere else would
        // eventually describe a change that did not happen, or miss one that
        // did.
        events.emit(
                EventType.IDENTITY_LINKED,
                subject.id(),
                redeemedIdentity.ref(),
                null,
                Map.of("issuedFor", issuedIdentity.ref(), "proofMethod", "link-code"));

        // AFTER identity.linked, deliberately. An effector reading the stream
        // in order sees the link before it sees what the link made true, which
        // is the order the two facts actually happened in.
        emitGateTransitions(
                gatesBefore, List.of(issuedIdentity.ref(), redeemedIdentity.ref()));

        return new Result.Linked(subject, issuedIdentity, redeemedIdentity);
    }

    /**
     * Records that a platform account proved itself.
     *
     * <p>Distinct from redeeming a code: this is an {@code identity-provider}
     * connector saying "I proved this account belongs to this person, by a means
     * of my own". The method is recorded rather than merely a boolean, because
     * policy is entitled to care about <em>how</em> something was proven — a
     * gate may accept a link code for one thing and demand something stronger
     * for another.
     *
     * <p>An account nobody has seen before gets a subject of its own. That is
     * not a link — a subject with one identity is a person known on one platform
     * — and it is the honest representation of what the connector just asserted.
     */
    public Identity attest(
            String attestingConnectorId,
            String platformKind,
            String platformId,
            String display,
            String proofMethod) {

        kinds.seen(platformKind, attestingConnectorId);

        // Before the attestation, and by REF rather than by subject: the
        // account may not have one yet, and if it does the whole subject's
        // outcomes can move.
        Map<String, Set<String>> gatesBefore = gatesSatisfiedBy(
                identities.subjectOf(platformKind, platformId)
                        .map(o -> refsOf(o.id()))
                        .orElse(List.of(platformKind + ":" + platformId)));

        String method = proofMethod == null || proofMethod.isBlank()
                ? "connector-attested"
                : proofMethod;

        Optional<Identity> existing = identities.findIdentity(platformKind, platformId);
        Identity identity;
        if (existing.isPresent()) {
            identities.markVerified(platformKind, platformId, method, clock.instant());
            identity = identities.findIdentity(platformKind, platformId).orElseThrow();
        } else {
            Subject subject = identities.createSubject(clock.instant());
            identity = identities.bind(
                    subject.id(), platformKind, platformId, display, Map.of(),
                    method, clock.instant(), clock.instant());
        }

        audit.append(new AuditEntry(
                0L,
                clock.instant(),
                "connector:" + attestingConnectorId,
                "identity.verified",
                identity.subjectId(),
                identity.ref(),
                null,
                Map.of("proofMethod", method)));

        events.emit(
                EventType.IDENTITY_VERIFIED,
                identity.subjectId(),
                identity.ref(),
                null,
                Map.of("proofMethod", method));

        // Verification changes gate outcomes on its own: a rule asking for a
        // VERIFIED kind is unmet by an identity that is merely linked, so
        // proving one can open a gate without any link happening. Emitting for
        // every identity on the subject, because the newly-proved kind may be
        // what another platform's effector was waiting for.
        emitGateTransitions(gatesBefore, refsOf(identity.subjectId()));

        return identity;
    }

    /**
     * Removes an identity from its subject.
     *
     * <p>Hard with respect to policy: the row goes, and a decision asked one
     * transaction later sees it gone. Soft with respect to audit: the rows
     * naming it stay forever, because what happened still happened.
     */
    public boolean unlink(String actorConnectorId, String platformKind, String platformId) {
        Optional<Subject> owner = identities.subjectOf(platformKind, platformId);

        // Every ref on the subject, captured before the row goes: removing one
        // identity can drop the whole subject below a rule's required kinds, so
        // the SIBLINGS lose gates too, and their effectors have to hear about
        // it. The removed identity is in the list as well -- it is losing every
        // gate it had, which is what tells its own effector to take the role
        // back.
        List<String> affected = owner.map(o -> refsOf(o.id())).orElse(List.of());
        Map<String, Set<String>> gatesBefore = gatesSatisfiedBy(affected);

        boolean removed = identities.unlink(platformKind, platformId);

        if (removed) {
            audit.append(new AuditEntry(
                    0L,
                    clock.instant(),
                    "connector:" + actorConnectorId,
                    "identity.unlinked",
                    owner.map(Subject::id).orElse(null),
                    platformKind + ":" + platformId,
                    null,
                    Map.of()));

            emitGateTransitions(gatesBefore, affected);

            events.emit(
                    EventType.IDENTITY_UNLINKED,
                    owner.map(Subject::id).orElse(null),
                    platformKind + ":" + platformId);
        }
        return removed;
    }

    /** Every identity of the subject owning a platform account. */
    public List<Identity> graphOf(String platformKind, String platformId) {
        return identities.subjectOf(platformKind, platformId)
                .map(s -> identities.identitiesOf(s.id()))
                .orElse(List.of());
    }
}
