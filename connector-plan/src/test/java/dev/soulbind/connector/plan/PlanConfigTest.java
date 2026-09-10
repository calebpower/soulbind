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
package dev.soulbind.connector.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * This connector's configuration, none of which was executed by anything.
 *
 * <p>All thirteen mutants in {@code PlanConfig} came back "no coverage": the
 * defaults, the TTL floor, and the one check that stops the connector starting.
 * These are the values an operator sets once and never looks at again, which is
 * exactly why a default that changes quietly is expensive — the symptom appears
 * months later as a dashboard behaving differently from the one next to it.
 */
class PlanConfigTest {

    @TempDir
    Path tempDir;

    private static final String MINIMAL = """
            [core]
            url = "http://127.0.0.1:7000"
            """;

    private static Config load(String toml) {
        return ConfigLoader.parse(toml, "test", PlanConfig.SCHEMA, Map.of());
    }

    @Test
    @DisplayName("the defaults are the documented ones")
    void defaults() {
        Config config = load(MINIMAL);

        assertEquals("game", PlanConfig.platformKind(config));
        assertEquals(LinkDataSource.DEFAULT_TTL, PlanConfig.cacheTtl(config));
        assertFalse(PlanConfig.showSubjectId(config),
                "the subject id was shown by default; it identifies a person across every"
                        + " platform they hold and it is nobody's business by accident");
    }

