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
package dev.soulbind.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.policy.Decision;
import dev.soulbind.policy.Effect;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SDK's protocol layer, exercised entirely above the transport seam.
 *
 * <p>Every test here runs against {@link InMemoryTransport}. That is the seam
 * earning its keep: conditions a real network cannot be asked for on demand —
 * a core reachable for one call and gone for the next, a proxy error page where
 * an envelope was expected, a refusal arriving where a success was — are one
 * line each.
 *
 * <p>The claim under test throughout is the distinction between <b>refused</b>
 * and <b>unreachable</b>. Collapsing them turns "you may not" into "try again
 * later", and turns a genuine denial into something a retry loop eventually
 * gets past.
 */
class SoulbindClientTest {

    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String GATE = "minecraft.join";

    private static String allowEnvelope(int ttl) {
        return "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"allow\","
                + "\"reason\":\"requirements-met\",\"detail\":\"ok\","
                + "\"ttlSeconds\":" + ttl + ",\"missingKinds\":[]}}";
    }

    private static String denyEnvelope() {
        return "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"deny\","
                + "\"reason\":\"not-linked\",\"detail\":\"link first\","
                + "\"ttlSeconds\":60,\"missingKinds\":[\"kind-b\"]}}";
    }

    private static String refusalEnvelope(String code) {
        return "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"" + code
                + "\",\"message\":\"no\"}}";
    }

    private SoulbindClient client(InMemoryTransport transport, DecisionCache cache) {
        return new SoulbindClient(transport, "a-credential", CLOCK, cache);
    }

    // --- the distinction -------------------------------------------------------

    @Test
    @DisplayName("an answer is an answer")
    void freshAnswer() {
        InMemoryTransport transport = InMemoryTransport.always(allowEnvelope(60));
        DecisionCache.Answer answer =
                client(transport, new DecisionCache()).decide(GATE, "kind-a", "acct-1");

        assertEquals(Effect.ALLOW, answer.decision().effect());
        assertEquals(DecisionCache.Source.FRESH, answer.source());
    }

    @Test
    @DisplayName("a DENIAL from core is served as-is, not treated as a failure")
    void denialIsAnAnswer() {
        // core said no. That is a working system, and the connector's job is to
        // tell the person -- not to retry, and not to consult its fail mode.
        InMemoryTransport transport = InMemoryTransport.always(denyEnvelope());
        DecisionCache.Answer answer =
                client(transport, new DecisionCache()).decide(GATE, "kind-a", "acct-1");

        assertEquals(Effect.DENY, answer.decision().effect());
        assertEquals(DecisionCache.Source.FRESH, answer.source());
        assertEquals(java.util.List.of("kind-b"), answer.decision().missingKinds());
        assertEquals(1, transport.sendCount(), "a denial must not be retried");
    }

    @Test
    @DisplayName("an unreachable core falls back to the fail mode")
    void unreachableFallsBack() {
        InMemoryTransport transport = InMemoryTransport.always(allowEnvelope(60)).goDown();
        DecisionCache.Answer answer =
                client(transport, new DecisionCache()).decide(GATE, "kind-a", "acct-1");

        assertEquals(Effect.DENY, answer.decision().effect());
        assertEquals(DecisionCache.Source.FAIL_MODE, answer.source());
        assertTrue(answer.decision().detail().contains("our side, not yours"));
    }

    @Test
    @DisplayName("a REFUSAL never reaches the fail mode, and never serves a cached allow")
    void refusalDoesNotUseTheCache() {
        // The distinction that matters most. If this connector's capability is
        // revoked, serving a cached allow would use a stale answer to route
        // around a permissions problem -- and it would keep working long enough
        // for nobody to notice.
        InMemoryTransport transport = InMemoryTransport.always(allowEnvelope(600));
        DecisionCache cache = new DecisionCache();
        SoulbindClient client = client(transport, cache);

        assertEquals(Effect.ALLOW, client.decide(GATE, "kind-a", "acct-1").decision().effect());
        assertTrue(cache.cached(GATE, "kind-a:acct-1", NOW).isPresent());

        transport.respondWith(r -> refusalEnvelope(ErrorCode.MISSING_CAPABILITY.wireName()));
        DecisionCache.Answer answer = client.decide(GATE, "kind-a", "acct-1");

        assertEquals(
                Effect.DENY,
                answer.decision().effect(),
                "a cached allow was served after core refused the connector");
        assertTrue(answer.decision().detail().contains("missing-capability"));
    }

