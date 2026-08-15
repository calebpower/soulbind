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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import dev.soulbind.core.storage.Backend;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.SchemaVersion;
import dev.soulbind.protocol.Wire;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tier 7 — seeded fuzz against an embedded core.
 *
 * <p><b>The oracle is not "this is rejected".</b> Many of these inputs are
 * perfectly legal and must succeed. The oracle is:
 *
 * <ol>
 *   <li>never a 5xx — an unhandled exception reaching the transport;
 *   <li>every response a well-formed envelope, whatever else it says;
 *   <li>never {@link ErrorCode#INTERNAL}, which is how an unhandled path
 *       reports itself through the envelope rather than as a 5xx;
 *   <li>the server still answering afterwards.
 * </ol>
 *
 * <p>An oracle of "the right things are rejected" would need a second
 * implementation of every rule to compare against — and would be wrong wherever
 * the two disagreed, with no way to tell which. These four properties need no
 * second implementation and hold for every input a caller can construct.
 *
 * <p><b>Seeds are printed and replayable.</b> Every run prints its seed;
 * {@code SOULBIND_SEED=<n> ./gradlew :core:test} replays it exactly. A fuzz
 * failure nobody can reproduce is a fuzz failure nobody will fix.
 */
@Tag("fuzz")
class ProtocolFuzzTest {

    @TempDir
    Path tempDir;

    /** Requests per backend. Enough to cover the corpus in several positions. */
    private static final int ITERATIONS = 600;

    private static final Set<Capability> GRANTED =
            Set.of(Capability.CODE_DISPLAY, Capability.CONFIG_MANAGEMENT);

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("no 5xx, always an envelope, never an internal error, server alive after")
    void fuzz(Backend backend) throws Exception {
        long seed = seed();
        // Printed unconditionally, not only on failure: a run that passes and a
        // run that fails must be equally reproducible, or the first failure is
        // the first time anybody tries.
        System.out.println("[fuzz] backend=" + backend + " seed=" + seed
                + "  (replay with SOULBIND_SEED=" + seed + ")");

        Random random = new Random(seed);
        List<String> corpus = HostileInputs.load();
        Clock clock = TestCore.fixedClock();

        List<String> failures = new ArrayList<>();

        try (TestCore core = new TestCore(backend, tempDir, GRANTED, clock)) {
            for (int i = 0; i < ITERATIONS; i++) {
                Case sent = nextCase(random, corpus, core);
                HttpResponse<String> response;
                try {
                    response = core.postSigned(
                            sent.body(), clock.instant(), sent.token(), sent.nonce());
                } catch (Exception e) {
                    failures.add(sent.describe() + " -> transport threw " + e);
                    continue;
                }
                checkOracle(core, sent, response, failures);
                if (failures.size() > 20) {
                    break; // enough to diagnose; the seed replays the rest
                }
            }

            // Property 4, checked last and against a request known to be good:
            // a server that stopped answering somewhere in the middle would
            // otherwise show up only as a wall of transport errors.
            HttpResponse<String> alive = core.postSigned(
                    core.request("heartbeat", "{}"), clock.instant());
            assertEquals(
                    200, alive.statusCode(),
                    "the server stopped answering a known-good request after fuzzing");
            assertTrue(
                    core.codec.mapper().readTree(alive.body()).get(Wire.OK).asBoolean(),
                    "the server was alive but refusing a request it had accepted before");
        }

        if (!failures.isEmpty()) {
            fail("seed " + seed + " on " + backend + " produced " + failures.size()
                    + " oracle violations (replay with SOULBIND_SEED=" + seed + "):\n  "
                    + String.join("\n  ", failures));
        }
    }

    private void checkOracle(
            TestCore core, Case sent, HttpResponse<String> response, List<String> failures)
            throws Exception {

        if (response.statusCode() >= 500) {
            // The headline property. A 5xx means an exception escaped to the
            // transport, which is a crash the caller triggered.
            failures.add(sent.describe() + " -> HTTP " + response.statusCode());
            return;
        }
        if (response.statusCode() != 200) {
            failures.add(sent.describe() + " -> HTTP " + response.statusCode()
                    + "; every outcome is 200 with the reason in the envelope");
            return;
        }

        JsonNode json;
        try {
            json = core.codec.mapper().readTree(response.body());
        } catch (Exception e) {
            failures.add(sent.describe() + " -> unparseable response: " + e);
            return;
        }
        if (json == null || !json.isObject()) {
            failures.add(sent.describe() + " -> response is not a JSON object");
            return;
        }
        if (!json.has(Wire.SCHEMA) || json.get(Wire.SCHEMA).asInt() != SchemaVersion.CURRENT) {
            failures.add(sent.describe() + " -> envelope missing or wrong schema");
            return;
        }
        if (!json.has(Wire.OK)) {
            failures.add(sent.describe() + " -> envelope has no '" + Wire.OK + "'");
            return;
        }
        if (json.get(Wire.OK).asBoolean()) {
            return;
        }

        JsonNode error = json.get(Wire.ERROR);
        if (error == null || !error.has(Wire.ERROR_CODE)) {
            failures.add(sent.describe() + " -> refusal with no error code");
            return;
        }
        String code = error.get(Wire.ERROR_CODE).asText();
        if (ErrorCode.fromWireName(code).isEmpty()) {
            failures.add(sent.describe() + " -> unrecognised error code '" + code + "'");
            return;
        }
        if (ErrorCode.INTERNAL.wireName().equals(code)) {
            // An unhandled path reporting itself through the envelope. Not a
            // crash, but the same defect wearing a tidier hat -- and no input a
            // caller can construct should reach it.
            failures.add(sent.describe() + " -> " + ErrorCode.INTERNAL.wireName());
        }
    }

    /** One generated request, with enough context to reproduce it by hand. */
    private record Case(String body, String token, String nonce, String shape) {
        String describe() {
            String shown = body.length() > 200 ? body.substring(0, 200) + "..." : body;
            return "[" + shape + "] " + shown.replace("\n", "\\n");
        }
    }

    private Case nextCase(Random random, List<String> corpus, TestCore core) {
        String hostile = corpus.get(random.nextInt(corpus.size()));

        // A hostile CREDENTIAL is only fuzzed here when it is a legal HTTP
        // header value. Anything else -- non-ASCII, control characters -- cannot
        // be sent as a header at all: java.net.http refuses to build the
        // request, and so does every conformant stack. Fuzzing it here would
        // measure the client, not the server.
        //
        // The full value space IS reachable through the dispatcher, which the
        // socket transport and the PHP client both feed directly, so it is
        // fuzzed there instead -- see dispatcherFuzz below. This narrowing
        // covers exactly the credential position on exactly this transport.
        String token = random.nextInt(10) == 0 && isHeaderSafe(hostile)
                ? hostile
                : core.credential;
        String nonce = UUID.randomUUID().toString();

        return switch (random.nextInt(8)) {
            case 0 -> new Case(core.request(hostile, "{}"), token, nonce, "hostile-op");
            case 1 -> new Case(
                    core.request("hello", json(Map.of("connectorName", hostile))),
                    token, nonce, "hostile-hello-name");
            case 2 -> new Case(
                    core.request("hello", json(Map.of("capabilities", List.of(hostile)))),
                    token, nonce, "hostile-capability");
            case 3 -> new Case(hostile, token, nonce, "raw-corpus-as-body");
            case 4 -> new Case(
                    "{\"" + Wire.SCHEMA + "\":" + random.nextInt() + ",\"" + Wire.OP
                            + "\":\"heartbeat\",\"" + Wire.ID + "\":\"x\"}",
                    token, nonce, "random-schema");
            case 5 -> new Case(
                    core.request("heartbeat", json(Map.of("unexpected", hostile))),
                    token, nonce, "unexpected-payload-field");
            case 6 -> new Case(
                    core.request("connector.list", hostile.startsWith("{") ? hostile : "{}"),
                    token, nonce, "hostile-payload-json");
            default -> new Case(
                    truncate(core.request("hello", "{}"), random),
                    token, nonce, "truncated-envelope");
        };
    }

    /** Whether a value can legally appear as an HTTP header value at all. */
    private static boolean isHeaderSafe(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /** Truncation, because a half-delivered message is what a dropped connection produces. */
    private static String truncate(String body, Random random) {
        return body.isEmpty() ? body : body.substring(0, random.nextInt(body.length()));
    }

    private static String json(Map<String, Object> fields) {
        Map<String, Object> copy = new HashMap<>(fields);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : copy.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(e.getKey())).append(':');
            if (e.getValue() instanceof List<?> list) {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(quote(String.valueOf(list.get(i))));
                }
                sb.append(']');
            } else {
                sb.append(quote(String.valueOf(e.getValue())));
            }
        }
        return sb.append('}').toString();
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * The seed for this run.
     *
     * <p>From {@code SOULBIND_SEED} when set, so a failure replays exactly;
     * otherwise fresh, so successive runs explore. The property is wired through
     * the build for every module, so a replay needs no code change.
     */
    private static long seed() {
        String configured = System.getProperty("soulbind.seed");
        if (configured != null && !configured.isBlank()) {
            return Long.parseLong(configured.strip());
        }
        return new Random().nextLong();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the dispatcher survives the whole corpus in every position")
    void dispatcherFuzz(Backend backend) throws Exception {
        // The same oracle, one layer down, where values that cannot survive an
        // HTTP header ARE reachable -- the socket transport and the PHP client
        // both hand the dispatcher strings directly. Nothing a caller can
        // construct may make it throw.
        long seed = seed();
        System.out.println("[fuzz:dispatcher] backend=" + backend + " seed=" + seed
                + "  (replay with SOULBIND_SEED=" + seed + ")");

        Random random = new Random(seed);
        List<String> corpus = HostileInputs.load();
        Clock clock = TestCore.fixedClock();
        List<String> failures = new ArrayList<>();

        try (TestCore core = new TestCore(backend, tempDir, GRANTED, clock)) {
            for (String hostile : corpus) {
                for (int position = 0; position < 3; position++) {
                    int schema = random.nextInt(4) == 0 ? random.nextInt() : SchemaVersion.CURRENT;
                    String op = position == 0 ? hostile : "hello";
                    String token = position == 1 ? hostile : core.credential;
                    var payload = position == 2
                            ? core.codec.mapper().getNodeFactory().textNode(hostile)
                            : core.codec.mapper().createObjectNode();

                    WireResponse response;
                    try {
                        response = core.dispatcher().dispatch(schema, op, token, payload);
                    } catch (RuntimeException e) {
                        failures.add("op=" + describe(op) + " token=" + describe(token)
                                + " payload=" + describe(String.valueOf(payload))
                                + " -> threw " + e);
                        continue;
                    }
                    if (response == null) {
                        failures.add("op=" + describe(op) + " -> null response");
                    } else if (!response.ok() && response.code() == ErrorCode.INTERNAL) {
                        failures.add("op=" + describe(op) + " token=" + describe(token)
                                + " -> " + ErrorCode.INTERNAL.wireName());
                    }
                }
            }
        }

        if (!failures.isEmpty()) {
            fail("seed " + seed + " on " + backend + ": " + failures.size()
                    + " dispatcher violations (replay with SOULBIND_SEED=" + seed + "):\n  "
                    + String.join("\n  ", failures.subList(0, Math.min(20, failures.size()))));
        }
    }

    private static String describe(String value) {
        String shown = value.length() > 60 ? value.substring(0, 60) + "..." : value;
        return "'" + shown.replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the corpus is actually loaded -- an empty one would fuzz nothing")
    void corpusIsNotEmpty(Backend backend) {
        // Without this, a corpus that failed to load would make the fuzz run
        // vacuously green: zero hostile values, 600 well-formed requests, no
        // violations. The loader throws on an empty parse; this states the
        // expectation where a reader will see it.
        List<String> corpus = HostileInputs.load();
        assertNotNull(corpus);
        assertTrue(
                corpus.size() > 50,
                () -> "the corpus parsed to only " + corpus.size() + " values");
    }
}
