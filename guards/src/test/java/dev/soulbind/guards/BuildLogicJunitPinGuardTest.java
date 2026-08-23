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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * The one version number this repository is forced to write down twice.
 *
 * <p>{@code build-logic} is a separate included build with its own
 * {@code settings.gradle.kts}, so it cannot read {@code gradle/libs.versions
 * .toml}. Its test source set needs JUnit, and the only way to say which JUnit
 * is a literal — which is exactly the drift this guard's own commit removed
 * from the artifact version, arriving somewhere else.
 *
 * <p>Not solvable by deleting one of them, so it is solved by asserting they
 * agree. A tree where the guards run on 5.11.4 and the build-logic tests run on
 * something else is a tree where a JUnit upgrade is half-applied and nobody is
 * told; the halves fail differently and the second half fails in a build nobody
 * routinely reads.
 */
class BuildLogicJunitPinGuardTest {

    /** {@code junit = "5.11.4"} in the version catalog's {@code [versions]} block. */
    private static final Pattern CATALOG_PIN =
            Pattern.compile("(?m)^\\s*junit\\s*=\\s*\"([^\"]+)\"\\s*$");

    /**
     * The JUnit coordinate in build-logic's dependencies.
     *
     * <p>The version group requires a leading digit, so a floating coordinate —
     * {@code junit-jupiter:+}, {@code junit-jupiter:[5,6)}, or the version
     * omitted entirely — does not match and is reported as absent rather than
     * silently compared against nothing.
     */
    private static final Pattern BUILD_LOGIC_PIN =
            Pattern.compile("org\\.junit\\.jupiter:junit-jupiter:([0-9][^\"]*)");

    private static String pin(Pattern pattern, String text, String what) {
        Matcher m = pattern.matcher(text);
        assertTrue(m.find(),
                "no pinned JUnit version found in " + what + ". Either it moved, or it is"
                        + " no longer pinned to an exact version -- and an unpinned test"
                        + " framework is a build whose behaviour changes without a commit.");
        return m.group(1);
    }

    /**
     * The comparison, taking text rather than reading files.
     *
     * <p>So that the must-fail cases below drive the identical code the real
     * one does. A second, parallel implementation checking the fixtures would
     * prove only that the second implementation works — {@link SourceTree}'s
     * doc says the same thing about every other guard here.
     */
    private static void assertPinsAgree(String catalogText, String buildLogicText) {
        String catalog = pin(CATALOG_PIN, catalogText, "gradle/libs.versions.toml");
        String buildLogic = pin(BUILD_LOGIC_PIN, buildLogicText, "build-logic/build.gradle.kts");
        assertEquals(catalog, buildLogic,
                "gradle/libs.versions.toml pins JUnit at " + catalog + " but build-logic/build"
                        + ".gradle.kts pins it at " + buildLogic + ". build-logic cannot read"
                        + " the catalog, so this is the only thing keeping the two honest.");
    }

    @Test
    @DisplayName("build-logic's JUnit is the tree's JUnit")
    void thePinsAgree() throws IOException {
        assertPinsAgree(
                Files.readString(SourceTree.repoRoot().resolve("gradle/libs.versions.toml")),
                Files.readString(SourceTree.repoRoot().resolve("build-logic/build.gradle.kts")));
    }

    @Test
    @DisplayName("it fires when the two disagree")
    void aDisagreementIsCaught() {
        AssertionFailedError raised = assertThrows(AssertionFailedError.class, () ->
                assertPinsAgree(
                        "[versions]\njunit = \"5.11.4\"\n",
                        "testImplementation(\"org.junit.jupiter:junit-jupiter:5.10.0\")\n"));
        assertTrue(raised.getMessage().contains("5.10.0"),
                "the failure names neither version, so somebody reading it has to go and"
                        + " find both by hand: " + raised.getMessage());
    }

    @Test
    @DisplayName("it fires when build-logic's pin floats instead of disagreeing")
    void aFloatingPinIsCaught() {
        // Distinct from a disagreement, and the case a plain equality check
        // would miss by comparing against a version it never found. Every one
        // of these resolves to whatever the repository serves that day.
        for (String floating : new String[] {
                "testImplementation(\"org.junit.jupiter:junit-jupiter:+\")\n",
                "testImplementation(\"org.junit.jupiter:junit-jupiter:[5,6)\")\n",
                "testImplementation(\"org.junit.jupiter:junit-jupiter:latest.release\")\n",
                "testImplementation(\"org.junit.jupiter:junit-jupiter\")\n"}) {
            assertThrows(AssertionFailedError.class,
                    () -> assertPinsAgree("[versions]\njunit = \"5.11.4\"\n", floating),
                    "this floating coordinate was accepted as a pin: " + floating.trim());
        }
    }

    @Test
    @DisplayName("it fires when the catalog entry is gone rather than reporting agreement")
    void aMissingCatalogEntryIsCaught() {
        // The vacuous-pass shape: both sides absent must not read as "equal".
        assertThrows(AssertionFailedError.class, () ->
                assertPinsAgree(
                        "[versions]\ngradle = \"9.6.1\"\n",
                        "testImplementation(\"org.junit.jupiter:junit-jupiter:5.11.4\")\n"));
    }

    @Test
    @DisplayName("agreement really does pass, so the cases above mean something")
    void agreementPasses() {
        // Without this every assertThrows above would still pass if the
        // comparison threw unconditionally.
        assertPinsAgree(
                "[versions]\njunit = \"5.11.4\"\n",
                "testImplementation(\"org.junit.jupiter:junit-jupiter:5.11.4\")\n");
    }
}
