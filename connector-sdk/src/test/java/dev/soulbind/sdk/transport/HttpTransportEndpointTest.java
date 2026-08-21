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

package dev.soulbind.sdk.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What an operator's {@code core.url} becomes.
 *
 * <p>Uncovered until a mutation sweep said so. The value is small and the
 * failure it prevents is not: a trailing slash in a config file would otherwise
 * produce {@code //v1/rpc}, which some servers route and some do not — so the
 * same configuration works on one deployment and 404s on the next, with
 * nothing in either explaining the difference.
 */
class HttpTransportEndpointTest {

    private static String endpointFor(String coreUrl) {
        return new HttpTransport(coreUrl, "cred", Clock.systemUTC()).endpoint().toString();
    }

    @Test
    @DisplayName("a trailing slash is trimmed, and its absence changes nothing")
    void trailingSlashIsTrimmed() {
        String expected = "http://127.0.0.1:7180/v1/rpc";

        for (String written : List.of("http://127.0.0.1:7180", "http://127.0.0.1:7180/")) {
            assertEquals(expected, endpointFor(written),
                    "'" + written + "' produced a different endpoint; an operator's trailing"
                            + " slash must not decide whether their install works");
        }
    }

    @Test
    @DisplayName("only ONE trailing slash is meaningful; the rest is the operator's business")
    void onlyOneSlashIsTrimmed() {
        // Stated rather than left to discovery: the trim is one character, not
        // a normaliser. Two slashes is a typo this does not silently repair,
        // and pretending otherwise would hide it.
        assertEquals("http://127.0.0.1:7180//v1/rpc", endpointFor("http://127.0.0.1:7180//"));
    }

    @Test
    @DisplayName("a path prefix survives, so core behind a reverse proxy still works")
    void pathPrefixSurvives() {
        // docs/install.md pushes operators to put core behind a TLS-terminating
        // proxy, and such a proxy commonly mounts it under a path.
        assertEquals("https://example.com/soulbind/v1/rpc",
                endpointFor("https://example.com/soulbind"));
        assertEquals("https://example.com/soulbind/v1/rpc",
                endpointFor("https://example.com/soulbind/"));
    }
}
