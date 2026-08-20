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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code NOTICE} says what actually ships, and does not promise what does not
 * exist.
 *
 * <p><b>Why this guard exists.</b> From Phase 0 until Phase 8, NOTICE stated
 * that "a complete third-party licence inventory is generated at build time and
 * ships in every distributed artifact". No generator was ever written. That is
 * a false statement in the legal file that accompanies every copy of this
 * software — the one document whose entire purpose is to be relied upon by
 * somebody who is not reading the code.
 *
 * <p>It survived eight phases because nothing read it. Every other claim this
 * repository makes about itself is held to the code by a guard; this one was
 * prose, and prose drifts silently.
 *
 * <p>Two things are asserted, and they are different claims:
 *
 * <ol>
 *   <li><b>Nothing in the dependency catalogue is missing from NOTICE.</b> The
 *       catalogue is where a dependency is added, so it is the side that moves;
 *       an addition that never reaches NOTICE is an undisclosed third party.</li>
 *   <li><b>NOTICE does not claim a generated inventory while no generator
 *       exists.</b> When the Phase 10 packaging work lands one, this assertion
 *       is what makes updating the claim part of landing it, rather than
 *       something to remember.</li>
 * </ol>
 */
class NoticeGuardTest {

    private static final Path NOTICE = SourceTree.repoRoot().resolve("NOTICE");
    private static final Path CATALOGUE =
            SourceTree.repoRoot().resolve("gradle/libs.versions.toml");

    /**
     * Matches a catalogue entry's Maven coordinate.
     *
     * <p>Only the {@code module = "group:artifact"} half is taken. Versions
     * move on their own schedule and pinning them here would make a routine
     * bump fail this guard for no licence-related reason.
     */
    private static final Pattern MODULE =
            Pattern.compile("module\\s*=\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("every catalogued dependency is disclosed in NOTICE")
    void everyDependencyIsDisclosed() throws IOException {
        String notice = Files.readString(NOTICE, StandardCharsets.UTF_8);
        String catalogue = Files.readString(CATALOGUE, StandardCharsets.UTF_8);

        List<String> modules = new ArrayList<>();
        Matcher m = MODULE.matcher(catalogue);
        while (m.find()) {
            modules.add(m.group(1));
        }

        // The catalogue emptying, or the regex ceasing to match, would satisfy
        // the loop below without asserting anything. Twelve entries exist; the
        // floor is what must be visible for the rest to mean something.
        assertTrue(modules.size() >= 10,
                () -> "only " + modules.size() + " catalogue entries found (" + modules
                        + "). Either the catalogue shrank drastically or this guard has"
                        + " stopped parsing it, and an empty list discloses nothing.");

        List<String> undisclosed = modules.stream()
                .filter(module -> !notice.contains(module))
                .toList();

        assertTrue(undisclosed.isEmpty(),
                () -> "these dependencies are in gradle/libs.versions.toml and not in"
                        + " NOTICE: " + undisclosed + ". NOTICE is the file a redistributor"
                        + " relies on to know what they are shipping, so an omission there"
                        + " is not a documentation gap.");
    }

    @Test
    @DisplayName("NOTICE does not claim a generated inventory while none is generated")
    void noticeDoesNotPromiseAGenerator() throws IOException {
        String notice = Files.readString(NOTICE, StandardCharsets.UTF_8);

        // The claim, roughly as it was worded for eight phases. Matched loosely
        // on purpose: the failure mode is somebody reinstating the promise in
        // slightly different words, ahead of the thing that would make it true.
        boolean claimsGeneration = notice.contains("is generated at build time")
                || notice.contains("generated inventory ships")
                || notice.contains("inventory is generated");

        boolean generatorExists = generatorExists();

        assertFalse(claimsGeneration && !generatorExists,
                "NOTICE says a third-party licence inventory is generated at build time,"
                        + " and no task in build-logic/ generates one. That is a false"
                        + " statement in a legal file. Either write the generator (§16, a"
                        + " Phase 10 deliverable) or state what actually ships.");
    }

    /**
     * Whether anything in the build actually produces a licence inventory.
     *
     * <p>Deliberately a search for a TASK rather than for an output file: an
     * output file can be left behind by a run that no longer happens, and this
     * guard would then agree with a NOTICE that had become untrue again.
     */
    private static boolean generatorExists() throws IOException {
        Path buildLogic = SourceTree.repoRoot().resolve("build-logic/src/main/kotlin");
        if (!Files.isDirectory(buildLogic)) {
            return false;
        }
        try (var paths = Files.walk(buildLogic)) {
            return paths.filter(Files::isRegularFile)
                    .anyMatch(path -> {
                        try {
                            String text = Files.readString(path, StandardCharsets.UTF_8);
                            return text.contains("licenceInventory")
                                    || text.contains("licenseInventory");
                        } catch (IOException e) {
                            return false;
                        }
                    });
        }
    }
}
