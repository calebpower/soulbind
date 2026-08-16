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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.policy.Effect;
import dev.soulbind.policy.PolicyOverride;
import dev.soulbind.policy.Rule;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.Wire;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** `decide` over the wire, and a rule changing underneath a decision. */
class DecideWireTest {

    @TempDir
    Path tempDir;

    private static final String GATE = "gate.post";

    private JsonNode ok(TestCore core, Clock clock, String op, String payload) throws Exception {
        JsonNode json = core.codec.mapper().readTree(
                core.postSigned(core.request(op, payload), clock.instant()).body());
        assertTrue(json.get(Wire.OK).asBoolean(), json::toString);
        return json.get(Wire.PAYLOAD);
    }

    private String decideBody(String kind, String id) {
        return "{\"gate\":\"" + GATE + "\",\"platformKind\":\"" + kind
                + "\",\"platformId\":\"" + id + "\"}";
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an unconfigured gate allows, and asking about it registers it")
    void unconfiguredGateAllows(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.ENFORCEMENT_POINT), clock)) {

            JsonNode decision = ok(core, clock, "decide", decideBody("kind-a", "acct-1"));
            assertEquals("allow", decision.get("effect").asText());
            assertEquals("no-rule", decision.get("reason").asText());

            // A connector asking about a gate is declaring the gate exists. An
            // operator cannot write a rule for a gate they cannot see.
            assertTrue(
                    core.storage.policy().gates().contains(GATE),
                    "the gate was not recorded, so it is invisible to whoever writes rules");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a rule requiring a link denies an unlinked account, and says what is missing")
    void ruleDeniesUnlinked(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.ENFORCEMENT_POINT), clock)) {

            core.storage.policy().setRule(Rule.linked(GATE), clock.instant(), "test");

            JsonNode decision = ok(core, clock, "decide", decideBody("kind-a", "acct-1"));
            assertEquals("deny", decision.get("effect").asText());
            assertEquals("not-linked", decision.get("reason").asText());
            assertTrue(decision.get("ttlSeconds").asInt() > 0);
            assertNotNull(decision.get("detail").asText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("linking changes the answer, and the decision reflects the graph")
    void linkingFlipsTheDecision(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir,
                Set.of(Capability.ENFORCEMENT_POINT, Capability.CODE_DISPLAY,
                        Capability.CODE_ENTRY), clock)) {

            core.storage.policy().setRule(Rule.linked(GATE), clock.instant(), "test");
            assertEquals(
                    "deny",
                    ok(core, clock, "decide", decideBody("kind-a", "acct-1"))
                            .get("effect").asText());

            String code = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\"}")
                    .get("code").asText();
            ok(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"kind-b\","
                            + "\"platformId\":\"acct-2\"}");

            JsonNode after = ok(core, clock, "decide", decideBody("kind-a", "acct-1"));
            assertEquals(
                    "allow", after.get("effect").asText(),
                    "the decision is computed from the graph, so linking must change it "
                            + "without anything being told to invalidate anything");
            assertEquals("requirements-met", after.get("reason").asText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a rule requiring verified kinds is not satisfied by an unverified link")
    void verificationMatters(Backend backend) throws Exception {
        // Binding an account is not proving it. A gate that accepted a bound
        // but unproven identity would accept a claim.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir,
                Set.of(Capability.ENFORCEMENT_POINT, Capability.IDENTITY_PROVIDER), clock)) {

            core.storage.policy().setRule(
                    Rule.requiring(GATE, "kind-a"), clock.instant(), "test");

            // Bound with no proof: bind() directly, not through attest.
            var subject = core.storage.identities().createSubject(clock.instant());
            core.storage.identities().bind(
                    subject.id(), "kind-a", "acct-1", null, java.util.Map.of(),
                    null, null, clock.instant());

            JsonNode denied = ok(core, clock, "decide", decideBody("kind-a", "acct-1"));
            assertEquals("deny", denied.get("effect").asText());
            assertEquals("missing-kinds", denied.get("reason").asText());
            assertEquals(
                    List.of("kind-a"),
                    toList(denied.get("missingKinds")),
                    "the denial must name the kind, even though the account IS of that kind -- "
                            + "what is missing is the proof");

            // Attesting supplies the proof, and the answer flips.
            ok(core, clock, "attest",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\","
                            + "\"proofMethod\":\"oauth\"}");
            assertEquals(
                    "allow",
                    ok(core, clock, "decide", decideBody("kind-a", "acct-1"))
                            .get("effect").asText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an override reaches the decision, in both directions")
    void overridesReachTheDecision(Backend backend) throws Exception {
        // Found by mutation-checking: replacing the override lookup with an
        // empty list passed every test in this file. The override path -- which
        // is how an operator admits or bans one person -- was reachable only
        // through the engine's own unit tests, and nothing proved core actually
        // consulted it.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.ENFORCEMENT_POINT), clock)) {

            core.storage.policy().setRule(Rule.linked(GATE), clock.instant(), "test");
            assertEquals(
                    "deny",
                    ok(core, clock, "decide", decideBody("kind-a", "acct-1"))
                            .get("effect").asText());

            // An allow override for an account that has linked nothing -- the
            // case an override exists for.
            String allowId = core.storage.policy().addOverride(
                    new PolicyOverride(
                            GATE, null, "kind-a:acct-1", Effect.ALLOW, "vouched for", null),
                    clock.instant(), "operator");

            JsonNode allowed = ok(core, clock, "decide", decideBody("kind-a", "acct-1"));
            assertEquals("allow", allowed.get("effect").asText());
            assertEquals("override", allowed.get("reason").asText());
            assertEquals(
                    "vouched for", allowed.get("detail").asText(),
                    "the operator's reason travels to whoever reads the decision");

            // A deny override on top must win, because wrongly denying costs a
            // complaint and wrongly allowing costs the thing the gate is for.
            core.storage.policy().addOverride(
                    new PolicyOverride(
                            GATE, null, "kind-a:acct-1", Effect.DENY, "banned since", null),
                    clock.instant(), "operator");
            assertEquals(
                    "deny",
                    ok(core, clock, "decide", decideBody("kind-a", "acct-1"))
                            .get("effect").asText(),
                    "deny must beat allow through the wire too, not only in the engine");

            // And an override for a DIFFERENT gate must not leak in.
            core.storage.policy().addOverride(
                    new PolicyOverride(
                            "gate.other", null, "kind-a:acct-2", Effect.ALLOW, "elsewhere", null),
                    clock.instant(), "operator");
            assertEquals(
                    "deny",
                    ok(core, clock, "decide", decideBody("kind-a", "acct-2"))
                            .get("effect").asText(),
                    "an override for another gate opened this one");

            assertNotNull(allowId);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an expired override does not reach the decision")
    void expiredOverrideDoesNotApply(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.ENFORCEMENT_POINT), clock)) {

            core.storage.policy().setRule(Rule.linked(GATE), clock.instant(), "test");
            core.storage.policy().addOverride(
                    new PolicyOverride(
                            GATE, null, "kind-a:acct-1", Effect.ALLOW, "temporary",
                            clock.instant().minusSeconds(1)),
                    clock.instant(), "operator");

            assertEquals(
                    "deny",
                    ok(core, clock, "decide", decideBody("kind-a", "acct-1"))
                            .get("effect").asText(),
                    "a lapsed override still opened the gate");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("T8: a rule mutating under a storm of decisions never yields a torn answer")
    void ruleMutationRacingDecision(Backend backend) throws Exception {
        // The specification's T8 for this phase. The claim is NOT that the
        // answer is stable -- it must change, that is the point of editing a
        // rule. The claim is that every answer is one of the two COHERENT ones,
        // and never a blend of a half-applied edit.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.ENFORCEMENT_POINT), clock)) {

            core.storage.policy().setRule(Rule.linked(GATE), clock.instant(), "test");

            int readers = 8;
            int perReader = 40;
            ExecutorService pool = Executors.newFixedThreadPool(readers + 1);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger incoherent = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();

            try {
                for (int r = 0; r < readers; r++) {
                    futures.add(pool.submit(() -> {
                        start.await();
                        for (int i = 0; i < perReader; i++) {
                            JsonNode d = ok(core, clock, "decide", decideBody("kind-a", "acct-1"));
                            String effect = d.get("effect").asText();
                            String reason = d.get("reason").asText();

                            // Every answer must be internally consistent: the
                            // reason must be one that CAN produce that effect.
                            boolean coherent = switch (reason) {
                                case "no-rule", "requirements-met", "grace" ->
                                        effect.equals("allow");
                                case "not-linked", "missing-kinds", "default" ->
                                        effect.equals("deny");
                                case "override" -> true;
                                default -> false;
                            };
                            if (!coherent) {
                                incoherent.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }

                // The writer, flipping the rule underneath them.
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < 40; i++) {
                        if (i % 2 == 0) {
                            core.storage.policy().clearRule(GATE);
                        } else {
                            core.storage.policy().setRule(
                                    Rule.linked(GATE), clock.instant(), "test");
                        }
                    }
                    return null;
                }));

                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(120, TimeUnit.SECONDS), "did not finish");
                for (Future<?> future : futures) {
                    future.get(120, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertEquals(
                    0, incoherent.get(),
                    () -> incoherent.get() + " decisions paired an effect with a reason that "
                            + "cannot produce it, which means a decision saw a half-applied "
                            + "rule edit");
        }
    }

    private static List<String> toList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
