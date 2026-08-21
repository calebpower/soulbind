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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.events.EventRecord;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.Wire;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What an operator's override tells the effectors.
 *
 * <p><b>It told them nothing.</b> {@code GateEvaluator.satisfiedGates} counts an
 * allow-override as satisfying a gate — its javadoc says so, and gives as the
 * reason that overrides "change only when something else emits an event, which
 * is exactly what an effector can track". Nothing emitted on {@code
 * override.set}. So an operator admitting somebody by hand changed what the
 * evaluator answered and no connector was ever told: no role, no group, and no
 * line anywhere saying why.
 *
 * <p>The full-stack {@code groups} stage ran red on this for two sessions. Its
 * smoke admits a player by override so they can run {@code /link} — the
 * documented reason overrides exist — which meant that identity already
 * satisfied the gate before the link and there was no transition to emit when
 * the link arrived. Core was right; the missing event was one operation
 * earlier. See DECISIONS 10.26.
 */
class OverrideEffectTest {

    @TempDir
    Path tempDir;

    private static final String GATE = "game.join";
    private static final String REF = "game:11111111-2222-3333-4444-555555555555";

    private JsonNode ok(TestCore core, Clock clock, String op, String payload) throws Exception {
        JsonNode json = core.codec.mapper().readTree(
                core.postSigned(core.request(op, payload), clock.instant()).body());
        assertTrue(json.get(Wire.OK).asBoolean(), json::toString);
        return json.get(Wire.PAYLOAD);
    }

    private static TestCore admin(Backend backend, Path tempDir, Clock clock) throws Exception {
        return new TestCore(
                backend, tempDir,
                Set.of(Capability.CONFIG_MANAGEMENT, Capability.CODE_DISPLAY,
                        Capability.CODE_ENTRY, Capability.ENFORCEMENT_POINT),
                clock);
    }

    /** A gate that denies everybody who has not linked. */
    private void gateRequiringALink(TestCore core, Clock clock) throws Exception {
        ok(core, clock, "rule.set",
                "{\"gate\":\"" + GATE + "\",\"requiredKinds\":[],\"requireLinked\":true,"
                        + "\"graceSeconds\":0,\"defaultEffect\":\"deny\"}");
    }

