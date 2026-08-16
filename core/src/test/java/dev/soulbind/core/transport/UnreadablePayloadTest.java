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
}
