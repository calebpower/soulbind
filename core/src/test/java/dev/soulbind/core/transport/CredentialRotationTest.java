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

import dev.soulbind.core.storage.Backend;
import dev.soulbind.core.storage.StorageBackends;
import dev.soulbind.protocol.Capability;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rotating a credential, and what must be true the instant it happens.
 *
 * <p>Until this existed, the only way to retire a leaked connector credential
 * was to register the connector again under a new name — leaving the leaked one
 * working, which is the opposite of what an operator wants at that moment.
 *
 * <p>The property that matters is not that a new credential appears. It is that
 * <b>the old one stops working immediately</b>, and that is what these assert.
 */
class CredentialRotationTest {

    @TempDir Path tempDir;

    private static final Set<Capability> ADMIN = Set.of(Capability.CONFIG_MANAGEMENT);

    private static String rotate(TestCore core, String name) {
        return core.request("connector.rotate", "{\"name\":\"" + name + "\"}");
    }

    private static String heartbeat(TestCore core) {
        return core.request("heartbeat", "{}");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the old credential stops working the moment it is rotated")
    void theOldCredentialDies(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            String victimBefore = core.registerAnother(
                    "victim", Set.of(Capability.CODE_DISPLAY));

            // It works to begin with, or the assertion below proves nothing:
            // a credential that never worked also "stops working".
            HttpResponse<String> before =
                    core.postSignedAs(victimBefore, heartbeat(core), clock.instant());
            assertEquals(200, before.statusCode());
            assertTrue(before.body().contains("\"ok\":true"),
                    () -> "the credential did not work before rotation: " + before.body());

            HttpResponse<String> rotated =
                    core.postSigned(rotate(core, "victim"), clock.instant());
            assertTrue(rotated.body().contains("\"ok\":true"),
                    () -> "rotation itself was refused: " + rotated.body());

            HttpResponse<String> after =
                    core.postSignedAs(victimBefore, heartbeat(core), clock.instant());
            assertFalse(after.body().contains("\"ok\":true"),
                    () -> "the OLD credential still works after rotation. That is the whole"
                            + " point of the operation: it exists because a credential is in"
                            + " the wrong hands, and a grace period is exactly what is not"
                            + " wanted then. Response: " + after.body());
            assertTrue(after.body().contains("unknown-credential"),
                    () -> "the refusal does not say the credential is unknown: " + after.body());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the new credential works, and is not the old one")
    void theNewCredentialWorks(Backend backend) throws Exception {
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            String before = core.registerAnother("victim", Set.of(Capability.CODE_DISPLAY));

            HttpResponse<String> rotated =
                    core.postSigned(rotate(core, "victim"), clock.instant());
            String body = rotated.body();
            assertTrue(body.contains("\"credential\""),
                    () -> "rotation returned no credential: " + body);

            String after = body.replaceAll(".*\"credential\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            assertFalse(after.equals(before),
                    "rotation handed back the credential it was replacing");

            HttpResponse<String> works =
                    core.postSignedAs(after, heartbeat(core), clock.instant());
            assertTrue(works.body().contains("\"ok\":true"),
                    () -> "the NEW credential does not work, so rotation locked the connector"
                            + " out rather than re-keying it: " + works.body());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a rotation leaves an audit row naming the connector")
    void rotationIsAudited(Backend backend) throws Exception {
        // §14 asks for rotation to be "an admin operation with an audit row".
        // A credential change nobody can account for afterwards is exactly what
        // an incident review is looking for, and this is the operation most
        // likely to be performed during one.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            core.registerAnother("victim", Set.of(Capability.CODE_DISPLAY));
            core.postSigned(rotate(core, "victim"), clock.instant());

            HttpResponse<String> audit = core.postSigned(
                    core.request("audit.query", "{\"limit\":50}"), clock.instant());
            assertTrue(audit.body().contains("connector.rotated"),
                    () -> "no audit row for the rotation: " + audit.body());
            assertTrue(audit.body().contains("victim"),
                    () -> "the audit row does not name which connector was rotated: "
                            + audit.body());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an admin can rotate its own credential, and gets the new one back")
    void rotatingYourself(Backend backend) throws Exception {
        // The likeliest real rotation of all: the admin credential itself has
        // leaked, and the only credential able to authorize the rotation is the
        // one being rotated. It works because the request authenticates before
        // the handler runs -- but the caller is cut off the instant the response
        // is written, so a lost response means re-registering rather than
        // rotating again. Asserted here because that ordering is easy to break
        // by moving authentication after dispatch, and the symptom would be an
        // operator locked out of their own core during an incident.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            String selfName = core.connectorName();

            HttpResponse<String> rotated =
                    core.postSigned(rotate(core, selfName), clock.instant());
            assertTrue(rotated.body().contains("\"ok\":true"),
                    () -> "an admin could not rotate its own credential: " + rotated.body());

            String fresh = rotated.body()
                    .replaceAll(".*\"credential\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            HttpResponse<String> works =
                    core.postSignedAs(fresh, heartbeat(core), clock.instant());
            assertTrue(works.body().contains("\"ok\":true"),
                    () -> "the credential handed back by a self-rotation does not work, which"
                            + " locks the operator out of their own core: " + works.body());

            // And the credential that signed the rotation is gone.
            HttpResponse<String> old =
                    core.postSigned(heartbeat(core), clock.instant());
            assertFalse(old.body().contains("\"ok\":true"),
                    () -> "the admin's previous credential still works: " + old.body());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("rotating a connector that does not exist says which name")
    void unknownConnectorIsNamed(Backend backend) throws Exception {
        // An operator rotating a credential in a hurry has usually mistyped,
        // and "not found" without the name sends them to check the wrong thing.
        Clock clock = TestCore.fixedClock();
        try (TestCore core = new TestCore(backend, tempDir, ADMIN, clock)) {
            HttpResponse<String> response =
                    core.postSigned(rotate(core, "no-such-connector"), clock.instant());

            assertFalse(response.body().contains("\"ok\":true"));
            assertTrue(response.body().contains("no-such-connector"),
                    () -> "the refusal does not name the connector that was not found: "
                            + response.body());
        }
    }
}
