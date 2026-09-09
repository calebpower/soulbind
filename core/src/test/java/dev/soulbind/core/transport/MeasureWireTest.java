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
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The measure operations over the wire.
 *
 * <p>Two claims carry the weight here.
 *
 * <p><b>Reporting is a mutation, so it re-evaluates in the same request.</b>
 * That is the whole answer to "what re-runs a trailing window when nobody has
 * done anything", and why core still has no timer. If the transition were not
 * emitted, a role would be granted the next time the subject happened to link
 * something else, or never.
 *
 * <p><b>Core stamps the observation time.</b> The request carries none, and it
 * must stay that way: freshness a connector can assert is freshness any
 * connector can extend, which would make a staleness rule opt-out.
 */
class MeasureWireTest {

    @TempDir
    Path tempDir;

    private static final Set<Capability> REPORTER = Set.of(Capability.MEASURE_SOURCE);
    private static final Set<Capability> ADMIN = Set.of(
            Capability.MEASURE_SOURCE, Capability.CONFIG_MANAGEMENT);

    private JsonNode call(TestCore core, Clock clock, String op, String body) throws Exception {
        return core.codec.mapper().readTree(
                core.postSigned(core.request(op, body), clock.instant()).body());
    }

    private JsonNode ok(TestCore core, Clock clock, String op, String body) throws Exception {
        JsonNode json = call(core, clock, op, body);
        assertTrue(json.get(Wire.OK).asBoolean(), json::toString);
        return json.get(Wire.PAYLOAD);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a reported measure reads back, stamped with core's clock")
    void reportThenGet(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":25200,"windowSeconds":604800}
                    """);

            JsonNode measures = ok(core, clock, "measure.get", """
                    {"platformKind":"game","platformId":"9f2c"}
                    """).get("measures");

            assertEquals(1, measures.size(), measures::toString);
            JsonNode m = measures.get(0);
            assertEquals("playtime", m.get("name").asText());
            assertEquals(25_200L, m.get("value").asLong());
            assertEquals(604_800L, m.get("windowSeconds").asLong());
            assertEquals(clock.instant().getEpochSecond(),
                    m.get("observedAtEpochSeconds").asLong(),
                    "the observation time must be core's, and the request carries none");
            assertTrue(m.get("reportedBy").asText().startsWith("connector:"),
                    m.get("reportedBy")::asText);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a second report replaces the first")
    void reportOverwrites(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":100,"windowSeconds":604800}
                    """);
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":999,"windowSeconds":604800}
                    """);

            JsonNode measures = ok(core, clock, "measure.get", """
                    {"platformKind":"game","platformId":"9f2c"}
                    """).get("measures");
            assertEquals(1, measures.size(), measures::toString);
            assertEquals(999L, measures.get(0).get("value").asLong());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("get narrows to one measure by name")
    void getByName(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":1,"windowSeconds":604800}
                    """);
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"posts",
                     "value":2,"windowSeconds":604800}
                    """);

            assertEquals(2, ok(core, clock, "measure.get", """
                    {"platformKind":"game","platformId":"9f2c"}
                    """).get("measures").size());

            JsonNode one = ok(core, clock, "measure.get", """
                    {"platformKind":"game","platformId":"9f2c","name":"posts"}
                    """).get("measures");
            assertEquals(1, one.size(), one::toString);
            assertEquals("posts", one.get(0).get("name").asText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("reporting writes no audit row; the transition it causes is the record")
    void reportingIsNotAudited(Backend backend) throws Exception {
        // A reporter running every few minutes over an active population writes
        // thousands of rows a week of pure telemetry, into a log designed to be
        // prunable and valuable because a human can read it. The precedent is
        // gateSeen on the decide hot path, which deliberately appends nothing.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":25200,"windowSeconds":604800}
                    """);

            JsonNode entries = ok(core, clock, "audit.query", "{}").get("entries");
            for (JsonNode entry : entries) {
                assertFalse(entry.get("action").asText().startsWith("measure."),
                        () -> "a routine report wrote an audit row: " + entry);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a report that crosses a threshold emits requirements-met in the same request")
    void reportEmitsTransition(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            ok(core, clock, "rule.set", """
                    {"gate":"activity.meeper","requireLinked":false,"requiredKinds":[],
                     "graceSeconds":0,"defaultEffect":"deny",
                     "measure":{"name":"playtime","atLeast":25200,
                                "windowSeconds":604800,"maxAgeSeconds":3600}}
                    """);

            // Below the threshold: nothing to hear about.
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":100,"windowSeconds":604800}
                    """);
            assertFalse(eventsMention(core, clock, "subject.requirements-met"),
                    "a report below the threshold announced that requirements were met");

            // Crossing it: the effector has to hear, and nothing else will tell
            // it -- no user action has occurred and there is no timer.
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":25200,"windowSeconds":604800}
                    """);
            assertTrue(eventsMention(core, clock, "subject.requirements-met"),
                    "crossing the threshold emitted nothing, so the role would never appear");

            // Falling back below it: the mirror, and the half that matters most,
            // because until it fires somebody holds a role a rule says they
            // should not.
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":1,"windowSeconds":604800}
                    """);
            assertTrue(eventsMention(core, clock, "subject.requirements-lost"),
                    "falling below the threshold emitted nothing, so the role would never come off");
        }
    }

    private boolean eventsMention(TestCore core, Clock clock, String type) throws Exception {
        JsonNode events = ok(core, clock, "event.subscribe", "{\"limit\":100}").get("events");
        for (JsonNode event : events) {
            if (type.equals(event.get("type").asText())) {
                return true;
            }
        }
        return false;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("reading measures needs config-management, reporting does not")
    void readingIsAdministrative(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, REPORTER, clock)) {
            ok(core, clock, "measure.report", """
                    {"platformKind":"game","platformId":"9f2c","name":"playtime",
                     "value":1,"windowSeconds":604800}
                    """);

            JsonNode refused = call(core, clock, "measure.get", """
                    {"platformKind":"game","platformId":"9f2c"}
                    """);
            assertFalse(refused.get(Wire.OK).asBoolean(), refused::toString);
            assertEquals(ErrorCode.MISSING_CAPABILITY.wireName(),
                    refused.get(Wire.ERROR).get("code").asText(),
                    "a reporter could read everybody's measurements");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a report missing its subject, its name or a usable window is refused")
    void invalidReports(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            for (String body : new String[] {
                """
                {"platformKind":"","platformId":"9f2c","name":"p","value":1,"windowSeconds":604800}
                """,
                """
                {"platformKind":"game","platformId":"","name":"p","value":1,"windowSeconds":604800}
                """,
                """
                {"platformKind":"game","platformId":"9f2c","name":"","value":1,"windowSeconds":604800}
                """,
                // A window covering no time cannot be compared against a rule's,
                // and zero is what an omitted field deserializes to -- the most
                // likely way to send one by accident.
                """
                {"platformKind":"game","platformId":"9f2c","name":"p","value":1,"windowSeconds":0}
                """,
            }) {
                JsonNode json = call(core, clock, "measure.report", body);
                assertFalse(json.get(Wire.OK).asBoolean(), () -> "accepted: " + body);
                assertEquals(ErrorCode.INVALID_REQUEST.wireName(),
                        json.get(Wire.ERROR).get("code").asText(), json::toString);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a rule with no measure renders exactly as it did before measures existed")
    void ruleWithoutMeasureIsByteIdentical(Backend backend) throws Exception {
        // The compatibility claim, asserted on the bytes rather than assumed. A
        // null component renders as "measure":null by default, which would
        // change every rule.get response ever written -- so the inclusion is
        // scoped to the type in Codec, and this is what holds it there.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            ok(core, clock, "rule.set", """
                    {"gate":"gate.linked","requireLinked":false,
                     "requiredKinds":["kind-a","kind-b"],"graceSeconds":0,
                     "defaultEffect":"deny"}
                    """);

            String rendered = ok(core, clock, "rule.get", "{\"gate\":\"gate.linked\"}")
                    .toString();
            assertFalse(rendered.contains("measure"),
                    () -> "a measure-free rule mentioned a measure on the wire: " + rendered);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a rule WITH a measure round-trips through set and get")
    void ruleWithMeasureRoundTrips(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            ok(core, clock, "rule.set", """
                    {"gate":"activity.meeper","requireLinked":false,"requiredKinds":[],
                     "graceSeconds":0,"defaultEffect":"deny",
                     "measure":{"name":"playtime","atLeast":25200,
                                "windowSeconds":604800,"maxAgeSeconds":3600}}
                    """);

            JsonNode measure = ok(core, clock, "rule.get", "{\"gate\":\"activity.meeper\"}")
                    .get("measure");
            assertEquals("playtime", measure.get("name").asText());
            assertEquals(25_200L, measure.get("atLeast").asLong());
            assertEquals(604_800L, measure.get("windowSeconds").asLong());
            assertEquals(3_600L, measure.get("maxAgeSeconds").asLong());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a rule whose measure is nonsense is refused, not stored")
    void nonsensicalMeasureIsRefused(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            // maxAgeSeconds of zero: a requirement whose answer never goes stale,
            // which is the "role outlives its evidence" failure with no bound.
            JsonNode json = call(core, clock, "rule.set", """
                    {"gate":"activity.meeper","requireLinked":false,"requiredKinds":[],
                     "graceSeconds":0,"defaultEffect":"deny",
                     "measure":{"name":"playtime","atLeast":1,
                                "windowSeconds":604800,"maxAgeSeconds":0}}
                    """);
            assertFalse(json.get(Wire.OK).asBoolean(), json::toString);
            assertEquals(ErrorCode.INVALID_REQUEST.wireName(),
                    json.get(Wire.ERROR).get("code").asText(), json::toString);
        }
    }
}
