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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every workflow action is pinned to a commit, not to a tag.
 *
 * <p>This repository already refuses mutable references everywhere it matters:
 * container images by SHA-256 digest, Paper and Velocity by SHA-256 in
 * {@code pins.env}, the PHP PHAR by checksum. The argument is one line and the
 * same every time — <b>a tag is a name, and a name can be repointed at
 * different bytes by whoever owns it.</b>
 *
 * <p>Workflows were written as the one exception, and an automated review
 * caught it. The instance was fixed the same afternoon, which is exactly the
 * point at which {@code HarnessPinsGuardTest} would remind you that fixing the
 * instance is what let the same bug happen a second time. Nothing stopped the
 * next {@code uses:} line — in a new workflow, or added to an existing one
 * next year — from floating again.
 *
 * <p><b>The release workflow is why this is a guard and not a preference.</b>
 * It holds {@code contents: write}, so a repointed tag there is somebody else's
 * code running with permission to publish artifacts under this project's name.
 *
 * <p>The rule is deliberately narrow: a 40-character hexadecimal ref. Local
 * actions ({@code ./.github/...}) and Docker actions ({@code docker://...})
 * are not tag references and are left alone.
 */
class ActionPinGuardTest {

    /**
     * A {@code uses:} line, and the reference it names.
     *
     * <p>Matched with a regex rather than a YAML parse on purpose: a parse
     * would have to understand every shape a workflow can take before it could
     * report on any of them, and the thing being looked for is a token on a
     * line. A comment after the ref is expected — that is where the version
     * goes — so the pattern stops at whitespace or {@code #}.
     */
    private static final Pattern USES =
            Pattern.compile("^\\s*(?:-\\s*)?uses:\\s*(\\S+)", Pattern.MULTILINE);

    /** A full commit id. Not an abbreviation: a short ref is ambiguous by design. */
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-f]{40}$");

    private static List<Path> workflows() {
        Path dir = SourceTree.repoRoot().resolve(".github/workflows");
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir, 1)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".yml") || n.endsWith(".yaml");
                    })
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("could not walk " + dir, e);
        }
    }

    /** The unpinned references in one workflow's text, as {@code line: ref}. */
    private static List<String> unpinnedIn(String yaml) {
        List<String> unpinned = new ArrayList<>();
        Matcher m = USES.matcher(yaml);
        while (m.find()) {
            String ref = m.group(1);

            // Not a tag reference at all, so there is no tag to float.
            if (ref.startsWith("./") || ref.startsWith("docker://")) {
                continue;
            }

            int at = ref.lastIndexOf('@');
            if (at < 0) {
                unpinned.add(ref + " (no ref at all)");
                continue;
            }
            if (!COMMIT_SHA.matcher(ref.substring(at + 1)).matches()) {
                unpinned.add(ref);
            }
        }
        return unpinned;
    }

    @Test
    @DisplayName("there are workflows to guard, so this guard is not vacuous")
    void theGuardHasSomethingToGuard() {
        // Two empty lists compare equal. A guard that silently matches nothing
        // reads as coverage, which is the failure mode every guard here is
        // written to avoid being an instance of.
        assertFalse(
                workflows().isEmpty(),
                "no workflows found under .github/workflows. Either CI moved, in which case "
                        + "this guard needs updating deliberately, or it has gone missing.");

        long usesLines = workflows().stream()
                .map(SourceTree::read)
                .mapToLong(text -> USES.matcher(text).results().count())
                .sum();
        assertTrue(
                usesLines > 0,
                "the workflows contain no `uses:` lines at all, so this guard is asserting "
                        + "nothing. Either every action was replaced with a plain `run:` step -- "
                        + "in which case delete this guard deliberately -- or the pattern has "
                        + "stopped matching the file format.");
    }

    @Test
    @DisplayName("every action is pinned to a commit, never to a tag")
    void everyActionIsPinnedToACommit() {
        List<String> offenders = new ArrayList<>();
        for (Path workflow : workflows()) {
            for (String ref : unpinnedIn(SourceTree.read(workflow))) {
                offenders.add(SourceTree.rel(workflow) + ": " + ref);
            }
        }

        assertTrue(
                offenders.isEmpty(),
                () -> "a workflow action is pinned to a tag rather than a commit: " + offenders
                        + ". A tag is a name and a name can be repointed at different bytes by "
                        + "whoever owns it -- which is why every container image, jar and PHAR "
                        + "in this repository is pinned by digest or checksum. The release "
                        + "workflow holds `contents: write`, so a repointed tag there is "
                        + "somebody else's code running with permission to publish under this "
                        + "project's name. Resolve the tag to its commit and put the version in "
                        + "a comment beside it.");
    }

    @Test
    @DisplayName("the guard fires on a workflow that floats, and only on the lines that do")
    void theGuardFiresOnTheBrokenFixture() {
        // The fixture holds three references: two floating, one pinned. Asserting
        // the COUNT and the CONTENT rather than merely "not empty" is what proves
        // the guard distinguishes -- a guard that objected to every `uses:` line
        // it saw would pass a not-empty check while being useless.
        Path fixture = SourceTree.repoRoot()
                .resolve("guards/src/test/resources/fixtures/unpinned-action/workflow.yml");
        assertTrue(Files.isRegularFile(fixture), () -> "missing fixture: " + fixture);

        List<String> found = unpinnedIn(SourceTree.read(fixture));

        assertEquals(
                List.of("actions/checkout@v4", "some-org/some-action@main"),
                found,
                () -> "the guard must catch both floating refs and must NOT catch the pinned "
                        + "one beside them, nor the plain `run:` step below. Got: " + found);
    }
}
