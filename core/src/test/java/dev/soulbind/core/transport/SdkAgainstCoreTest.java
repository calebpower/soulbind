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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.storage.Backend;
import dev.soulbind.policy.Effect;
import dev.soulbind.policy.Rule;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.HttpTransport;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The SDK against a real core, over the real transport.
 *
 * <p>Everything else in this repository tests one side. The SDK's own suite
 * runs above an in-memory transport; core's runs against a hand-built HTTP
 * client. Both are right, and neither proves the two <b>agree</b> — that
 * signing produces what verification expects, that a refusal core emits is a
 * refusal the SDK recognises, that a decision survives the round trip with its
 * TTL intact.
 *
 * <p>This is the seam between the two implementations, and until now nothing
 * crossed it. The golden vectors cross the Java/PHP seam; this crosses the
 * client/server one.
 */
class SdkAgainstCoreTest {

    @TempDir
    Path tempDir;

    // Neutral, and it has to be: this test lives in core, where the platform
    // vocabulary guard forbids naming a platform. The guard caught the first
    // version, which borrowed a gate name from the connector that motivated the
    // test -- exactly the leak it exists to stop.
    private static final String GATE = "gate.join";

    private SoulbindClient connect(TestCore core, DecisionCache cache) {
        return new SoulbindClient(
                new HttpTransport(
                        "http://127.0.0.1:" + core.port, core.credential, Clock.systemUTC()),
                core.credential,
                Clock.systemUTC(),
                cache);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the SDK's signing is what core's verification expects")
    void signingAgrees(Backend backend) {
        // The claim the golden vectors cannot make: they prove two
        // IMPLEMENTATIONS of the signing scheme agree, not that this client and
        // this server do. A mismatch here would look like a credential problem.
        try (TestCore core = new TestCore(
                        backend, tempDir, Set.of(Capability.CODE_DISPLAY), Clock.systemUTC());
                SoulbindClient client = connect(core, new DecisionCache())) {

            assertInstanceOf(
                    SoulbindClient.Outcome.Ok.class,
                    client.call("heartbeat", null),
                    "a request the SDK signed was not accepted by core");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a decision survives the round trip with its TTL")
    void decisionRoundTrip(Backend backend) {
        try (TestCore core = new TestCore(
                        backend, tempDir, Set.of(Capability.ENFORCEMENT_POINT),
                        Clock.systemUTC());
                SoulbindClient client = connect(core, new DecisionCache())) {

            core.storage.policy().setRule(
                    Rule.linked(GATE), Clock.systemUTC().instant(), "test");

            DecisionCache.Answer answer = client.decide(GATE, "kind-a", "acct-1");

            assertEquals(Effect.DENY, answer.decision().effect());
            assertEquals(DecisionCache.Source.FRESH, answer.source());
            assertTrue(
                    answer.decision().ttlSeconds() > 0,
                    "the TTL did not survive the round trip, so nothing will ever be cached");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a real refusal from core is recognised as a refusal, not an outage")
    void refusalIsRecognised(Backend backend) {
        // The distinction the SDK is built around, checked against a refusal
        // core actually emits rather than one a test wrote by hand.
        try (TestCore core = new TestCore(
                        backend, tempDir, Set.of(Capability.CODE_DISPLAY), Clock.systemUTC());
                SoulbindClient client = connect(core, new DecisionCache())) {

            // This connector holds code-display, not enforcement-point.
            SoulbindClient.Outcome outcome = client.call("decide", new Object() {
                public final String gate = GATE;
                public final String platformKind = "kind-a";
                public final String platformId = "acct-1";
            });

            SoulbindClient.Outcome.Refused refused = assertInstanceOf(
                    SoulbindClient.Outcome.Refused.class,
                    outcome,
                    "core's refusal was read as an outage, which would send the connector to "
                            + "its fail mode instead of telling somebody");
            assertEquals(ErrorCode.MISSING_CAPABILITY, refused.code());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a full link through the SDK, both halves")
    void fullLinkThroughTheSdk(Backend backend) {
        try (TestCore core = new TestCore(
                        backend, tempDir,
                        Set.of(Capability.CODE_DISPLAY, Capability.CODE_ENTRY,
                                Capability.CONFIG_MANAGEMENT),
                        Clock.systemUTC());
                SoulbindClient client = connect(core, new DecisionCache())) {

            SoulbindClient.Outcome issued = client.call("code.issue", new Object() {
                public final String platformKind = "kind-a";
                public final String platformId = "acct-1";
                public final String display = "Alex";
            });
            String code = assertInstanceOf(SoulbindClient.Outcome.Ok.class, issued)
                    .payload().text("code");
            assertFalse(code.isBlank());

            SoulbindClient.Outcome redeemed = client.call("code.redeem", new RedeemBody(
                    code, "kind-b", "acct-2", "Alex"));

            var ok = assertInstanceOf(SoulbindClient.Outcome.Ok.class, redeemed);
            assertEquals(
                    2, ok.payload().size("identities"),
                    "the link did not produce a two-identity subject");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a stale signature is refused, and the SDK reads that as a refusal")
    void staleSignatureIsARefusal(Backend backend) {
        // A connector whose clock has drifted. It must be told, not left to
        // conclude the network is down -- the fix is a clock, not a cable.
        Clock skewed = Clock.offset(Clock.systemUTC(), java.time.Duration.ofHours(2));
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CODE_DISPLAY), Clock.systemUTC())) {

            SoulbindClient client = new SoulbindClient(
                    new HttpTransport("http://127.0.0.1:" + core.port, core.credential, skewed),
                    core.credential,
                    skewed,
                    new DecisionCache());

            SoulbindClient.Outcome.Refused refused = assertInstanceOf(
                    SoulbindClient.Outcome.Refused.class, client.call("heartbeat", null));
            assertEquals(ErrorCode.STALE_TIMESTAMP, refused.code());
            client.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a wrong credential is a refusal, and never opens a gate")
    void wrongCredential(Backend backend) {
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.ENFORCEMENT_POINT), Clock.systemUTC())) {

            SoulbindClient client = new SoulbindClient(
                    new HttpTransport(
                            "http://127.0.0.1:" + core.port, "not-a-credential",
                            Clock.systemUTC()),
                    "not-a-credential",
                    Clock.systemUTC(),
                    new DecisionCache());

            DecisionCache.Answer answer = client.decide(GATE, "kind-a", "acct-1");
            assertEquals(
                    Effect.DENY,
                    answer.decision().effect(),
                    "an unregistered credential got an allow");
            client.close();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("core stopping is an OUTAGE to the SDK, not a refusal")
    void coreStoppingIsAnOutage(Backend backend) {
        // The other half of the distinction, against a server that genuinely
        // goes away rather than a transport told to pretend.
        TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.ENFORCEMENT_POINT), Clock.systemUTC());
        SoulbindClient client = connect(core, new DecisionCache());
        try {
            assertInstanceOf(SoulbindClient.Outcome.Ok.class, client.call("heartbeat", null));

            core.close();

            DecisionCache.Answer answer = client.decide(GATE, "kind-a", "acct-1");
            assertEquals(Effect.DENY, answer.decision().effect());
            assertEquals(
                    DecisionCache.Source.FAIL_MODE,
                    answer.source(),
                    "a dead server was reported as a decision rather than an outage");
        } finally {
            client.close();
        }
    }

    private record RedeemBody(
            String code, String platformKind, String platformId, String display) {}
}
