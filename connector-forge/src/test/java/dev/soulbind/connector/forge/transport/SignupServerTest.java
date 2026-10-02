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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.connector.forge.ForgeSurface;
import dev.soulbind.connector.forge.Registration;
import dev.soulbind.connector.forge.ScriptedSurface;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The listener, over a real socket.
 *
 * <p>This is the one tier that needs one. Everything it routes to is pure and
 * asserted without a server; what is left -- that a form body is decoded, that
 * the status a person receives is the status that was decided, and that a fault
 * does not reach them as a stack trace -- is only observable through an actual
 * request.
 *
 * <p>In the transport package on purpose, so the seam guard's exemption covers
 * these tests exactly as it covers the class they exercise.
 */
class SignupServerTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    private static final String PATH = "/soulbind/signup";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final java.util.List<String> logged = new java.util.ArrayList<>();

    private static String allow() {
        return "{" + '"' + "schema" + '"' + ":1," + '"' + "ok" + '"' + ":true," + '"' + "payload"
                + '"' + ":{" + '"' + "effect" + '"' + ":" + '"' + "allow" + '"' + "," + '"'
                + "reason" + '"' + ":" + '"' + "requirements-met" + '"' + "," + '"' + "detail"
                + '"' + ":" + '"' + "ok" + '"' + "," + '"' + "ttlSeconds" + '"' + ":60," + '"'
                + "missingKinds" + '"' + ":[]}}";
    }

    private static String deny() {
        return "{" + '"' + "schema" + '"' + ":1," + '"' + "ok" + '"' + ":true," + '"' + "payload"
                + '"' + ":{" + '"' + "effect" + '"' + ":" + '"' + "deny" + '"' + "," + '"'
                + "reason" + '"' + ":" + '"' + "missing-kinds" + '"' + "," + '"' + "detail" + '"'
                + ":" + '"' + "link your forum account" + '"' + "," + '"' + "ttlSeconds" + '"'
                + ":60," + '"' + "missingKinds" + '"' + ":[" + '"' + "forum" + '"' + "]}}";
    }

    private static String redeemed() {
        return "{" + '"' + "schema" + '"' + ":1," + '"' + "ok" + '"' + ":true," + '"' + "payload"
                + '"' + ":{" + '"' + "subjectId" + '"' + ":" + '"' + "s-1" + '"' + "}}";
    }

    /** A core that redeems, then answers the gate with whatever is asked for. */
    private static InMemoryTransport core(String decision) {
        return new InMemoryTransport(request ->
                request.contains("code.redeem") ? redeemed() : decision);
    }

    private SignupServer serve(InMemoryTransport transport, ForgeSurface forge) {
        Registration registration = new Registration(
                new SoulbindClient(transport, "a-credential", CLOCK, new DecisionCache()),
                forge, "forge", "forge.register");
        return new SignupServer(registration, PATH, (m, t) -> logged.add(m))
                .start("127.0.0.1", 0);
    }

    private static HttpResponse<String> get(SignupServer server)
            throws IOException, InterruptedException {
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(base(server) + PATH)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(SignupServer server, Map<String, String> fields)
            throws IOException, InterruptedException {
        StringBuilder encoded = new StringBuilder();
        fields.forEach((k, v) -> {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return HTTP.send(
                HttpRequest.newBuilder(URI.create(base(server) + PATH))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(encoded.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String base(SignupServer server) {
        return "http://127.0.0.1:" + server.port();
    }

    private static Map<String, String> complete() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("code", "BC23DFG4");
        f.put("username", "ada");
        f.put("email", "ada@example.invalid");
        f.put("password", "a-passphrase");
        f.put("confirm", "a-passphrase");
        return f;
    }

    @Test
    @DisplayName("the form is served where the proxy will look for it")
    void formIsServed() throws Exception {
        try (SignupServer server = serve(core(allow()), new ScriptedSurface())) {
            HttpResponse<String> response = get(server);

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<form"), response::body);
        }
    }

    @Test
    @DisplayName("a complete submission creates the account and says so")
    void completeSubmissionCreates() throws Exception {
        ScriptedSurface forge = new ScriptedSurface();
        try (SignupServer server = serve(core(allow()), forge)) {
            HttpResponse<String> response = post(server, complete());

            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("ada"), response::body);
            assertTrue(forge.exists("ada"));
        }
    }

    @Test
    @DisplayName("an incomplete submission comes back as 422 with the form and its problems")
    void incompleteSubmissionIsUnprocessable() throws Exception {
        // 422 rather than 400: the request was understood and its contents were
        // not acceptable. A 400 reads as a broken browser to anybody reading a
        // proxy log.
        try (SignupServer server = serve(core(allow()), new ScriptedSurface())) {
            Map<String, String> fields = complete();
            fields.remove("email");

            HttpResponse<String> response = post(server, fields);

            assertEquals(422, response.statusCode());
            assertTrue(response.body().contains("<form"), response::body);
            assertTrue(response.body().contains("email"), response::body);
        }
    }

    @Test
    @DisplayName("a redisplayed form keeps the name but never the password")
    void redisplayKeepsOnlyTheNonSecretFields() throws Exception {
        try (SignupServer server = serve(core(allow()), new ScriptedSurface())) {
            Map<String, String> fields = complete();
            fields.put("confirm", "mismatched");

            HttpResponse<String> response = post(server, fields);

            assertEquals(422, response.statusCode());
            assertTrue(response.body().contains("ada"), response::body);
            assertFalse(response.body().contains("a-passphrase"),
                    () -> "the password was written back into the page: " + response.body());
        }
    }

    @Test
    @DisplayName("a refused gate is 403 and creates nothing")
    void refusedGateIsForbidden() throws Exception {
        ScriptedSurface forge = new ScriptedSurface();
        try (SignupServer server = serve(core(deny()), forge)) {
            HttpResponse<String> response = post(server, complete());

            assertEquals(403, response.statusCode());
            assertTrue(response.body().contains("forum"), response::body);
            assertFalse(forge.exists("ada"));
        }
    }

    @Test
    @DisplayName("an unreachable core is 503, which is the retryable one")
    void outageIsServiceUnavailable() throws Exception {
        ScriptedSurface forge = new ScriptedSurface();
        try (SignupServer server = serve(core(allow()).goDown(), forge)) {
            HttpResponse<String> response = post(server, complete());

            assertEquals(503, response.statusCode());
            assertFalse(forge.exists("ada"));
        }
    }

    @Test
    @DisplayName("a fault below the handler is 503, not a stack trace")
    void faultBecomesAnOutagePage() throws Exception {
        // Javalin's default handler answers a 500 with the exception in it, on a
        // page somebody is standing in front of.
        ForgeSurface explodes = new ForgeSurface() {
            @Override
            public Presence presence(String username) {
                throw new IllegalStateException("a secret-bearing message");
            }

            @Override
            public Creation create(Account account) {
                throw new IllegalStateException("unused");
            }

            @Override
            public boolean setActive(String username, boolean active) {
                return false;
            }
        };

        try (SignupServer server = serve(core(allow()), explodes)) {
            HttpResponse<String> response = post(server, complete());

            assertEquals(503, response.statusCode());
            assertFalse(response.body().contains("a secret-bearing message"),
                    () -> "an internal message reached the page: " + response.body());
            assertFalse(response.body().contains("IllegalStateException"), response::body);
        }
    }

    @Test
    @DisplayName("a refused LINK is 403, which an outage would not be")
    void refusedLinkIsForbidden() throws Exception {
        // Distinct from the gate refusing, and from core being down: this is
        // core answering no to the redemption -- a spent or unknown code. It
        // must reach somebody as 403, because retrying will not help.
        String refusal = "{" + '"' + "schema" + '"' + ":1," + '"' + "ok" + '"' + ":false,"
                + '"' + "error" + '"' + ":{" + '"' + "code" + '"' + ":" + '"'
                + "invalid-request" + '"' + "," + '"' + "message" + '"' + ":" + '"'
                + "already-redeemed: that code has been used" + '"' + "}}";
        ScriptedSurface forge = new ScriptedSurface();

        try (SignupServer server = serve(InMemoryTransport.always(refusal), forge)) {
            HttpResponse<String> response = post(server, complete());

            assertEquals(403, response.statusCode(),
                    () -> "a spent code was reported as something worth retrying: "
                            + response.body());
            assertFalse(forge.exists("ada"),
                    "the gate was asked, and an account created, for a name bound to nothing");
        }
    }

    @Test
    @DisplayName("a fault is written to the log as well as answered politely")
    void faultIsLogged() throws Exception {
        // The person gets a calm page; somebody still has to be able to find
        // out what happened, and a swallowed exception leaves nothing to find.
        ForgeSurface explodes = new ForgeSurface() {
            @Override
            public Presence presence(String username) {
                throw new IllegalStateException("the detail that matters");
            }

            @Override
            public Creation create(Account account) {
                throw new IllegalStateException("unused");
            }

            @Override
            public boolean setActive(String username, boolean active) {
                return false;
            }
        };

        try (SignupServer server = serve(core(allow()), explodes)) {
            post(server, complete());
        }

        assertTrue(logged.stream().anyMatch(m -> m.contains("failed to render")),
                () -> "a fault reached the page and nothing else: " + logged);
    }

    @Test
    @DisplayName("closing it actually releases the port")
    void closeReleasesThePort() throws Exception {
        // try-with-resources everywhere else would hide a close() that did
        // nothing: the tests would pass and a long-running process would leak
        // a listener per restart.
        SignupServer server = serve(core(allow()), new ScriptedSurface());
        int port = server.port();
        assertEquals(200, get(server).statusCode());

        server.close();

        assertThrows(IOException.class,
                () -> HTTP.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString()),
                "the port still answered after close(), so nothing was released");
    }

    @Test
    @DisplayName("port 0 binds something, and reports which")
    void ephemeralPortIsReported() throws Exception {
        // The only way to learn the port when 0 was asked for, and what every
        // test here depends on.
        try (SignupServer server = serve(core(allow()), new ScriptedSurface())) {
            assertTrue(server.port() > 0, () -> "bound port reported as " + server.port());
        }
    }
}
