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

package dev.soulbind.core.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The line an operator actually reads, and the number a script acts on.
 *
 * <p>`CliTest` asserts the exit codes. Nothing asserted the <em>tally</em>, so
 * a mutation sweep found the arithmetic behind "4 ok, 2 warning(s), 1 failed"
 * surviving along with both {@code println} calls that emit it. An operator
 * reading a summary that miscounts, or is missing entirely, has been handed a
 * verdict with no evidence.
 */
class DoctorReportTest {

    private record Reported(String output, int exit) {}

    private static Reported report(List<Doctor.Finding> findings) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exit;
        try (PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8)) {
            exit = Doctor.report(findings, out);
        }
        return new Reported(buffer.toString(StandardCharsets.UTF_8), exit);
    }

    private static Doctor.Finding at(Doctor.Level level) {
        return new Doctor.Finding(level, level.name().toLowerCase(java.util.Locale.ROOT),
                "detail for " + level);
    }

    @Test
    @DisplayName("the tally counts each level, and ok is what is left over")
    void tallyIsCorrect() {
        Reported r = report(List.of(
                at(Doctor.Level.OK), at(Doctor.Level.OK), at(Doctor.Level.OK),
                at(Doctor.Level.WARN), at(Doctor.Level.WARN),
                at(Doctor.Level.FAIL)));

        assertTrue(r.output().contains("3 ok, 2 warning(s), 1 failed"),
                () -> "the summary miscounts: " + r.output());
    }

    @Test
    @DisplayName("every finding is printed, with its detail")
    void everyFindingIsPrinted() {
        // The detail is the half that says what to DO. A report that printed
        // only the check names would name six problems and solve none.
        Reported r = report(List.of(at(Doctor.Level.FAIL), at(Doctor.Level.WARN)));

        assertTrue(r.output().contains("detail for FAIL"), r.output());
        assertTrue(r.output().contains("detail for WARN"), r.output());
    }

    @Test
    @DisplayName("warnings alone are healthy; one failure is not")
    void exitCodeFollowsFailuresOnly() {
        // The distinction a script depends on. Warnings are things an operator
        // should read and may legitimately accept -- binding every interface
        // behind a proxy, say -- and treating them as failures would make
        // `doctor` unusable in exactly the deployments that read it.
        assertEquals(Doctor.EXIT_HEALTHY,
                report(List.of(at(Doctor.Level.OK), at(Doctor.Level.WARN))).exit());
        assertEquals(Doctor.EXIT_HEALTHY, report(List.of()).exit());

        assertEquals(Doctor.EXIT_UNHEALTHY,
                report(List.of(at(Doctor.Level.OK), at(Doctor.Level.FAIL))).exit());
        assertEquals(Doctor.EXIT_UNHEALTHY,
                report(List.of(at(Doctor.Level.FAIL), at(Doctor.Level.WARN))).exit());
    }

    @Test
    @DisplayName("an empty report says so rather than printing nothing")
    void emptyReportStillSummarises() {
        Reported r = report(List.of());
        assertTrue(r.output().contains("0 ok, 0 warning(s), 0 failed"),
                () -> "a report with no findings printed no summary: '" + r.output() + "'");
    }
}
