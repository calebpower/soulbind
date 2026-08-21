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

package dev.soulbind.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The arithmetic behind "did you mean?".
 *
 * <p>Tested directly because testing it through the suggestion did not work.
 * The suggestion depends only on whether the distance is under a threshold, so
 * three mutants — the initialisation row, and the outer loop's last iteration —
 * survived a suite of suggestion tests: every input still landed on the same
 * side of the threshold with the numbers wrong.
 *
 * <p>The cases below pin the numbers themselves. The empty-string rows are the
 * ones that catch a broken initialisation row, and the equal-length rows catch
 * an outer loop that stops one short.
 */
class EditDistanceTest {

    @ParameterizedTest(name = "{0} -> {1} is {2}")
    @CsvSource({
            // Identical, and the degenerate cases either side of it.
            "host, host, 0",
            "'', '', 0",
            // Against empty: the distance is the other string's whole length.
            // A broken initialisation row gets these wrong and nothing else
            // notices, because a suggestion never compares against "".
            "host, '', 4",
            "'', host, 4",
            "a, '', 1",
            "'', abcdefgh, 8",
            // One edit of each kind.
            "host, hos, 1",
            "host, hosts, 1",
            "host, hoct, 1",
            // Equal length, every character different: catches an outer loop
            // that stops before the last character of the first string.
            "host, ptcd, 4",
            "abcd, wxyz, 4",
            // The real keys, at the distances the suggestion turns on.
            "server.hos, server.host, 1",
            "server.hoxy, server.host, 2",
            "server.hxyz, server.host, 3",
    })
    @DisplayName("known distances come back exactly")
    void knownDistances(String a, String b, int expected) {
        assertEquals(expected, ConfigLoader.editDistance(a, b));
    }

    @Test
    @DisplayName("the distance is symmetric, which a one-sided loop bug breaks")
    void symmetric() {
        // Levenshtein is symmetric. An implementation that mishandles one
        // string's final character is not, and the asymmetry is visible here
        // even when both directions still fall under a suggestion threshold.
        String[][] pairs = {
                {"server.host", "server.port"},
                {"storage.password", "server.host"},
                {"a", "abcdef"},
                {"", "server.tls"},
        };
        for (String[] pair : pairs) {
            assertEquals(
                    ConfigLoader.editDistance(pair[0], pair[1]),
                    ConfigLoader.editDistance(pair[1], pair[0]),
                    pair[0] + " and " + pair[1] + " measure differently by direction");
        }
    }
}
