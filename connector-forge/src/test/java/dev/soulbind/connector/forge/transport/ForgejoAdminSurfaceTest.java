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
package dev.soulbind.connector.forge.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.connector.forge.ForgeSurface;
import io.javalin.Javalin;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The host adapter, against a stub standing in for the forge.
 *
 * <p>Not protocol-faithful fakery of somebody else's product -- that rots the
 * moment they change it. This asserts only the part this class owns: the
 * mapping from the status codes the host's published schema documents to the
 * three answers the seam defines, and that nothing typed into a field can
 * change the shape of the request carrying it.
 *
 * <p>The mapping is where the damage would be. A 422 read as success hands
 * somebody an account that was never theirs; a 500 read as ABSENT creates one
 * on top of an existing account.
 */
class ForgejoAdminSurfaceTest {

    private final List<String> logged = new ArrayList<>();
    private final List<String> seenAuth = new ArrayList<>();
    private final List<String> seenBodies = new ArrayList<>();
    private Javalin stub;

    @AfterEach
    void stop() {
        if (stub != null) {
            stub.stop();
        }
    }

    /** A forge that answers these codes, in order, to any request. */
    private ForgejoAdminSurface surfaceAnswering(int... codes) {
        AtomicInteger next = new AtomicInteger();
        stub = Javalin.create(c -> c.showJavalinBanner = false);
        stub.before(ctx -> {
            seenAuth.add(String.valueOf(ctx.header("Authorization")));
            seenBodies.add(ctx.body());
        });
        stub.get("/api/v1/users/{name}", ctx -> ctx.status(code(codes, next)).result("{}"));
        stub.post("/api/v1/admin/users", ctx -> ctx.status(code(codes, next)).result("{}"));
        stub.patch("/api/v1/admin/users/{name}", ctx -> ctx.status(code(codes, next)).result("{}"));
        stub.start("127.0.0.1", 0);
        return surface("http://127.0.0.1:" + stub.port());
    }

    private static int code(int[] codes, AtomicInteger next) {
        int i = next.getAndIncrement();
        return codes[Math.min(i, codes.length - 1)];
    }

    private ForgejoAdminSurface surface(String baseUrl) {
        BiConsumer<String, Throwable> log = (m, t) -> logged.add(m);
        return new ForgejoAdminSurface(baseUrl, "a-secret-token", Duration.ofSeconds(5), log);
    }

    private static ForgeSurface.Account account() {
        return new ForgeSurface.Account("ada", "ada@example.invalid", "a-passphrase");
    }

    // --- presence --------------------------------------------------------------

    @Test
    @DisplayName("200 is present, 404 is absent")
    void presenceReadsTheTwoDocumentedCodes() {
        assertEquals(ForgeSurface.Presence.PRESENT, surfaceAnswering(200).presence("ada"));
        stop();
        stub = null;
        assertEquals(ForgeSurface.Presence.ABSENT, surfaceAnswering(404).presence("ada"));
    }

    @Test
    @DisplayName("anything else is UNKNOWN, because declining to answer is not answering no")
    void unexpectedCodeIsUnknown() {
        // The failure this prevents: a 500 or a 502 from a proxy read as ABSENT,
        // and an account created on top of one that already exists.
        assertEquals(ForgeSurface.Presence.UNKNOWN, surfaceAnswering(500).presence("ada"));
        assertTrue(logged.stream().anyMatch(m -> m.contains("unknown")), logged::toString);
    }

    @Test
    @DisplayName("a forge that is not there at all is UNKNOWN")
    void unreachableForgeIsUnknown() {
        // Port 1 on loopback: nothing listens, and the connection is refused
        // rather than left to time out.
        assertEquals(ForgeSurface.Presence.UNKNOWN,
                surface("http://127.0.0.1:1").presence("ada"));
        assertTrue(logged.stream().anyMatch(m -> m.contains("could not reach")),
                logged::toString);
    }

    // --- creation --------------------------------------------------------------

    @Test
    @DisplayName("201 is created")
    void createdOn201() {
        assertEquals(ForgeSurface.Creation.CREATED, surfaceAnswering(201).create(account()));
    }

    @Test
    @DisplayName("422 with the name now present means it was taken")
    void validationFailureWithNamePresentIsAlreadyExists() {
        // 422 covers a taken name AND a rejected email. Told apart by asking
        // again rather than by reading the host's prose, which is not a
        // contract: the first answer is the POST, the second the lookup.
        assertEquals(ForgeSurface.Creation.ALREADY_EXISTS,
                surfaceAnswering(422, 200).create(account()));
    }

