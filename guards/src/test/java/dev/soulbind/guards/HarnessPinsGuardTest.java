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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every harness's pinned artefact list must survive a clone.
 *
 * <p>A {@code pins.env} is the OPPOSITE of a secret: it is the record of
 * exactly which bytes a stack run used, and a clone without it cannot reproduce
 * anything. But it ends in {@code .env}, and {@code .gitignore} excludes
 * {@code *.env} because secrets never belong in the tree.
 *
 * <p><b>That collision has now bitten twice.</b> The first time,
 * {@code harness/fullstack/pins.env} was silently swallowed and the fix was a
 * negation naming that one file. The second time,
 * {@code harness/flarum/pins.env} was swallowed by the same line — because the
 * fix had been written to cover the instance rather than the class, which is
 * the failure this guard exists to stop happening a third time.
 *
 * <p>Reads {@code .gitignore} rather than shelling out to git: the guards run
 * inside a toolchain container that has no reason to carry a git binary, and a
 * guard that silently skips when its tool is absent is worse than no guard.
 */
class HarnessPinsGuardTest {

    private static List<Path> pinsFiles() {
        Path harness = SourceTree.repoRoot().resolve("harness");
        if (!Files.isDirectory(harness)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(harness, 3)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("pins.env"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("could not walk " + harness, e);
        }
    }

    @Test
    @DisplayName("there is at least one pins file, so this guard is not vacuous")
    void theGuardHasSomethingToGuard() {
        // A guard that silently matches nothing reads as coverage. If the
        // harnesses are ever restructured so pins live elsewhere, this fails and
        // somebody decides what the guard should look at now.
        assertFalse(
                pinsFiles().isEmpty(),
                "no harness/*/pins.env found. Either the harnesses moved, in which case this "
                        + "guard needs updating deliberately, or every pinned artefact list has "
                        + "gone missing -- and a clone can no longer reproduce a stack run.");
    }

    @Test
    @DisplayName("every harness pins file escapes the *.env rule")
    void everyPinsFileIsNegated() {
        String gitignore = SourceTree.read(SourceTree.repoRoot().resolve(".gitignore"));

        List<String> negations = new ArrayList<>();
        boolean excludesEnv = false;
        for (String raw : gitignore.split("\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.equals("*.env") || line.equals("**/*.env")) {
                excludesEnv = true;
            }
            if (line.startsWith("!")) {
                negations.add(line.substring(1));
            }
        }

        if (!excludesEnv) {
            // Nothing to escape from. The guard has no work, and saying so is
            // better than passing silently as though it had done some.
            return;
        }

        List<String> unprotected = new ArrayList<>();
        for (Path pins : pinsFiles()) {
            String rel = SourceTree.rel(pins).replace('\\', '/');
            if (negations.stream().noneMatch(n -> matches(n, rel))) {
                unprotected.add(rel);
            }
        }

        assertTrue(
                unprotected.isEmpty(),
                () -> "gitignore's *.env rule swallows a pinned artefact list: " + unprotected
                        + ". A pins file is the record of exactly which bytes a stack run used; "
                        + "a clone without it cannot reproduce anything. Add a GLOB negation "
                        + "that covers the class, not another line naming this one file -- "
                        + "naming the file is what let this happen the second time.");
    }

    /**
     * Whether a gitignore pattern covers a path.
     *
     * <p>Only the two shapes this rule needs: an exact path, and a single
     * {@code *} standing for one path segment. Deliberately not a full
     * gitignore implementation — a half-written one would quietly accept
     * patterns git does not, which is the direction that turns a guard into
     * false reassurance.
     */
    private static boolean matches(String pattern, String path) {
        String p = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        if (p.equals(path)) {
            return true;
        }
        if (!p.contains("*")) {
            return false;
        }
        String regex = p.replace(".", "\\.").replace("*", "[^/]*");
        return path.matches(regex);
    }

    @Test
    @DisplayName("GUARD FIRES: a pins file with no negation is rejected")
    void guardFiresOnAnUnprotectedPath() {
        // The engine, against a fixture pair rather than the real tree, so the
        // must-fail case is proven without breaking the repository to prove it.
        assertFalse(
                matches("harness/fullstack/pins.env", "harness/flarum/pins.env"),
                "a negation naming one harness must NOT be read as covering another -- that "
                        + "misreading is precisely what this guard exists to catch");
        assertTrue(
                matches("harness/*/pins.env", "harness/flarum/pins.env"),
                "a single-segment glob must cover a sibling harness");
        assertFalse(
                matches("harness/*/pins.env", "harness/a/b/pins.env"),
                "a single * must not cross a path separator; git does not let it either, and "
                        + "a guard that is more permissive than git approves patterns git will "
                        + "ignore");
    }
}
