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

package dev.soulbind.guards;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transport seam guard.
 *
 * <p>Two transports ship, and more may. Protocol logic — signing, idempotency,
 * decision caching, authorization — lives <em>above</em> them and must be
 * testable against an in-memory transport with no socket in sight. The moment
 * an HTTP or WebSocket type appears outside a transport package, that logic has
 * acquired a dependency on how the bytes arrived, and the next change quietly
 * assumes it.
 *
 * <p>The failure this prevents is specific: authorization or replay logic that
 * can only be exercised by standing up a server. It still works — it just
 * cannot be tested cheaply, so it is tested less, so it is where the bugs go.
 *
 * <p><b>What this does NOT prove:</b> that the transport packages themselves are
 * correct, or that a third transport would fit. It proves that nothing above
 * them has learned which one is in use.
 */
class TransportSeamGuardTest {

    /**
     * The packages permitted to name a transport type.
     *
     * <p>Matched on the PACKAGE path, so each package's own tests are covered by
     * the same exemption — a transport's tests must obviously name transport
     * types, and a guard that fired on the tests proving the seam works is a
     * guard that gets suppressed rather than obeyed.
     */
    private static final List<String> TRANSPORT_PACKAGES = List.of(
            "dev/soulbind/core/transport",
            "dev/soulbind/sdk/transport");

    private static final Pattern TRANSPORT_TYPE = Pattern.compile(
            "\\bio\\.javalin\\b|\\bJavalin\\b|\\bWsContext\\b|\\bWsMessageContext\\b"
                    + "|\\bjakarta\\.servlet\\b|\\bjavax\\.servlet\\b|\\borg\\.eclipse\\.jetty\\b"
                    + "|\\bjava\\.net\\.http\\b|\\bHttpClient\\b|\\bHttpRequest\\b"
                    + "|\\bHttpResponse\\b|\\bWebSocket\\b|\\bHttpServer\\b|\\bOkHttp");

    @Test
    @DisplayName("no HTTP or WebSocket type escapes a transport package")
    void seamHolds() {
        List<String> violations = scan(SourceTree.repoRoot(), SourceTree.productionModules());
        assertTrue(
                violations.isEmpty(),
                () -> "protocol logic must be testable against an in-memory transport. A "
                        + "transport type outside the transport packages means it no longer "
                        + "is.\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("GUARD FIRES: a transport type outside the seam is rejected")
    void fixtureIsRejected() {
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");
        List<String> violations = scan(fixtures, List.of("transport-seam-violation"));

        assertFalse(
                violations.isEmpty(),
                "the must-fail fixture was not rejected: either it stopped naming a transport "
                        + "type, or the guard stopped detecting one");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("HttpClient")),
                () -> "expected the offending type to be named: " + violations);
    }

    private static List<String> scan(Path root, List<String> modules) {
        List<String> violations = new ArrayList<>();
        for (String module : modules) {
            for (Path src : SourceTree.javaSourcesUnder(root.resolve(module))) {
                String rel = SourceTree.rel(src).replace('\\', '/');
                if (TRANSPORT_PACKAGES.stream().anyMatch(rel::contains)) {
                    continue;
                }
                String[] lines = SourceTree.read(src).split("\n", -1);
                boolean inBlockComment = false;
                for (int i = 0; i < lines.length; i++) {
                    String code = stripComment(lines[i], inBlockComment);
                    inBlockComment = updateBlockState(lines[i], inBlockComment);
                    if (code.isBlank()) {
                        continue;
                    }
                    var matcher = TRANSPORT_TYPE.matcher(code);
                    if (matcher.find()) {
                        violations.add("%s:%d names %s -> %s"
                                .formatted(rel, i + 1, matcher.group(), lines[i].strip()));
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Comments are stripped before matching.
     *
     * <p>The same stated narrowing as the storage seam guard: prose explaining
     * the transports necessarily names them, and a guard that fired on its own
     * explanation would be routed around. A violation hidden in a comment is not
     * caught, and is also not a violation, because a comment does not execute.
     */
    private static String stripComment(String line, boolean inBlockComment) {
        if (inBlockComment) {
            int end = line.indexOf("*/");
            return end < 0 ? "" : line.substring(end + 2);
        }
        String out = line;
        int blockStart = out.indexOf("/*");
        if (blockStart >= 0) {
            int blockEnd = out.indexOf("*/", blockStart);
            out = blockEnd >= 0
                    ? out.substring(0, blockStart) + out.substring(blockEnd + 2)
                    : out.substring(0, blockStart);
        }
        int lineComment = out.indexOf("//");
        if (lineComment >= 0) {
            out = out.substring(0, lineComment);
        }
        return out;
    }

    private static boolean updateBlockState(String line, boolean wasInBlock) {
        int lastOpen = line.lastIndexOf("/*");
        int lastClose = line.lastIndexOf("*/");
        if (wasInBlock) {
            return lastClose < 0;
        }
        return lastOpen >= 0 && lastClose < lastOpen;
    }
}