    @Test
    @DisplayName("an unreachable core DOES use an unexpired cached decision")
    void cacheCoversAnOutage() {
        InMemoryTransport transport = InMemoryTransport.always(allowEnvelope(600));
        DecisionCache cache = new DecisionCache();
        SoulbindClient client = client(transport, cache);

        client.decide(GATE, "kind-a", "acct-1");
        transport.goDown();

        DecisionCache.Answer answer = client.decide(GATE, "kind-a", "acct-1");
        assertEquals(Effect.ALLOW, answer.decision().effect());
        assertEquals(DecisionCache.Source.CACHED, answer.source());
    }

    @Test
    @DisplayName("a proxy error page is an OUTAGE, not a refusal")
    void nonEnvelopeIsAnOutage() {
        // Something between here and core is answering -- a captive portal, a
        // load balancer's error page. Core never said no, so treating it as a
        // refusal would report a denial nobody issued.
        SoulbindClient client = client(
                InMemoryTransport.always("<html><body>502 Bad Gateway</body></html>"),
                new DecisionCache());

        assertInstanceOf(
                SoulbindClient.Outcome.Unreachable.class, client.call("heartbeat", null));
    }

    @Test
    @DisplayName("VALID JSON that is not an envelope is also an outage")
    void validJsonThatIsNotAnEnvelopeIsAnOutage() {
        // The case the HTML test above does NOT reach: an error page fails to
        // parse at all and never gets as far as the is-this-an-envelope check.
        // A gateway that answers in JSON does -- and a mutation treating that
        // branch as a refusal passed every test until this existed.
        //
        // It matters because the JSON-speaking intermediary is the likely one:
        // an API gateway, a service mesh, a rate limiter. None of them is core,
        // and none of them refused anything.
        for (String body : new String[] {
            "{\"error\":\"gateway timeout\"}",
            "{\"message\":\"rate limited\",\"retryAfter\":30}",
            "[1,2,3]",
            "\"just a string\"",
            "null",
            "{}",
        }) {
            SoulbindClient client =
                    client(InMemoryTransport.always(body), new DecisionCache());
            assertInstanceOf(
                    SoulbindClient.Outcome.Unreachable.class,
                    client.call("heartbeat", null),
                    () -> "treated as an answer: " + body);
        }
    }

    @Test
    @DisplayName("a non-envelope response reaches the FAIL MODE, not a denial from core")
    void nonEnvelopeFallsBackToFailMode() {
        // The consequence of getting the previous test wrong: a connector would
        // report "core denied you" when core never saw the request.
        SoulbindClient client = client(
                InMemoryTransport.always("{\"error\":\"gateway timeout\"}"),
                new DecisionCache());

        DecisionCache.Answer answer = client.decide(GATE, "kind-a", "acct-1");
        assertEquals(Effect.DENY, answer.decision().effect());
        assertEquals(
                DecisionCache.Source.FAIL_MODE,
                answer.source(),
                "an intermediary's answer was reported as core's");
        assertTrue(
                answer.decision().detail().contains("our side, not yours"),
                "the person was told they were refused, rather than that the system is down");
    }

    @Test
    @DisplayName("a truncated response is an outage")
    void truncatedIsAnOutage() {
        InMemoryTransport transport = InMemoryTransport.always("{\"schema\":1,\"ok\":tr");
        assertInstanceOf(
                SoulbindClient.Outcome.Unreachable.class,
                client(transport, new DecisionCache()).call("heartbeat", null));
    }

    @Test
    @DisplayName("core recovering is noticed on the next call")
    void recovery() {
        // Asserted because a connector that latched "down" would stay down after
        // a blip, and the outage would outlive its cause.
        InMemoryTransport transport = InMemoryTransport.always(allowEnvelope(0)).goDown();
        SoulbindClient client = client(transport, new DecisionCache());

        assertEquals(
                DecisionCache.Source.FAIL_MODE,
                client.decide(GATE, "kind-a", "acct-1").source());

        transport.comeBack();
        assertEquals(
                DecisionCache.Source.FRESH,
                client.decide(GATE, "kind-a", "acct-1").source());
    }

