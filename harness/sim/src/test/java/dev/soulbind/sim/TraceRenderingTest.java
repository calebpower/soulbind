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

/**
 * What a run leaves behind for a person to read.
 *
 * <p>The trace and the violation lines are not decoration: when a seed fails,
 * they are the whole of what anybody has to work from — the run is gone, and a
 * line that omits the account or the invariant sends the reader back to
 * re-running it blind. Nothing asserted any of it.
 */
class TraceRenderingTest {

    private static final Actor ALEX = new Actor("alex", List.of("game:alex"), 0);

    @Test
    @DisplayName("an action renders its subject and its target when it has them")
    void fullAction() {
        String line = new Action(
                ActionKind.REDEEM_CODE, ALEX, "BCDFGHJK", "chat:alex").toString();

        assertEquals("alex REDEEM_CODE BCDFGHJK -> chat:alex", line,
                "the trace line does not say what was done to what; a failing seed then has"
                        + " to be re-run to find out");
    }

    @Test
    @DisplayName("an action with no subject or target says so by omission, not by 'null'")
    void bareAction() {
        // CONFIG_FLIP names neither. Rendering the absent fields would put
        // "null" in the record of the run, which reads as a defect in the
        // harness and sends somebody looking for one.
        String line = new Action(ActionKind.CONFIG_FLIP, ALEX, null, null).toString();

        assertFalse(line.contains("null"), line);
        assertTrue(line.startsWith("alex CONFIG_FLIP"), line);
    }

    @Test
    @DisplayName("a subject with no target renders the subject and no arrow")
    void subjectOnly() {
        String line = new Action(ActionKind.DESCRIBE, ALEX, "game:alex", null).toString();

        assertEquals("alex DESCRIBE game:alex", line, line);
    }

    @Test
    @DisplayName("an adversarial action is marked as one in the trace")
    void nemesisIsMarked() {
        // Reporting only, and it is the marking that makes a trace readable:
        // scanning two hundred lines for the ones that were trying to break
        // something is the first thing anybody does with a failed seed.
        String hostile = new Action(ActionKind.DOUBLE_REDEEM, ALEX, "BCDFGHJK", "chat:alex")
                .toString();
        String ordinary = new Action(ActionKind.DESCRIBE, ALEX, "game:alex", null).toString();

        assertTrue(hostile.contains("[nemesis]"), hostile);
        assertFalse(ordinary.contains("[nemesis]"),
                "an ordinary action was marked adversarial, which makes the marking useless: "
                        + ordinary);
    }

    @Test
    @DisplayName("a violation says which invariant, when, and what")
    void violationRendersEverything() {
        String line = new Checker.Violation(
                "linkage-mirrors-model", "core does not know game:alex at all", 42).toString();

        assertTrue(line.contains("42"),
                "the violation does not say when it was first seen, so a hunt has nothing to"
                        + " narrow towards: " + line);
        assertTrue(line.contains("linkage-mirrors-model"),
                "the violation does not name the invariant that complained: " + line);
        assertTrue(line.contains("core does not know game:alex at all"),
                "the violation does not say what was wrong: " + line);
    }
}