    @Test
    @DisplayName("422 with the name still free is a failure, not a taken name")
    void validationFailureWithNameFreeIsFailure() {
        // Somebody mistyped their email. Reporting "that name is taken" would
        // send them to choose a different username for no reason.
        assertEquals(ForgeSurface.Creation.FAILED,
                surfaceAnswering(422, 404).create(account()));
        assertTrue(logged.stream().anyMatch(m -> m.contains("still free")), logged::toString);
    }

    @Test
    @DisplayName("any other code is a failure, never a success")
    void otherCodesFail() {
        assertEquals(ForgeSurface.Creation.FAILED, surfaceAnswering(403).create(account()));
    }

    // --- activation ------------------------------------------------------------

    @Test
    @DisplayName("200 on the patch is success; anything else is not")
    void activationReadsTheDocumentedCode() {
        assertTrue(surfaceAnswering(200).setActive("ada", false));
        stop();
        stub = null;
        logged.clear();
        assertFalse(surfaceAnswering(404).setActive("ada", false));
    }

    @Test
    @DisplayName("the patch asks for the state it was told to, as a JSON boolean")
    void activationSendsABoolean() {
        // The host's schema wants a boolean here. Sending the string "false"
        // would be accepted as a non-empty value by some readers and would
        // activate the account it was meant to disable.
        surfaceAnswering(200).setActive("ada", false);

        assertTrue(seenBodies.stream().anyMatch(b -> b.contains("\"active\":false")),
                () -> seenBodies.toString());
    }

    // --- the token and the request shape ---------------------------------------

    @Test
    @DisplayName("the token travels in the scheme the host publishes, and never to the log")
    void tokenIsSentButNeverLogged() {
        surfaceAnswering(500).presence("ada");

        assertTrue(seenAuth.stream().anyMatch(a -> a.equals("token a-secret-token")),
                seenAuth::toString);
        assertTrue(logged.stream().noneMatch(m -> m.contains("a-secret-token")),
                () -> "the admin token reached the log: " + logged);
    }

    @Test
    @DisplayName("a base URL with a trailing slash does not produce a doubled one")
    void trailingSlashOnTheBaseUrlIsTrimmed() {
        // A trailing slash in a pasted URL is the most ordinary configuration
        // mistake there is, and `//api/v1` is a 404 that looks like a missing
        // endpoint rather than a typo.
        stub = Javalin.create(c -> c.showJavalinBanner = false);
        List<String> paths = new ArrayList<>();
        stub.before(ctx -> paths.add(ctx.path()));
        stub.get("/api/v1/users/{name}", ctx -> ctx.status(200).result("{}"));
        stub.start("127.0.0.1", 0);

        new ForgejoAdminSurface("http://127.0.0.1:" + stub.port() + "/", "t",
                Duration.ofSeconds(5), (m, t) -> logged.add(m)).presence("ada");

        assertEquals(List.of("/api/v1/users/ada"), paths,
                () -> "the request went somewhere other than the host's API: " + paths);
    }

    @Test
    @DisplayName("a forge that cannot be reached fails the creation rather than claiming one")
    void unreachableForgeFailsCreation() {
        assertEquals(ForgeSurface.Creation.FAILED,
                surface("http://127.0.0.1:1").create(account()));
        assertTrue(logged.stream().anyMatch(m -> m.contains("could not reach")),
                logged::toString);
    }

    @Test
    @DisplayName("an unexpected code on creation is reported with the code itself")
    void unexpectedCreationCodeIsNamed() {
        // "it did not work" sends somebody to read this connector's source. The
        // status is the one fact that points at the host instead.
        surfaceAnswering(403).create(account());

        assertTrue(logged.stream().anyMatch(m -> m.contains("403")),
                () -> "the status the forge gave was dropped: " + logged);
    }

    @Test
    @DisplayName("a failed activation says which direction it was attempting")
    void failedActivationNamesTheDirection() {
        // The log is the only record of what was being attempted, and one that
        // said "activate" when it meant the reverse sends somebody to the wrong
        // half of this connector.
        surfaceAnswering(500).setActive("ada", false);

        assertTrue(logged.stream().anyMatch(m -> m.contains("deactivate")),
                () -> "the direction was wrong or absent: " + logged);
    }