    @Test
    @DisplayName("what is configured wins over the default")
    void overridesApply() {
        Config config = load(MINIMAL + """
                [plan]
                platformkind = "proxy"
                cachettlseconds = 5
                showsubjectid = true
                """);

        assertEquals("proxy", PlanConfig.platformKind(config));
        assertEquals(Duration.ofSeconds(5), PlanConfig.cacheTtl(config));
        assertTrue(PlanConfig.showSubjectId(config));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -900})
    @DisplayName("a TTL of zero or less falls back to the default rather than disabling the cache")
    void ttlFloor(int seconds) {
        // Plan renders eight providers per player page. A TTL of zero is one
        // round trip per provider per player -- the page would be visibly slow
        // and the cause would be a number somebody typed once.
        Config config = load(MINIMAL + """
                [plan]
                cachettlseconds = %d
                """.formatted(seconds));

        assertEquals(LinkDataSource.DEFAULT_TTL, PlanConfig.cacheTtl(config),
                () -> "a TTL of " + seconds + " disabled caching instead of falling back");
    }

    @Test
    @DisplayName("a blank platform kind falls back, rather than asking core about ''")
    void blankPlatformKind() {
        // `filter(s -> !s.isBlank())`. Without it, `kind = ""` sends core a
        // reference of `:someone`, which it cannot match to anything -- so every
        // player renders as unlinked and nothing says why.
        Config config = load(MINIMAL + """
                [plan]
                platformkind = "   "
                """);

        assertEquals("game", PlanConfig.platformKind(config));
    }

    @Test
    @DisplayName("a configuration with a core url validates")
    void minimalValidates() {
        assertTrue(PlanConfig.validate(load(MINIMAL)).isEmpty(),
                PlanConfig.validate(load(MINIMAL))::toString);
    }

    @Test
    @DisplayName("a blank core url is refused, and says what it is for")
    void blankCoreUrlIsRefused() {
        // Blank rather than absent, because the schema already refuses absent.
        // An empty string passes the schema and leaves the connector with
        // nowhere to ask -- and it would start, render every player as unknown,
        // and look like an outage.
        List<String> problems = PlanConfig.validate(load("""
                [core]
                url = ""
                """));

        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("core.url"), problems::toString);
        assertTrue(problems.get(0).contains("nowhere to ask"),
                "the refusal says what is missing but not what breaks: " + problems);
    }

    @Test
    @DisplayName("load() reads a real file through this connector's schema")
    void loadReadsAFile() throws Exception {
        // parse() is what the rest of this file uses, which left the entry
        // point the plugin actually calls executed by nothing.
        Path file = tempDir.resolve("soulbind-plan.toml");
        Files.writeString(file, MINIMAL + """
                [plan]
                platformkind = "proxy"
                """);

        assertEquals("proxy", PlanConfig.platformKind(PlanConfig.load(file)));
    }

    // --- reporting -----------------------------------------------------------

    @Test
    @DisplayName("reporting is off unless asked for, with documented defaults when on")
    void measureDefaults() {
        Config off = load(MINIMAL);
        assertFalse(PlanConfig.measureEnabled(off),
                "a connector that starts reporting because it was installed is one nobody chose");
        assertTrue(PlanConfig.validate(off).isEmpty(), () -> PlanConfig.validate(off).toString());

        Config on = load(MINIMAL + """
                [measure]
                enabled = true
                credential = "c"
                """);
        assertTrue(PlanConfig.measureEnabled(on));
        assertEquals("activityindex", PlanConfig.measureName(on));
        assertEquals(Duration.ofDays(21), PlanConfig.measureWindow(on),
                "the window is what the host's index covers, not a span this connector picks");
        assertEquals(Duration.ofMinutes(15), PlanConfig.measureSweep(on));
        assertTrue(PlanConfig.validate(on).isEmpty(), () -> PlanConfig.validate(on).toString());
    }

    @Test
    @DisplayName("a cadence at or above the window is refused, because nothing stays fresh")
    void cadenceMustBeWellInsideTheWindow() {
        // This is the mitigation for the one narrowing measures carry: a role
        // granted on a measure survives the reporter going away, because a
        // stale observation refuses without emitting. A reporter that measures
        // no more often than the window it measures leaves EVERY observation
        // stale, so the mitigation has to be enforced rather than documented.
        List<String> equal = PlanConfig.validate(load(MINIMAL + """
                [measure]
                enabled = true
                credential = "c"
                windowseconds = 3600
                sweepseconds = 3600
                """));
        assertEquals(1, equal.size(), equal::toString);
        assertTrue(equal.get(0).contains("well below"), equal::toString);

        List<String> longer = PlanConfig.validate(load(MINIMAL + """
                [measure]
                enabled = true
                credential = "c"
                windowseconds = 3600
                sweepseconds = 7200
                """));
        assertEquals(1, longer.size(), longer::toString);

        assertTrue(PlanConfig.validate(load(MINIMAL + """
                [measure]
                enabled = true
                credential = "c"
                windowseconds = 3600
                sweepseconds = 60
                """)).isEmpty(), "a cadence well inside the window was refused");
    }

    @Test
    @DisplayName("reporting without its own credential is refused")
    void reportingNeedsItsOwnCredential() {
        // The read-only credential this connector already holds cannot write,
        // and widening it would let the least-audited surface in the system
        // manufacture entitlement.
        List<String> problems = PlanConfig.validate(load(MINIMAL + """
                [measure]
                enabled = true
                """));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("measure.credential"), problems::toString);
    }

    @Test
    @DisplayName("a non-positive window or cadence is refused, and says one thing about it")
    void nonPositiveIsRefused() {
        // EXACTLY one problem each. Asserting only "not empty" let a boundary
        // mutant survive: with `window >= 0` instead of `> 0`, a window of zero
        // produced its own complaint AND the cadence complaint, and a test that
        // only counted "some" could not tell.
        List<String> zeroWindow = PlanConfig.validate(load(MINIMAL + """
                [measure]
                enabled = true
                credential = "c"
                windowseconds = 0
                """));
        assertEquals(1, zeroWindow.size(), zeroWindow::toString);
        assertTrue(zeroWindow.get(0).contains("windowseconds"), zeroWindow::toString);

        List<String> zeroSweep = PlanConfig.validate(load(MINIMAL + """
                [measure]
                enabled = true
                credential = "c"
                sweepseconds = 0
                """));
        assertEquals(1, zeroSweep.size(), zeroSweep::toString);
    }

    @Test
    @DisplayName("a blank credential is refused, not treated as one that was set")
    void blankCredentialIsRefused() {
        // A quoted empty string looks configured. If it passed, the connector
        // would start and every report would be refused by core with nothing
        // in this file to explain why.
        List<String> problems = PlanConfig.validate(load(MINIMAL + """
                [measure]
                enabled = true
                credential = "   "
                """));
        assertEquals(1, problems.size(), problems::toString);
        assertTrue(problems.get(0).contains("measure.credential"), problems::toString);
    }

    @Test
    @DisplayName("a blank measure name falls back to the default rather than being sent blank")
    void blankNameFallsBack() {
        assertEquals("activityindex", PlanConfig.measureName(load(MINIMAL + """
                [measure]
                name = "  "
                """)), "a blank name would be reported to core as a measure called nothing");
    }

    @Test
    @DisplayName("nothing about reporting is validated while it is off")
    void disabledReportingIsNotValidated() {
        // Otherwise an operator cannot leave a half-written block in the file
        // while they decide, which is how a setting ends up somewhere worse.
        assertTrue(PlanConfig.validate(load(MINIMAL + """
                [measure]
                enabled = false
                windowseconds = 1
                sweepseconds = 999999
                """)).isEmpty());
    }
}
