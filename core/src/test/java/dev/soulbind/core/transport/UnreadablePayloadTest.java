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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.registry.Authorizer.Operation;
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.Wire;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A payload that cannot be read says so, and does not blame a field.
 *
 * <p>Every handler used to answer with the SAME message for two different
 * faults, because {@code request.isEmpty()} and {@code blank(field)} shared one
 * branch:
 *
 * <pre>
 *   if (request.isEmpty() || blank(request.get().gate())) {
 *       return WireResponse.error(INVALID_REQUEST, "rule.set names a gate");
 *   }
 * </pre>
 *
 * <p>So a caller who sent a field this build does not recognise was told the
 * gate was missing — while the gate sat there in the request, spelled
 * correctly. That is not a cosmetic complaint: it sends somebody to check the
 * one part of their payload that is definitely right.
 *
 * <p>It happened. A harness added a {@code detail} field to {@code rule.set},
 * the bind failed, and the reply said {@code rule.set names a gate}. The detour
 * that cost is the reason this test exists.
 */
class UnreadablePayloadTest {

    @TempDir
    Path tempDir;

    private JsonNode refusal(TestCore core, Clock clock, String op, String payload)
            throws Exception {
        JsonNode json = core.codec.mapper().readTree(
                core.postSigned(core.request(op, payload), clock.instant()).body());
        assertFalse(json.get(Wire.OK).asBoolean(), () -> op + " was accepted: " + json);
        return json.get(Wire.ERROR);
    }

    /**
     * Operations that bind no payload, so an unknown field cannot fail them.
     *
     * <p>Written out by hand rather than derived. An operation that stops
     * validating its payload would otherwise be added to this set by the very
     * code that broke it, and the test would agree.
     */
    private static final Set<Operation> BIND_NOTHING = Set.of(
            // Answers from the registry and the runtime config with no request
            // shape at all. There is nothing to misread.
            Operation.CONNECTOR_LIST,
            Operation.CONFIG_GET,

            // A liveness ping. It writes last-seen and answers with the time,
            // reading nothing -- deliberately, because a heartbeat that touched
            // anything else would let a flapping connector rewrite its own row.
            // Ignoring an unknown field is the right behaviour here and the
            // only place in the table where it is.
            Operation.HEARTBEAT);

    static java.util.stream.Stream<Operation> bindingOperations() {
        return CoreHandlers.implemented().stream().filter(op -> !BIND_NOTHING.contains(op));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindingOperations")
    @DisplayName("EVERY operation that binds a payload refuses one it cannot read, and says so")
    void everyOperationRefusesAnUnreadablePayload(Operation operation) throws Exception {
        // Fifty-four mutants lived in these branches -- one `unreadable(...)`
        // per operation, and almost none of them executed by anything. Two
        // guesses about why were both wrong: they are not the MariaDB-only
        // paths, and re-running with the fuzz tier included moved the number by
        // four. They were simply untested.
        //
        // Parameterised over `implemented()` so an operation added tomorrow is
        // covered the day it exists, rather than when somebody remembers.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend(), tempDir, EnumSet.allOf(Capability.class), clock)) {

            JsonNode json = core.codec.mapper().readTree(
                    core.postSigned(
                            core.request(
                                    operation.wireName(),
                                    "{\"soulbind-no-such-field\":\"anything\"}"),
                            clock.instant()).body());

            assertFalse(json.get(Wire.OK).asBoolean(),
                    () -> operation.wireName() + " ACCEPTED a payload it cannot have"
                            + " understood. A field this build does not know was ignored,"
                            + " so a caller sending the wrong shape is told nothing: " + json);

            String message = json.get(Wire.ERROR).path(Wire.ERROR_MESSAGE).asText("");
            assertFalse(message.isBlank(),
                    () -> operation.wireName() + " refused with no explanation at all");

            // A CLEAN refusal, not a crash reported as one. A handler that
            // tested `!request.isEmpty()` by mistake would reach through an
            // empty Optional, throw, and the dispatcher would answer `internal`
            // -- still a refusal, still ok:false, and indistinguishable from
            // this test's point of view unless the code is checked too.
            String code = json.get(Wire.ERROR).path(Wire.ERROR_CODE).asText("");
            assertNotEquals(ErrorCode.INTERNAL.wireName(), code,
                    () -> operation.wireName() + " answered an unreadable payload with an"
                            + " internal error, which means it threw rather than refused: "
                            + message);
        }
    }

    /** One backend is enough here: this is about payload binding, not persistence. */
    private static Backend backend() {
        return StorageBackends.any();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an unrecognised field is reported as an unreadable payload, not a missing gate")
    void unknownFieldIsNotReportedAsAMissingField(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CONFIG_MANAGEMENT), clock)) {

            // The gate is present and correct. Only `detail` is unknown.
            JsonNode error = refusal(core, clock, "rule.set",
                    "{\"gate\":\"gate.post\",\"requireLinked\":true,\"requiredKinds\":[],"
                            + "\"graceSeconds\":0,\"defaultEffect\":\"deny\","
                            + "\"detail\":\"a field this build does not have\"}");

            String message = error.path(Wire.ERROR_MESSAGE).asText("");

            assertTrue(
                    message.contains("could not read the payload"),
                    () -> "an unreadable payload must say so; got: " + message);
            assertFalse(
                    message.contains("names a gate"),
                    () -> "the payload named its gate, and the refusal claimed otherwise. That "
                            + "sends an operator to check the one part that is correct. Got: "
                            + message);
            assertTrue(
                    message.contains("RuleView"),
                    () -> "the refusal should name the shape it could not read, since the codec "
                            + "cannot say which key was at fault; got: " + message);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a genuinely blank gate is still reported as a blank gate")
    void aBlankGateIsStillReportedAsSuch(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CONFIG_MANAGEMENT), clock)) {

            // Readable, and missing the one thing the handler needs. Without
            // this half, splitting the branch could have been "done" by
            // reporting everything as unreadable -- which loses the specific
            // message that was right all along.
            JsonNode error = refusal(core, clock, "rule.set",
                    "{\"gate\":\"\",\"requireLinked\":true,\"requiredKinds\":[],"
                            + "\"graceSeconds\":0,\"defaultEffect\":\"deny\"}");

            String message = error.path(Wire.ERROR_MESSAGE).asText("");

            assertTrue(
                    message.contains("names a gate"),
                    () -> "a blank gate must still be named as the problem; got: " + message);
            assertFalse(
                    message.contains("could not read the payload"),
                    () -> "this payload read fine; it was simply missing its gate. Got: "
                            + message);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a heartbeat records that the connector was seen")
    void heartbeatTouchesLastSeen(Backend backend) throws Exception {
        // The one line the heartbeat handler has, and it could be deleted with
        // nothing failing. Without it `connector.list` reports every connector
        // as never seen, and the operator's only way to spot one that has
        // stopped calling home stops working -- silently, and in the direction
        // where everything looks fine.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CONFIG_MANAGEMENT), clock)) {

            assertTrue(
                    core.storage.connectors().findByName(core.connector.name()).orElseThrow()
                            .lastSeenAt() == null,
                    "a connector that has never called was already recorded as seen");

            JsonNode json = core.codec.mapper().readTree(
                    core.postSigned(core.request("heartbeat", "{}"), clock.instant()).body());
            assertTrue(json.get(Wire.OK).asBoolean(), json::toString);

            assertTrue(
                    core.storage.connectors().findByName(core.connector.name()).orElseThrow()
                            .lastSeenAt() != null,
                    "a heartbeat did not record that the connector was seen, so connector.list"
                            + " will report it as never having called");
        }
    }
}