    @Test
    @DisplayName("an unreachable forge fails the activation, and names what it was doing")
    void unreachableForgeFailsActivation() {
        // The one path through setActive that no other test reaches. It matters
        // because the effector treats false as "stop and do not acknowledge",
        // so this is the branch that decides whether a deactivation is retried
        // or quietly forgotten.
        assertFalse(surface("http://127.0.0.1:1").setActive("ada", false));

        assertTrue(logged.stream().anyMatch(
                m -> m.contains("could not reach") && m.contains("deactivate")),
                () -> "the attempt was not described, so a retry has nothing to go on: "
                        + logged);
    }

    @Test
    @DisplayName("the administrator flag is read from the host's answer, both ways round")
    void roleReadsTheFlag() {
        stub = Javalin.create(c -> c.showJavalinBanner = false);
        stub.get("/api/v1/users/boss",
                ctx -> ctx.status(200).result("{\"login\":\"boss\",\"is_admin\":true}"));
        stub.get("/api/v1/users/ada",
                ctx -> ctx.status(200).result("{\"login\":\"ada\",\"is_admin\":false}"));
        stub.start("127.0.0.1", 0);
        ForgejoAdminSurface forge = surface("http://127.0.0.1:" + stub.port());

        assertEquals(ForgeSurface.Role.ADMINISTRATOR, forge.role("boss"));
        assertEquals(ForgeSurface.Role.ORDINARY, forge.role("ada"));
    }

    @Test
    @DisplayName("an unreadable or absent answer is UNKNOWN, never ORDINARY")
    void roleIsUnknownRatherThanOrdinary() {
        // ORDINARY is the reading that deactivates the last administrator.
        assertEquals(ForgeSurface.Role.UNKNOWN, surfaceAnswering(500).role("ada"));
        stop();
        stub = null;
        assertEquals(ForgeSurface.Role.UNKNOWN, surfaceAnswering(404).role("ada"));
        stop();
        stub = null;
        assertEquals(ForgeSurface.Role.UNKNOWN, surface("http://127.0.0.1:1").role("ada"));
    }

    @Test
    @DisplayName("a pretty-printed body reads the same as a compact one")
    void theFlagSurvivesPrettyPrinting() {
        // The narrowing this class states: matched rather than parsed, so
        // whitespace is removed first. Both forms are asserted because only one
        // of them is what any given host version happens to emit.
        assertTrue(ForgejoAdminSurface.administratorFlagIsSet("{\"is_admin\":true}"));
        assertTrue(ForgejoAdminSurface.administratorFlagIsSet(
                "{\n  \"login\": \"boss\",\n  \"is_admin\": true\n}"));
        assertFalse(ForgejoAdminSurface.administratorFlagIsSet("{\"is_admin\": false}"));
        assertFalse(ForgejoAdminSurface.administratorFlagIsSet("{\"login\":\"ada\"}"));
    }

    @Test
    @DisplayName("the JSON is exactly right, not merely containing the right pieces")
    void jsonIsExact() {
        // Asserted by equality rather than by `contains`: a misplaced comma
        // leaves every substring check passing and the document unparseable.
        assertEquals("{\"a\":\"1\",\"b\":\"2\"}",
                ForgejoAdminSurface.json("a", "1", "b", "2"));
        assertEquals("{\"only\":\"one\"}", ForgejoAdminSurface.json("only", "one"));
    }

    @Test
    @DisplayName("nothing in a field can change the shape of the request carrying it")
    void valuesCannotBreakOutOfTheJson() {
        String hostile = "ada\", \"admin\": true, \"x\": \"";

        String body = ForgejoAdminSurface.json("username", hostile, "email", "a@b.invalid");

        assertFalse(body.contains("\"admin\": true"),
                () -> "a field value became a second field, which is how an ordinary signup "
                        + "asks to be an administrator: " + body);
        assertTrue(body.contains("ada\\\", \\\"admin"), body);
    }

    @Test
    @DisplayName("a true or false value is a JSON boolean; everything else is a string")
    void booleansAreNotQuoted() {
        String body = ForgejoAdminSurface.json("a", "true", "b", "false", "c", "truthy");

        assertTrue(body.contains("\"a\":true"), body);
        assertTrue(body.contains("\"b\":false"), body);
        assertTrue(body.contains("\"c\":\"truthy\""),
                () -> "a value that merely looks boolean was unquoted: " + body);
    }

    @Test
    @DisplayName("control characters are escaped rather than emitted raw")
    void controlCharactersAreEscaped() {
        String body = ForgejoAdminSurface.json("k", "a" + (char) 1 + "b");

        assertTrue(body.contains("u0001"), body);
    }
}