    // --- parsing what core sent ------------------------------------------------

    @Test
    @DisplayName("an unreadable effect DENIES rather than defaulting open")
    void unreadableEffectDenies() {
        // A decision this build cannot parse must not open a gate.
        InMemoryTransport transport = InMemoryTransport.always(
                "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"maybe\","
                        + "\"reason\":\"requirements-met\",\"ttlSeconds\":60}}");

        assertEquals(
                Effect.DENY,
                client(transport, new DecisionCache()).decide(GATE, "kind-a", "acct-1")
                        .decision().effect());
    }

    @Test
    @DisplayName("an unknown reason parses to DEFAULT rather than throwing")
    void unknownReasonIsTolerated() {
        // A core newer than this connector may send a reason it has never heard
        // of. Refusing to parse the whole decision over an unrecognised label
        // would take the gate down over a vocabulary difference.
        InMemoryTransport transport = InMemoryTransport.always(
                "{\"schema\":1,\"ok\":true,\"payload\":{\"effect\":\"allow\","
                        + "\"reason\":\"some-future-reason\",\"ttlSeconds\":60}}");

        Decision decision = client(transport, new DecisionCache())
                .decide(GATE, "kind-a", "acct-1").decision();
        assertEquals(Effect.ALLOW, decision.effect());
        assertEquals(Decision.Reason.DEFAULT, decision.reason());
    }

    @Test
    @DisplayName("a zero-TTL decision is not cached")
    void zeroTtlNotCached() {
        InMemoryTransport transport = InMemoryTransport.always(allowEnvelope(0));
        DecisionCache cache = new DecisionCache();
        client(transport, cache).decide(GATE, "kind-a", "acct-1");
        assertEquals(0, cache.size());
    }

    // --- what goes out ----------------------------------------------------------

    @Test
    @DisplayName("every request carries the schema, the operation and a fresh id")
    void requestShape() {
        InMemoryTransport transport = InMemoryTransport.always(allowEnvelope(60));
        SoulbindClient client = client(transport, new DecisionCache());

        client.decide(GATE, "kind-a", "acct-1");
        client.decide(GATE, "kind-a", "acct-2");

        assertEquals(2, transport.sendCount());
        for (String sent : transport.sent()) {
            assertTrue(sent.contains("\"schema\":1"), sent);
            assertTrue(sent.contains("\"op\":\"decide\""), sent);
        }
        assertFalse(
                transport.sent().get(0).equals(transport.sent().get(1)),
                "two requests were byte-identical, so the correlation id is not fresh");
    }

    @Test
    @DisplayName("signing headers verify against the credential")
    void signingHeadersAreValid() {
        SoulbindClient client =
                client(InMemoryTransport.always(allowEnvelope(60)), new DecisionCache());

        String body = "{\"a\":1}";
        var headers = client.signingHeaders(body);

        assertTrue(dev.soulbind.protocol.RequestSigner.verify(
                "a-credential".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Long.parseLong(headers.get(dev.soulbind.protocol.Wire.HEADER_TIMESTAMP)),
                headers.get(dev.soulbind.protocol.Wire.HEADER_NONCE),
                body,
                headers.get(dev.soulbind.protocol.Wire.HEADER_SIGNATURE)));
    }

    @Test
    @DisplayName("each signed request gets a fresh nonce")
    void nonceIsFresh() {
        // A reused nonce is a request core refuses as a replay -- the connector
        // would work once per restart and then stop, which is a maddening
        // failure to diagnose from the outside.
        SoulbindClient client =
                client(InMemoryTransport.always(allowEnvelope(60)), new DecisionCache());

        assertFalse(
                client.signingHeaders("{}").get(dev.soulbind.protocol.Wire.HEADER_NONCE)
                        .equals(client.signingHeaders("{}")
                                .get(dev.soulbind.protocol.Wire.HEADER_NONCE)));
    }
}
