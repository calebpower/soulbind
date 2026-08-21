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
import dev.soulbind.core.storage.StorageBackends;
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
 * Guessing codes over the wire, and the one refusal that counts as a guess.
 *
 * <p>The unit test covers the counting. This covers the wiring, which is where
 * the mistake would actually be made: counting the wrong refusals would
 * throttle people who are not guessing at all — somebody whose code expired
 * while they walked to the other platform, or who tried to redeem a code
 * already used, both of which are ordinary and neither of which is an attack.
 */
class RedeemThrottleWireTest {

    @TempDir Path tempDir;

    private static JsonNode send(TestCore core, Clock clock, String op, String payload)
            throws Exception {
        return core.codec.mapper().readTree(
                core.postSigned(core.request(op, payload), clock.instant()).body());
    }

    private static String redeem(String code, String id) {
        return "{\"code\":\"" + code + "\",\"platformKind\":\"kind-b\","
                + "\"platformId\":\"" + id + "\",\"display\":\"Someone\"}";
    }

    private static boolean throttled(JsonNode response) {
        return !response.get(Wire.OK).asBoolean()
                && response.toString().contains("too many wrong codes");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an account guessing codes is cut off, and says so in words a person can act on")
    void guessingIsThrottled(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CODE_ENTRY), clock)) {

            JsonNode seen = null;
            for (int attempt = 0; attempt < 40; attempt++) {
                // Well-formed codes from the real alphabet that simply do not
                // exist -- which is what guessing looks like.
                JsonNode response = send(core, clock, "code.redeem",
                        redeem("BCDF" + String.format("%04d", attempt % 10000)
                                .replace('0', '2').replace('1', '3'), "guesser"));
                if (throttled(response)) {
                    seen = response;
                    break;
                }
            }

            final JsonNode cutOff = seen;
            assertTrue(cutOff != null,
                    "forty wrong codes from one account were all accepted for processing;"
                            + " nothing bounds guessing");
            assertTrue(cutOff.toString().contains("minutes"),
                    () -> "the refusal does not tell the person how long to wait: " + cutOff);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("only the guessing account is cut off")
    void othersAreUnaffected(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CODE_ENTRY), clock)) {

            for (int attempt = 0; attempt < 40; attempt++) {
                send(core, clock, "code.redeem", redeem("BCDF2345", "guesser"));
            }
            assertTrue(throttled(send(core, clock, "code.redeem",
                    redeem("BCDF2345", "guesser"))));

            JsonNode bystander = send(core, clock, "code.redeem",
                    redeem("BCDF2345", "innocent-bystander"));
            assertFalse(throttled(bystander),
                    () -> "one account guessing locked out another on the same connector,"
                            + " which would take a platform down over one abuser: "
                            + bystander);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a redeem refused for any OTHER reason is not counted as a guess")
    void onlyUnknownCodesCount(Backend backend) throws Exception {
        // The mistake worth guarding: an expired code, or one already redeemed,
        // means the person HAD a real code. Counting those would throttle
        // somebody who walked between two platforms too slowly.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CODE_DISPLAY, Capability.CODE_ENTRY),
                clock)) {

            for (int attempt = 0; attempt < 40; attempt++) {
                // Issue a code and redeem it as the SAME account: refused
                // same-account every time, and never a guess.
                JsonNode issued = send(core, clock, "code.issue",
                        "{\"platformKind\":\"kind-a\",\"platformId\":\"self-"
                                + attempt + "\",\"display\":\"Alex\"}");
                String code = issued.get(Wire.PAYLOAD).get("code").asText();

                JsonNode refused = send(core, clock, "code.redeem",
                        "{\"code\":\"" + code + "\",\"platformKind\":\"kind-a\","
                                + "\"platformId\":\"self-" + attempt + "\","
                                + "\"display\":\"Alex\"}");
                assertFalse(throttled(refused),
                        () -> "a same-account refusal was counted as a guess, so somebody"
                                + " who never guessed anything is now locked out: " + refused);
            }
        }
    }
}