    private static List<EventRecord> eventsOf(TestCore core, String type, String ref) {
        return core.storage.events().after(0, 1000).stream()
                .filter(e -> e.type().wireName().equals(type))
                .filter(e -> ref == null || ref.equals(e.identityRef()))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a permanent allow-override tells the effectors the gate just opened")
    void allowOverrideEmitsRequirementsMet(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            gateRequiringALink(core, clock);

            ok(core, clock, "override.set",
                    "{\"gate\":\"" + GATE + "\",\"identityRef\":\"" + REF + "\","
                            + "\"effect\":\"allow\",\"reason\":\"admitted so they can link\"}");

            List<EventRecord> met = eventsOf(core, "subject.requirements-met", REF);
            assertEquals(1, met.size(),
                    "an operator admitted an account by hand and no effector was told, so no"
                            + " role or group was ever applied: "
                            + core.storage.events().after(0, 1000).stream()
                                    .map(e -> e.type().wireName()).toList());
            assertEquals(GATE, met.get(0).gate(),
                    "the event does not name the gate, so a connector cannot tell whether it is"
                            + " the one it is configured for");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an override that EXPIRES tells them nothing, because nothing would take it back")
    void expiringOverrideIsSilent(Backend backend) throws Exception {
        // The narrowing, and it is the same one grace already carries: nothing
        // in this system re-evaluates on a timer. A group granted for a
        // one-hour override would still be there in March, and the operator who
        // set an hour would have no idea. Not granting is the smaller wrong.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            gateRequiringALink(core, clock);

            long anHourOn = clock.instant().getEpochSecond() + 3600;
            ok(core, clock, "override.set",
                    "{\"gate\":\"" + GATE + "\",\"identityRef\":\"" + REF + "\","
                            + "\"effect\":\"allow\",\"reason\":\"temporary\","
                            + "\"expiresAtEpochSeconds\":" + anHourOn + "}");

            assertTrue(eventsOf(core, "subject.requirements-met", REF).isEmpty(),
                    "a group was granted for an override that lapses in an hour, and nothing in"
                            + " this system would ever take it back");

            // Still ALLOWED, though -- the gate must honour it. Excluding it
            // from what effectors are told is not the same as ignoring it.
            assertEquals(
                    "allow",
                    ok(core, clock, "decide",
                            "{\"gate\":\"" + GATE + "\",\"platformKind\":\"game\","
                                    + "\"platformId\":\"11111111-2222-3333-4444-555555555555\"}")
                            .get("effect").asText(),
                    "the temporary override stopped admitting anybody, which is not a narrowing"
                            + " of what effectors hear -- it is a broken gate");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a deny-override over somebody who qualified takes the role back")
    void denyOverrideEmitsRequirementsLost(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            gateRequiringALink(core, clock);

            String code = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"game\",\"platformId\":"
                            + "\"11111111-2222-3333-4444-555555555555\",\"display\":\"P\"}")
                    .get("code").asText();
            ok(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"chat\","
                            + "\"platformId\":\"chat-1\"}");
            assertFalse(eventsOf(core, "subject.requirements-met", REF).isEmpty(),
                    "the link did not open the gate, so this test would be asserting nothing");

            ok(core, clock, "override.set",
                    "{\"gate\":\"" + GATE + "\",\"identityRef\":\"" + REF + "\","
                            + "\"effect\":\"deny\",\"reason\":\"banned by hand\"}");

            assertEquals(1, eventsOf(core, "subject.requirements-lost", REF).size(),
                    "an operator denied somebody by hand and the effector kept granting them"
                            + " the role, which is the ban not working");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("removing an override takes back what it granted")
    void removingAnOverrideEmitsRequirementsLost(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            gateRequiringALink(core, clock);

            ok(core, clock, "override.set",
                    "{\"gate\":\"" + GATE + "\",\"identityRef\":\"" + REF + "\","
                            + "\"effect\":\"allow\",\"reason\":\"admitted\"}");
            assertEquals(1, eventsOf(core, "subject.requirements-met", REF).size());

            JsonNode removed = ok(core, clock, "override.remove",
                    "{\"gate\":\"" + GATE + "\",\"identityRef\":\"" + REF + "\"}");
            assertEquals(1, removed.get("removed").asInt(),
                    "override.remove reported removing nothing, so the operator has no way to"
                            + " know whether it worked");

            assertEquals(1, eventsOf(core, "subject.requirements-lost", REF).size(),
                    "the override was taken back and the group it granted was not, so the"
                            + " permission outlives the decision that produced it");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("override.remove naming neither target is refused, never treated as 'all'")
    void removeWithoutATargetIsRefused(Backend backend) throws Exception {
        // A DELETE whose only predicate is the gate would clear every override
        // on it, and the reasons they carried are not recoverable.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            gateRequiringALink(core, clock);
            ok(core, clock, "override.set",
                    "{\"gate\":\"" + GATE + "\",\"identityRef\":\"" + REF + "\","
                            + "\"effect\":\"allow\",\"reason\":\"admitted\"}");

            JsonNode json = core.codec.mapper().readTree(
                    core.postSigned(
                            core.request("override.remove", "{\"gate\":\"" + GATE + "\"}"),
                            clock.instant()).body());

            assertFalse(json.get(Wire.OK).asBoolean(),
                    "override.remove with no target was accepted; if it removed anything it"
                            + " removed somebody else's override: " + json);
            assertEquals(
                    1, core.storage.policy().overridesFor(GATE).size(),
                    "the override was removed by a request that named no target");

            // And AGAIN one layer down. The handler refuses first, so the
            // repository's own guard is unreachable from the wire and would sit
            // there untested -- which is how defence in depth quietly becomes
            // one layer of defence and a comment. Asserted on the return value,
            // not on the surviving row: a repository that reported a removal it
            // did not perform is its own defect.
            assertEquals(
                    0,
                    core.storage.policy().removeOverridesFor(GATE, null, null),
                    "removeOverridesFor with neither target reported doing something; a DELETE"
                            + " whose only predicate is the gate clears overrides nobody asked"
                            + " about, and their reasons are gone with them");
            assertEquals(1, core.storage.policy().overridesFor(GATE).size());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a subject-scoped override reaches every platform the subject is on")
    void subjectOverrideFansOutPerIdentity(Backend backend) throws Exception {
        // Per identity, not per subject: each platform's effector reads the
        // event's identityRef and refuses a ref whose kind is not its own, so
        // one event would leave every platform but one with nothing to act on.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            // A gate nobody satisfies by linking, so the override is the only
            // thing that could open it and the count is unambiguous.
            ok(core, clock, "rule.set",
                    "{\"gate\":\"" + GATE + "\",\"requiredKinds\":[\"nobody-has-this\"],"
                            + "\"requireLinked\":true,\"graceSeconds\":0,"
                            + "\"defaultEffect\":\"deny\"}");

            String code = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"game\",\"platformId\":"
                            + "\"11111111-2222-3333-4444-555555555555\",\"display\":\"P\"}")
                    .get("code").asText();
            ok(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"chat\","
                            + "\"platformId\":\"chat-1\"}");

            String subjectId = core.storage.identities()
                    .subjectOf("game", "11111111-2222-3333-4444-555555555555")
                    .orElseThrow().id();

            ok(core, clock, "override.set",
                    "{\"gate\":\"" + GATE + "\",\"subjectId\":\"" + subjectId + "\","
                            + "\"effect\":\"allow\",\"reason\":\"the whole person\"}");

            List<EventRecord> met = eventsOf(core, "subject.requirements-met", null);
            assertEquals(
                    Set.of(REF, "chat:chat-1"),
                    met.stream().map(EventRecord::identityRef)
                            .collect(java.util.stream.Collectors.toSet()),
                    "a subject-scoped override did not reach every identity on the subject, so"
                            + " one platform's effector was never told: "
                            + met.stream().map(EventRecord::identityRef).toList());
        }
    }
}
