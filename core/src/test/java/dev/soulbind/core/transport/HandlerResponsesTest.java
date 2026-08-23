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
 * What the handlers put in a response, and one thing they do on the way.
 *
 * <p>Each of these was a mutant nothing killed: a view whose fields could all be
 * dropped, a ternary that could report an unproven identity as proven, and the
 * line that forgives somebody who mistyped a code before getting it right.
 */
class HandlerResponsesTest {

    @TempDir
    Path tempDir;

    private static final String PLAYER = "11111111-2222-3333-4444-555555555555";

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
                        Capability.CODE_ENTRY, Capability.IDENTITY_PROVIDER),
                clock);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("rule.get answers with the rule, not with an empty shape")
    void ruleGetCarriesTheRule(Backend backend) throws Exception {
        // The whole view could be dropped and nothing failed. An administrator
        // reading a gate would see a rule with no requirements and conclude the
        // gate was open, when it is not.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            ok(core, clock, "rule.set",
                    "{\"gate\":\"game.join\",\"requiredKinds\":[\"chat\"],"
                            + "\"requireLinked\":true,\"graceSeconds\":600,"
                            + "\"defaultEffect\":\"allow\"}");

            // The view IS the payload -- there is no wrapper. A gate with no
            // rule answers `{"gate": ..., "configured": false}` instead, which
            // is why "configured" is asserted absent below.
            JsonNode rule = ok(core, clock, "rule.get", "{\"gate\":\"game.join\"}");

            assertEquals("game.join", rule.get("gate").asText());
            assertEquals("chat", rule.get("requiredKinds").get(0).asText(),
                    "the required platforms are missing, so the gate reads as open");
            assertTrue(rule.get("requireLinked").asBoolean());
            assertEquals(600, rule.get("graceSeconds").asLong(),
                    "the grace period is missing, so a forgiving gate reads as strict");
            assertEquals("allow", rule.get("defaultEffect").asText());
            assertFalse(rule.has("configured"),
                    "a gate that HAS a rule answered with the shape used for one that has"
                            + " none: " + rule);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a gate with no rule says so, rather than failing")
    void ruleGetOnAnUngovernedGate(Backend backend) throws Exception {
        // No rule is not an error: a gate nobody configured is a gate nobody
        // asked for, and reporting that as a failure would make "is this gate
        // governed?" unanswerable without catching something.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            JsonNode answer = ok(core, clock, "rule.get", "{\"gate\":\"nobody.configured\"}");

            assertEquals("nobody.configured", answer.get("gate").asText());
            assertFalse(answer.get("configured").asBoolean(),
                    "an ungoverned gate reported itself as configured: " + answer);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("subject.inspect reports an unproven identity as having no verification time")
    void inspectDistinguishesProvenFromNot(Backend backend) throws Exception {
        // `verifiedAt == null ? null : ...`. Inverting it reports an identity
        // nobody has proven as verified at the epoch -- and the operator
        // reading that page is looking at it precisely to find out which
        // accounts still need proving.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            ok(core, clock, "attest",
                    "{\"platformKind\":\"game\",\"platformId\":\"" + PLAYER + "\","
                            + "\"proofMethod\":\"oauth\"}");

            JsonNode identity = ok(core, clock, "subject.inspect",
                    "{\"platformKind\":\"game\",\"platformId\":\"" + PLAYER + "\"}")
                    .get("identities").get(0);

            assertTrue(identity.get("verifiedAtEpochSeconds").asLong() > 0,
                    "an attested identity reports no verification time");

            // And the other side. Every wire path that binds an identity marks
            // it proven -- attest by its own method, redeem by `link-code` --
            // so an unproven one is reachable only through the repository. That
            // is not a reason to leave the branch untested: the column is
            // nullable, `PolicyEngine` reads `isVerified()`, and a view that
            // reported the epoch instead of nothing would tell an operator an
            // account was proven when the graph says it was not.
            var subject = core.storage.identities().createSubject(clock.instant());
            core.storage.identities().bind(
                    subject.id(), "chat", "chat-1", "Alex", java.util.Map.of(),
                    null, null, clock.instant());

            JsonNode chat = ok(core, clock, "subject.inspect",
                    "{\"platformKind\":\"chat\",\"platformId\":\"chat-1\"}")
                    .get("identities");

            assertTrue(chat.get(0).get("verifiedAtEpochSeconds").isNull(),
                    "an identity nobody has proven reported a verification time, so the page"
                            + " cannot answer what it exists to answer: " + chat);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("getting a code right clears the failures before it")
    void successForgivesEarlierMistakes(Backend backend) throws Exception {
        // Somebody who mistypes twice and then gets it right is not a threat,
        // and carrying their failures forward would eventually lock out a
        // person for being human. Nothing asserted the clearing.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            for (int i = 0; i < 3; i++) {
                core.postSigned(
                        core.request("code.redeem",
                                "{\"code\":\"WRONGONE\",\"platformKind\":\"forum\","
                                        + "\"platformId\":\"forum-1\"}"),
                        clock.instant());
            }
            assertTrue(core.throttle.recentFailures("forum:forum-1", clock.instant()) > 0,
                    "the wrong guesses were not counted, so this test proves nothing");

            String code = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"chat\",\"platformId\":\"chat-1\","
                            + "\"display\":\"Alex\"}").get("code").asText();
            ok(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"forum\","
                            + "\"platformId\":\"forum-1\"}");

            assertEquals(
                    0, core.throttle.recentFailures("forum:forum-1", clock.instant()),
                    "a successful redeem left the earlier mistakes on the record, so somebody"
                            + " who fumbled and then succeeded is still counted as guessing");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a request id that is absent or not a string is carried as absent")
    void requestIdIsOptionalAndMustBeAString(Backend backend) throws Exception {
        // Both halves of `id != null && id.isTextual()`. The id is echoed back
        // so a caller can match a reply to a request; a number where a string
        // belongs must not be echoed as one, and its absence must not throw.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = admin(backend, tempDir, clock)) {
            for (String envelope : new String[] {
                    "{\"schema\":1,\"op\":\"heartbeat\",\"payload\":{}}",
                    "{\"schema\":1,\"op\":\"heartbeat\",\"id\":7,\"payload\":{}}"}) {

                JsonNode json = core.codec.mapper().readTree(
                        core.postSigned(envelope, clock.instant()).body());

                assertTrue(json.get(Wire.OK).asBoolean(),
                        () -> "a request with no usable id was refused: " + json);
                assertFalse(json.hasNonNull(Wire.ID),
                        () -> "an id that is not a string was echoed back anyway: " + json);
            }
        }
    }
}
