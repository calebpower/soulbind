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

package dev.soulbind.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The seed set is present, explained, and distinct. */
class SeedsTest {

    @Test
    @DisplayName("the committed set is non-empty and every seed carries a note")
    void seedsAreCommittedAndExplained() {
        List<Seeds.Seed> seeds = Seeds.fixed();

        assertTrue(seeds.size() >= 3,
                () -> "the committed set has " + seeds.size() + " seeds. §14's gate asks for"
                        + " three fixed seeds across both backends.");
        for (Seeds.Seed seed : seeds) {
            assertFalse(seed.why().isBlank(),
                    "seed " + seed.value() + " has no note, and a seed nobody can explain is"
                            + " a seed the next person deletes");
        }
    }

    @Test
    @DisplayName("no seed is listed twice")
    void seedsAreDistinct() {
        // A duplicate would run twice and look like broader coverage than it is.
        List<Long> values = Seeds.fixed().stream().map(Seeds.Seed::value).toList();
        assertEquals(values.size(), values.stream().distinct().count(),
                () -> "a seed is listed more than once: " + values);
    }

    @Test
    @DisplayName("the promotion line does not consult a clock")
    void promotionIsReproducible() {
        // Everything else in this module is reproducible from its inputs. A
        // function that quietly read the wall clock would be the one thing that
        // was not, in the file whose whole subject is reproducibility.
        assertEquals(
                "77  found a vanished link on 2026-08-20",
                Seeds.promotionLine(77L, "2026-08-20", "a vanished link"));
    }
}
