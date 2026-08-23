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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.GateRecord;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.Wire;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * What a gate is for, said out loud and kept.
 *
 * <p>The column existed from the first migration and nothing could write to it:
 * both call sites passed {@code null} and no wire message carried one, so it was
 * a promise the schema made that nothing kept. These are the tests for the pipe
 * that fills it, and most of them exist for the same hazard -- that a gate is
 * re-declared on <b>every</b> permission check, so anything careless about a
 * missing description erases one that took an operator a minute to write, and
 * the erasure looks exactly like the note never having saved.
 *
 * <p>See DECISIONS 10.47.
 */
class GateDescriptionTest {

    @TempDir
    Path tempDir;

    private static final String GATE = "game.join";
    private static final String NOTE = "whether a player may connect to this deployment";

    private JsonNode ok(TestCore core, Clock clock, String op, String payload) throws Exception {
        JsonNode json = core.codec.mapper().readTree(
                core.postSigned(core.request(op, payload), clock.instant()).body());
        assertTrue(json.get(Wire.OK).asBoolean(), json::toString);
        return json.get(Wire.PAYLOAD);
    }

    private static TestCore core(Backend backend, Path tempDir, Clock clock) throws Exception {
        return new TestCore(
                backend, tempDir,
                Set.of(Capability.CONFIG_MANAGEMENT, Capability.ENFORCEMENT_POINT),
                clock);
    }

    private static String ruleBody(String description) {
        return "{\"gate\":\"" + GATE + "\",\"requiredKinds\":[],\"requireLinked\":false,"
                + "\"graceSeconds\":0,\"defaultEffect\":\"allow\""
                + (description == null ? "" : ",\"description\":\"" + description + "\"")
                + "}";
    }

    private static String decideBody() {
        return "{\"gate\":\"" + GATE + "\",\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\"}";
    }

    // --- the pipe, end to end ------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a description set with a rule comes back when the rule is read")
    void descriptionSurvivesTheRoundTrip(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            JsonNode set = ok(core, clock, "rule.set", ruleBody(NOTE));
            assertEquals(NOTE, set.get("description").asText(),
                    "rule.set answers with what is now stored, so an operator can see it took");

            JsonNode got = ok(core, clock, "rule.get", "{\"gate\":\"" + GATE + "\"}");
            assertEquals(NOTE, got.get("description").asText());
            assertEquals(
                    core.connector.id(),
                    got.get("registeredBy").asText(),
                    "the gate was declared by this connector, and rule.get is where an operator "
                            + "would look to find out who introduced a gate they did not expect");
        }
    }

    // --- the hazard the whole change lives or dies on ------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a permission check does not erase the description")
    void decideDoesNotEraseTheDescription(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            ok(core, clock, "rule.set", ruleBody(NOTE));

            // A gate is re-declared on every decide, with no description,
            // because a connector enforcing a gate knows its name and nothing
            // about what an operator wrote down.
            ok(core, clock, "decide", decideBody());
            ok(core, clock, "decide", decideBody());

            assertEquals(
                    NOTE,
                    core.storage.policy().gate(GATE).orElseThrow().description(),
                    "the busiest path in the system runs over this row constantly; if it "
                            + "overwrites, documentation vanishes minutes after being written "
                            + "and looks like it was never saved");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a description reaches a gate that already existed")
    void descriptionReachesAnAlreadyDeclaredGate(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            // The normal order of events: a connector meets the gate long
            // before an operator gets round to configuring it. An insert-only
            // write would accept the description here and store nothing.
            ok(core, clock, "decide", decideBody());
            assertNull(core.storage.policy().gate(GATE).orElseThrow().description());

            ok(core, clock, "rule.set", ruleBody(NOTE));
            assertEquals(NOTE, core.storage.policy().gate(GATE).orElseThrow().description());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a later rule change with nothing to say leaves the description alone")
    void aSilentRuleChangeKeepsTheDescription(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            ok(core, clock, "rule.set", ruleBody(NOTE));

            // Tightening a rule is not a statement about the documentation, and
            // an operator who omits the field is not asking for it to be
            // dropped -- most callers will not know the field exists.
            ok(core, clock, "rule.set", ruleBody(null));
            assertEquals(NOTE, core.storage.policy().gate(GATE).orElseThrow().description());

            // Blank is treated as absent, not as "delete this". There is
            // deliberately no way to blank a description; the alternative is a
            // request that wipes one by omitting a field.
            ok(core, clock, "rule.set", ruleBody("   "));
            assertEquals(NOTE, core.storage.policy().gate(GATE).orElseThrow().description());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a description can be replaced by a later one")
    void aDescriptionCanBeCorrected(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            ok(core, clock, "rule.set", ruleBody(NOTE));
            ok(core, clock, "rule.set", ruleBody("what it actually does"));

            assertEquals(
                    "what it actually does",
                    core.storage.policy().gate(GATE).orElseThrow().description(),
                    "a note nobody can correct is worse than no note");
        }
    }

    // --- what a caller does not get to say -----------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a caller cannot state who registered a gate")
    void registeredByIsNotAcceptedFromTheWire(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            ok(core, clock, "rule.set",
                    "{\"gate\":\"" + GATE + "\",\"requiredKinds\":[],\"requireLinked\":false,"
                            + "\"graceSeconds\":0,\"defaultEffect\":\"allow\","
                            + "\"registeredBy\":\"somebody-else\"}");

            assertEquals(
                    core.connector.id(),
                    core.storage.policy().gate(GATE).orElseThrow().registeredBy(),
                    "provenance a caller can assert is not provenance; it would let one "
                            + "connector put its own name, or another's, on a gate it did not "
                            + "introduce");
        }
    }

    // --- the gate outlives the rule ------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("withdrawing a rule leaves the gate and what was written about it")
    void clearingARuleKeepsTheDescription(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            ok(core, clock, "rule.set", ruleBody(NOTE));
            assertTrue(core.storage.policy().clearRule(GATE));

            JsonNode got = ok(core, clock, "rule.get", "{\"gate\":\"" + GATE + "\"}");
            assertEquals(
                    false,
                    got.get("configured").asBoolean(),
                    "the rule is gone, so the gate reads as unconfigured");
            assertEquals(
                    NOTE,
                    got.get("description").asText(),
                    "the description belongs to the gate, not to the rule -- dropping it here "
                            + "would make the documentation disappear the moment somebody "
                            + "withdrew a policy, which is when it is most worth reading");
        }
    }

    // --- the repository's own guarantee --------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("registered_by means who declared it first, not who asked last")
    void registeredByIsFirstWriterWins(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            core.storage.policy().gateSeen(GATE, "conn-first", null);
            core.storage.policy().gateSeen(GATE, "conn-second", NOTE);

            GateRecord gate = core.storage.policy().gate(GATE).orElseThrow();
            assertEquals(
                    "conn-first",
                    gate.registeredBy(),
                    "rewriting this on each declaration would redefine it as 'who asked most "
                            + "recently', which every connector overwrites within seconds");
            assertEquals(NOTE, gate.description(), "the description still lands");
            assertNotNull(gate.firstSeenAt());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a gate nobody declared has no row to read")
    void unknownGateIsEmpty(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = core(backend, tempDir, clock)) {
            assertTrue(
                    core.storage.policy().gate("never.declared").isEmpty(),
                    "an absent gate must be empty, not a row of nulls that reads as a gate "
                            + "somebody declared and said nothing about");
        }
    }
}
