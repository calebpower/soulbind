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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.events.EventEmitter;
import dev.soulbind.core.events.EventRecord;
import dev.soulbind.core.policy.GateEvaluator;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.Storage;
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.policy.Effect;
import dev.soulbind.policy.Rule;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Core telling effectors that a gate opened.
 *
 * <p>§7: core emits {@code subject.requirements-met} "per gate whose
 * requirements just became satisfied". It did not, for ten phases. The event
 * types were declared in {@code EventType}, documented in
 * {@code docs/protocol.md} with a paragraph on why they are per-gate, and
 * consumed by both reference effectors — and nothing produced them, so the
 * role effector could not fire in any deployment.
 *
 * <p>Nothing caught it because each half was tested against the other's
 * assumption: {@code RoleEffectorTest} injects the event and proves the role is
 * applied; the full-stack battery drives {@code decide}, a different path. The
 * two oracles never met. These are the meeting.
 */
class GateTransitionTest {

    @TempDir Path tempDir;

    private static final Duration TTL = Duration.ofMinutes(10);

    private record Fixture(Storage storage, LinkingService linking) implements AutoCloseable {
        @Override public void close() {
            storage.close();
        }
    }

    private Fixture fixture(Backend backend) {
        Storage storage = StorageBackends.open(backend, tempDir);
        Clock clock = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);
        return new Fixture(
                storage,
                new LinkingService(
                        new EventEmitter(storage.events(), clock),
                        storage.identities(), storage.linkCodes(), storage.platformKinds(),
                        storage.audit(),
                        new GateEvaluator(storage.identities(), storage.policy(), clock),
                        clock, TTL));
    }

    /** A gate that opens only for a subject linked across both platforms. */
    private static void ruleRequiringBoth(Storage storage, String gate) {
        storage.policy().gateSeen(gate, "conn-a", null);
        storage.policy().setRule(
                new Rule(gate, Set.of("kind-a", "kind-b"), true, 0L, Effect.DENY),
                Instant.parse("2026-03-01T11:00:00Z"), "test");
    }

    private static List<EventRecord> eventsOf(Storage storage, String type) {
        return storage.events().after(0, 1000).stream()
                .filter(e -> e.type().wireName().equals(type))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a link that opens a gate emits requirements-met, per identity")
    void linkOpeningAGateEmits(Backend backend) {
        try (Fixture f = fixture(backend)) {
            ruleRequiringBoth(f.storage(), "chat.member");

            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", "Alex");
            f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", "Alex");

            List<EventRecord> met = eventsOf(f.storage(), "subject.requirements-met");

            // One per identity, not one per subject: an effector finds its
            // target from the event's identityRef and refuses a ref whose kind
            // is not its own, so a single event would leave one platform's
            // effector with nothing it could act on.
            assertEquals(2, met.size(),
                    "expected one requirements-met per identity on the subject, got: "
                            + met.stream().map(EventRecord::identityRef).toList());
            assertEquals(
                    Set.of("kind-a:acct-1", "kind-b:acct-2"),
                    met.stream().map(EventRecord::identityRef).collect(
                            java.util.stream.Collectors.toSet()));
            assertTrue(met.stream().allMatch(e -> "chat.member".equals(e.gate())),
                    "the event does not name the gate that opened, so an effector cannot"
                            + " tell which of its configured roles to grant");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a link that satisfies nothing emits no requirements-met")
    void linkNotOpeningAGateIsSilent(Backend backend) {
        try (Fixture f = fixture(backend)) {
            // Requires a third kind nobody has. Linking two accounts is real
            // progress and still does not open this gate.
            f.storage().policy().gateSeen("strict.gate", "conn-a", null);
            f.storage().policy().setRule(
                    new Rule("strict.gate", Set.of("kind-a", "kind-b", "kind-c"), true, 0L,
                            Effect.DENY),
                    Instant.parse("2026-03-01T11:00:00Z"), "test");

            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", "Alex");
            f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", "Alex");

            assertTrue(eventsOf(f.storage(), "subject.requirements-met").isEmpty(),
                    "a gate whose requirements are still unmet emitted requirements-met,"
                            + " which would grant a role to somebody who does not qualify");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an unconfigured gate is not something anybody satisfies")
    void unruledGateIsNeverSatisfied(Backend backend) {
        // Asserted on the EVALUATOR, not on the event stream, and the reason is
        // worth recording: the obvious version of this test -- mention a gate,
        // link, assert no event -- passes whether or not the exclusion exists.
        // An unruled gate admits everybody, so it is "satisfied" before the
        // link and after it, and a transition diff sees no change either way.
        // Mutation-checking caught that: making unruled gates count as
        // satisfied left the event-level test green.
        //
        // What the exclusion actually protects is the day a rule is added to a
        // gate people have been passing through unruled -- and, more
        // immediately, it stops "gate mentioned once by one connector" from
        // meaning "every subject durably qualifies for it".
        try (Fixture f = fixture(backend)) {
            Clock clock = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);
            GateEvaluator evaluator = new GateEvaluator(
                    f.storage().identities(), f.storage().policy(), clock);

            f.storage().policy().gateSeen("merely.mentioned", "conn-a", null);
            ruleRequiringBoth(f.storage(), "chat.member");

            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", "Alex");
            f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", "Alex");

            Set<String> satisfied = evaluator.satisfiedGates("kind-a", "acct-1");
            assertTrue(satisfied.contains("chat.member"),
                    "the configured gate this subject does satisfy is missing, so the"
                            + " assertion below would hold for the wrong reason: " + satisfied);
            assertFalse(satisfied.contains("merely.mentioned"),
                    "a gate nobody has written a rule for is reported as durably satisfied."
                            + " Gates are recorded on first MENTION, so this would hand a"
                            + " standing role to every subject the moment any connector"
                            + " asked about a gate once: " + satisfied);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("unlinking emits requirements-lost, for the siblings as well")
    void unlinkEmitsLost(Backend backend) {
        try (Fixture f = fixture(backend)) {
            ruleRequiringBoth(f.storage(), "chat.member");
            LinkCodeRecord code = f.linking().issue("conn-a", "kind-a", "acct-1", "Alex");
            f.linking().redeem("conn-b", code.code(), "kind-b", "acct-2", "Alex");

            assertTrue(f.linking().unlink("conn-a", "kind-b", "acct-2"));

            List<EventRecord> lost = eventsOf(f.storage(), "subject.requirements-lost");

            // BOTH sides. Removing one identity drops the subject below the
            // rule, so the identity that stayed loses the gate too -- and its
            // effector is the one still holding a role it must take back.
            assertEquals(2, lost.size(),
                    "expected requirements-lost for the removed identity AND its sibling,"
                            + " got: " + lost.stream().map(EventRecord::identityRef).toList());
            assertTrue(
                    lost.stream().anyMatch(e -> "kind-a:acct-1".equals(e.identityRef())),
                    "the identity that REMAINED was not told it lost the gate, so its role"
                            + " would stay granted forever");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a second link does not re-emit a gate already open")
    void noDuplicateForAnAlreadyOpenGate(Backend backend) {
        try (Fixture f = fixture(backend)) {
            // requireLinked only: open as soon as there are two identities, and
            // it stays open when a third arrives. An effector is idempotent, so
            // a repeat is survivable -- but it is still core saying something
            // changed when nothing did.
            f.storage().policy().gateSeen("any.link", "conn-a", null);
            f.storage().policy().setRule(
                    new Rule("any.link", Set.of(), true, 0L, Effect.DENY),
                    Instant.parse("2026-03-01T11:00:00Z"), "test");

            LinkCodeRecord first = f.linking().issue("conn-a", "kind-a", "acct-1", "Alex");
            f.linking().redeem("conn-b", first.code(), "kind-b", "acct-2", "Alex");
            int afterFirst = eventsOf(f.storage(), "subject.requirements-met").size();

            LinkCodeRecord second = f.linking().issue("conn-a", "kind-a", "acct-1", "Alex");
            f.linking().redeem("conn-c", second.code(), "kind-c", "acct-3", "Alex");

            List<EventRecord> met = eventsOf(f.storage(), "subject.requirements-met");
            assertFalse(met.stream()
                            .filter(e -> "kind-a:acct-1".equals(e.identityRef()))
                            .count() > 1,
                    "the identity that already satisfied this gate was told again that it"
                            + " newly satisfies it; requirements-met means a TRANSITION");
            assertTrue(met.size() > afterFirst,
                    "the newly-joined identity was never told it satisfies the gate");
        }
    }
}
