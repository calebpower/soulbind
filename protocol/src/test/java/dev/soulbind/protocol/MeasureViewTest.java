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

package dev.soulbind.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The measure DTOs, at the edges nothing else reaches. */
class MeasureViewTest {

    @Test
    @DisplayName("an absent measure list reads as empty, never as null")
    void nullMeasuresBecomeEmpty() {
        // The wire hands this a missing field as null. A caller iterating the
        // result would then throw inside a response that reported success,
        // which reads as core failing rather than as an account with nothing
        // recorded.
        assertEquals(List.of(), new MeasureGetResponse(null).measures());
    }

    @Test
    @DisplayName("the list is copied, so a caller cannot edit a response after the fact")
    void measuresAreCopied() {
        List<MeasureView> mutable = new ArrayList<>();
        mutable.add(new MeasureView("playtime", 1L, 604_800L, 0L, "connector:x"));

        MeasureGetResponse response = new MeasureGetResponse(mutable);
        mutable.clear();

        assertEquals(1, response.measures().size(), "the response tracked the caller's list");
        assertThrows(UnsupportedOperationException.class,
                () -> response.measures().add(null));
    }
}
