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
 * The Phase 2 gate over the wire: two connectors complete a full link, both
 * directions, through the real transport and the real authorization table.
 *
 * <p>Two <em>separate</em> connectors with <em>different</em> capabilities, not
 * one connector holding both. A single credential doing the whole flow would
 * prove the linking logic works while asserting nothing about the thing the
 * capability model exists for — that the side displaying a code and the side
 * accepting one are distinct principals.
 */
class LinkingWireTest {

    @TempDir
    Path tempDir;

    private JsonNode send(TestCore core, Clock clock, String op, String payload)
            throws Exception {
        return core.codec.mapper().readTree(
                core.postSigned(core.request(op, payload), clock.instant()).body());
    }

    private JsonNode ok(TestCore core, Clock clock, String op, String payload) throws Exception {
        JsonNode json = send(core, clock, op, payload);
        assertTrue(json.get(Wire.OK).asBoolean(), json::toString);
        return json.get(Wire.PAYLOAD);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("GATE: two connectors complete a full link over the wire")
    void twoConnectorsLink(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CODE_DISPLAY), clock)) {

            // A second connector on the same core, holding only code-entry.
            var entry = core.registerAnother(
                    "entry-connector", Set.of(Capability.CODE_ENTRY));

            JsonNode issued = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\","
                            + "\"display\":\"Alex\"}");
            String code = issued.get("code").asText();
            assertTrue(issued.get("expiresAtEpochSeconds").asLong() > clock.instant()
                    .getEpochSecond());

            JsonNode redeemed = core.codec.mapper().readTree(core.postSignedAs(
                    entry,
                    core.request("code.redeem",
                            "{\"code\":\"" + code + "\",\"platformKind\":\"kind-b\","
                                    + "\"platformId\":\"acct-2\",\"display\":\"Alex\"}"),
                    clock.instant()).body());

            assertTrue(redeemed.get(Wire.OK).asBoolean(), redeemed::toString);
            JsonNode payload = redeemed.get(Wire.PAYLOAD);
            assertFalse(payload.get("subjectId").asText().isBlank());
            assertEquals(
                    2, payload.get("identities").size(),
                    "the response carries the whole graph, so a connector showing 'you are "
                            + "linked' does not need a second round trip that could show a "
                            + "stale picture");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("GATE: the same flow with the sides reversed")
    void reversedDirection(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CODE_ENTRY), clock)) {

            var display = core.registerAnother(
                    "display-connector", Set.of(Capability.CODE_DISPLAY));

            JsonNode issued = core.codec.mapper().readTree(core.postSignedAs(
                    display,
                    core.request("code.issue",
                            "{\"platformKind\":\"kind-b\",\"platformId\":\"acct-2\"}"),
                    clock.instant()).body());
            assertTrue(issued.get(Wire.OK).asBoolean(), issued::toString);
            String code = issued.get(Wire.PAYLOAD).get("code").asText();

            JsonNode redeemed = ok(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"kind-a\","
                            + "\"platformId\":\"acct-1\"}");
            assertEquals(2, redeemed.get("identities").size());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the display connector cannot redeem, and the entry connector cannot issue")
    void capabilitiesAreDistinct(Backend backend) throws Exception {
        // The whole reason the two capabilities are separate. If either side
        // could do both, a compromised chat bot could mint a code for any
        // account and redeem it against one it controls.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir, Set.of(Capability.CODE_DISPLAY), clock)) {

            var entry = core.registerAnother("entry", Set.of(Capability.CODE_ENTRY));

            JsonNode displayRedeeming = send(core, clock, "code.redeem",
                    "{\"code\":\"BBBBBBBB\",\"platformKind\":\"k\",\"platformId\":\"i\"}");
            assertEquals(
                    ErrorCode.MISSING_CAPABILITY.wireName(),
                    displayRedeeming.get(Wire.ERROR).get(Wire.ERROR_CODE).asText());

            JsonNode entryIssuing = core.codec.mapper().readTree(core.postSignedAs(
                    entry, core.request("code.issue",
                            "{\"platformKind\":\"k\",\"platformId\":\"i\"}"),
                    clock.instant()).body());
            assertEquals(
                    ErrorCode.MISSING_CAPABILITY.wireName(),
                    entryIssuing.get(Wire.ERROR).get(Wire.ERROR_CODE).asText());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a refusal names which refusal it was, not merely that it failed")
    void refusalsAreSpecific(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir,
                Set.of(Capability.CODE_DISPLAY, Capability.CODE_ENTRY), clock)) {

            JsonNode unknown = send(core, clock, "code.redeem",
                    "{\"code\":\"BBBBBBBB\",\"platformKind\":\"k\",\"platformId\":\"i\"}");
            assertTrue(
                    unknown.get(Wire.ERROR).get(Wire.ERROR_MESSAGE).asText()
                            .startsWith("unknown-code"),
                    () -> "the caller must be able to tell the person WHICH problem it was: "
                            + unknown);

            String code = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\"}")
                    .get("code").asText();
            JsonNode sameAccount = send(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"kind-a\","
                            + "\"platformId\":\"acct-1\"}");
            assertTrue(
                    sameAccount.get(Wire.ERROR).get(Wire.ERROR_MESSAGE).asText()
                            .startsWith("same-account"),
                    sameAccount::toString);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("inspect, then unlink, then inspect again")
    void inspectAndUnlink(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir,
                Set.of(Capability.CODE_DISPLAY, Capability.CODE_ENTRY,
                        Capability.CONFIG_MANAGEMENT), clock)) {

            String code = ok(core, clock, "code.issue",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\"}")
                    .get("code").asText();
            ok(core, clock, "code.redeem",
                    "{\"code\":\"" + code + "\",\"platformKind\":\"kind-b\","
                            + "\"platformId\":\"acct-2\"}");

            JsonNode before = ok(core, clock, "subject.inspect",
                    "{\"platformKind\":\"kind-b\",\"platformId\":\"acct-2\"}");
            assertTrue(before.get("linked").asBoolean());
            assertEquals(2, before.get("identities").size());

            assertTrue(ok(core, clock, "identity.unlink",
                    "{\"platformKind\":\"kind-b\",\"platformId\":\"acct-2\"}")
                    .get("removed").asBoolean());

            JsonNode after = ok(core, clock, "subject.inspect",
                    "{\"platformKind\":\"kind-b\",\"platformId\":\"acct-2\"}");
            assertFalse(
                    after.get("linked").asBoolean(),
                    "an unlink that a later inspect cannot see is not an unlink");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("attest records the proof method it was given")
    void attestRecordsProof(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(
                backend, tempDir,
                Set.of(Capability.IDENTITY_PROVIDER, Capability.CONFIG_MANAGEMENT), clock)) {

            JsonNode attested = ok(core, clock, "attest",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\","
                            + "\"display\":\"Alex\",\"proofMethod\":\"oauth\"}");

            assertEquals("oauth", attested.get("proofMethod").asText());
            assertEquals(
                    clock.instant().getEpochSecond(),
                    attested.get("verifiedAtEpochSeconds").asLong(),
                    "policy is entitled to care about HOW something was proven and WHEN, so "
                            + "both are recorded rather than a bare boolean");

            JsonNode inspected = ok(core, clock, "subject.inspect",
                    "{\"platformKind\":\"kind-a\",\"platformId\":\"acct-1\"}");
            assertTrue(
                    inspected.get("linked").asBoolean(),
                    "an attested account is known, even though one identity is not a link");
            assertEquals(1, inspected.get("identities").size());
        }
    }
}
