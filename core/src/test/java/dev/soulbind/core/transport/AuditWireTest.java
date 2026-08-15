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
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.Wire;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The audit operations over the wire.
 *
 * <p>The claim that matters here is <b>attribution</b>: an audit log whose actor
 * the caller controls is not evidence of anything, so the tests that assert core
 * decides it are the load-bearing ones.
 *
 * <p>What these do NOT prove: that every action which should be audited is
 * audited. That is a completeness claim asserted from both sides by the
 * simulated-user tier, where a shadow model predicts the rows that must exist
 * and no row may exist the model cannot account for.
 */
class AuditWireTest {

    @TempDir
    Path tempDir;

    private static final Set<Capability> SOURCE_AND_READER =
            Set.of(Capability.AUDIT_SOURCE, Capability.CONFIG_MANAGEMENT);

    /**
     * Sends a request and returns its payload, insisting it succeeded.
     *
     * <p>Takes the clock rather than reading the wall clock: the server runs on
     * a fixed clock, so signing against {@code now} puts the timestamp decades
     * outside the freshness window and every call fails as stale — which is
     * what happened the first time this was written.
     */
    private JsonNode payloadOf(TestCore core, Clock clock, String body) throws Exception {
        JsonNode json = core.codec.mapper().readTree(
                core.postSigned(body, clock.instant()).body());
        assertTrue(json.get(Wire.OK).asBoolean(), json::toString);
        return json.get(Wire.PAYLOAD);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a pushed entry comes back through query, with its sequence")
    void pushThenQuery(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, SOURCE_AND_READER, clock)) {
            JsonNode pushed = core.codec.mapper().readTree(core.postSigned(
                    core.request("audit.push", """
                            {"action":"identity.linked","subjectId":"s-1",
                             "identityRef":"kind-a:id-1","gate":"gate.x",
                             "detail":{"proof":"link-code"}}
                            """), clock.instant()).body());
            assertTrue(pushed.get(Wire.OK).asBoolean(), pushed::toString);
            assertEquals(1L, pushed.get(Wire.PAYLOAD).get("sequence").asLong());

            JsonNode entries = core.codec.mapper().readTree(core.postSigned(
                    core.request("audit.query", "{}"), clock.instant()).body())
                    .get(Wire.PAYLOAD).get("entries");

            assertEquals(1, entries.size());
            JsonNode entry = entries.get(0);
            assertEquals("identity.linked", entry.get("action").asText());
            assertEquals("s-1", entry.get("subjectId").asText());
            assertEquals("gate.x", entry.get("gate").asText());
            assertEquals("link-code", entry.get("detail").get("proof").asText());

            // The actor, asserted on an entry that carries EVERY optional field.
            // The dedicated attribution test below pushes a minimal entry, so a
            // defect that only forged the actor when some other field was
            // present would slip past it -- which is exactly what a mutation
            // making the actor depend on subjectId did.
            assertEquals("connector:" + core.connector.id(), entry.get("actor").asText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the actor is the connector, and a caller cannot set it")
    void actorIsNotCallerControlled(Backend backend) throws Exception {
        // The load-bearing test in this file. A connector able to name its own
        // actor could attribute its actions to another connector, or to a
        // person, and an audit log whose attribution the subject controls is
        // not evidence of anything.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, SOURCE_AND_READER, clock)) {
            // "actor" is not a field of AuditPushRequest, so sending one is a
            // caller trying to set something the schema does not offer. It must
            // be refused, not quietly ignored: silently dropping it would let a
            // connector believe it had set the actor.
            JsonNode forged = core.codec.mapper().readTree(core.postSigned(
                    core.request("audit.push",
                            "{\"action\":\"identity.linked\",\"actor\":\"connector:someone-else\"}"),
                    clock.instant()).body());
            assertFalse(
                    forged.get(Wire.OK).asBoolean(),
                    "a payload naming an actor was accepted; the caller may believe it took");

            // And the honest pushes record the connector core resolved --
            // whatever else the payload does or does not carry. Both shapes,
            // because a defect that forged the actor only when some other field
            // was present would otherwise show up on neither.
            core.postSigned(core.request("audit.push", "{\"action\":\"identity.linked\"}"),
                    clock.instant());
            core.postSigned(core.request("audit.push",
                    "{\"action\":\"identity.linked\",\"subjectId\":\"s-9\","
                            + "\"identityRef\":\"kind-a:id-9\",\"gate\":\"gate.y\"}"),
                    clock.instant());

            JsonNode entries = payloadOf(core, clock, core.request("audit.query", "{}"))
                    .get("entries");
            assertEquals(2, entries.size());
            for (JsonNode entry : entries) {
                assertEquals(
                        "connector:" + core.connector.id(),
                        entry.get("actor").asText(),
                        "the actor must be the connector core authenticated, not anything the "
                                + "caller said or omitted");
            }
        }
    }

    @Test
    @DisplayName("audit.push requires audit-source, and audit.query requires config-management")
    void capabilitiesAreEnforced() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                StorageBackends.any(), tempDir, Set.of(Capability.AUDIT_SOURCE), clock)) {

            JsonNode pushed = core.codec.mapper().readTree(core.postSigned(
                    core.request("audit.push", "{\"action\":\"a.b\"}"), clock.instant()).body());
            assertTrue(pushed.get(Wire.OK).asBoolean(), pushed::toString);

            JsonNode queried = core.codec.mapper().readTree(core.postSigned(
                    core.request("audit.query", "{}"), clock.instant()).body());
            assertFalse(
                    queried.get(Wire.OK).asBoolean(),
                    "a connector that can WRITE audit could also read it; those are different "
                            + "permissions and conflating them hands every event source a "
                            + "window onto everything else");
            assertEquals(
                    ErrorCode.MISSING_CAPABILITY.wireName(),
                    queried.get(Wire.ERROR).get(Wire.ERROR_CODE).asText());
        }
    }

    @Test
    @DisplayName("an entry with no action is refused rather than recorded blank")
    void actionIsRequired() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(StorageBackends.any(), tempDir, SOURCE_AND_READER, clock)) {
            for (String body : new String[] {"{}", "{\"action\":\"\"}", "{\"action\":\"   \"}"}) {
                JsonNode json = core.codec.mapper().readTree(
                        core.postSigned(core.request("audit.push", body), clock.instant()).body());
                assertFalse(json.get(Wire.OK).asBoolean(), () -> "accepted " + body);
                assertEquals(
                        ErrorCode.INVALID_REQUEST.wireName(),
                        json.get(Wire.ERROR).get(Wire.ERROR_CODE).asText());
            }
            // Nothing was written, so a refused push leaves no trace to explain.
            assertEquals(
                    0,
                    payloadOf(core, clock, core.request("audit.query", "{}")).get("entries").size());
        }
    }

    @Test
    @DisplayName("a query asking for more than the ceiling gets the ceiling")
    void limitIsBounded() throws Exception {
        // An unbounded audit query against a long-lived deployment is a way to
        // exhaust the server's memory from an authenticated endpoint, and "the
        // caller asked nicely" is not a defence.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(StorageBackends.any(), tempDir, SOURCE_AND_READER, clock)) {
            for (int i = 0; i < 5; i++) {
                core.postSigned(
                        core.request("audit.push", "{\"action\":\"a.b\"}"), clock.instant());
            }
            JsonNode entries = payloadOf(
                    core, clock, core.request("audit.query", "{\"limit\":999999999}")).get("entries");
            assertEquals(5, entries.size(), "only five exist, so five come back");

            JsonNode limited = payloadOf(
                    core, clock, core.request("audit.query", "{\"limit\":2}")).get("entries");
            assertEquals(2, limited.size(), "an explicit smaller limit is honoured");
        }
    }

    @Test
    @DisplayName("filters narrow the result rather than being ignored")
    void filtersApply() throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(StorageBackends.any(), tempDir, SOURCE_AND_READER, clock)) {
            core.postSigned(core.request("audit.push",
                    "{\"action\":\"identity.linked\",\"subjectId\":\"s-1\"}"), clock.instant());
            core.postSigned(core.request("audit.push",
                    "{\"action\":\"identity.unlinked\",\"subjectId\":\"s-2\"}"), clock.instant());

            assertEquals(1, payloadOf(core, clock, core.request(
                    "audit.query", "{\"action\":\"identity.linked\"}")).get("entries").size());
            assertEquals(1, payloadOf(core, clock, core.request(
                    "audit.query", "{\"subjectId\":\"s-2\"}")).get("entries").size());
            assertEquals(2, payloadOf(core, clock, core.request(
                    "audit.query", "{}")).get("entries").size(),
                    "an unfiltered query still sees both, so the filters narrowed rather than "
                            + "the rows being absent");
        }
    }
}
