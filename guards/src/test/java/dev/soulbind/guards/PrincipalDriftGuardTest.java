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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every capability grant in the harnesses is recorded in one place.
 *
 * <p><b>Why this guard exists.</b> Moving `identity.describe` from
 * `code-display` to `link-state-reader` broke four callers. Two were found by
 * review; the third by a session run and the fourth by the session run after
 * that — roughly an hour of battery time to learn something a single
 * repository-wide search would have answered immediately.
 *
 * <p>`harness/principals.txt` is now the one place a grant is written down, and
 * `harness/credential-smoke.sh` checks it against a real core in about thirty
 * seconds. This guard is the third leg: it asserts that no `--capabilities`
 * list in a harness script has drifted away from that table, so a grant changed
 * in a shell script and nowhere else fails `./gradlew build` rather than a
 * session.
 *
 * <p>It compares the SET of capabilities rather than the string, because the
 * order they are written in is not a property of anything.
 */
class PrincipalDriftGuardTest {

    private static final Path PRINCIPALS =
            SourceTree.repoRoot().resolve("harness/principals.txt");

    private static final List<Path> HARNESS_SCRIPTS = List.of(
            SourceTree.repoRoot().resolve("harness/flarum/stack.sh"),
            SourceTree.repoRoot().resolve("harness/fullstack/stack.sh"),
            // run.sh registers the t10 auditor itself -- the only principal a
            // single stage needs -- and a registration this guard does not scan
            // is a grant that can drift out of principals.txt unnoticed.
            SourceTree.repoRoot().resolve("harness/fullstack/run.sh"));

    private static final Pattern GRANT =
            Pattern.compile("--capabilities\\s+([a-z,-]+)");

    /** The capability sets recorded in the table, order-insensitive. */
    private static Set<Set<String>> recordedGrants() throws IOException {
        Set<Set<String>> grants = new LinkedHashSet<>();
        for (String line : Files.readAllLines(PRINCIPALS, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] columns = trimmed.split("\\|");
            if (columns.length < 2) {
                continue;
            }
            grants.add(Set.copyOf(Arrays.asList(columns[1].strip().split(","))));
        }
        return grants;
    }

    @Test
    @DisplayName("every capability grant in a harness script is recorded in principals.txt")
    void noGrantHasDrifted() throws IOException {
        Set<Set<String>> recorded = recordedGrants();

        // An empty or unparseable table satisfies "nothing is missing from it"
        // for every grant, which is the shape of vacuity this repository keeps
        // finding. Eight principals exist; the floor is what must parse for the
        // comparison below to mean anything.
        assertTrue(recorded.size() >= 6,
                () -> "only " + recorded.size() + " grants parsed out of principals.txt."
                        + " Every assertion below is 'this grant appears in the table', which"
                        + " an empty table cannot fail.");

        List<String> drifted = new ArrayList<>();
        int found = 0;
        for (Path script : HARNESS_SCRIPTS) {
            String text = Files.readString(script, StandardCharsets.UTF_8);
            Matcher m = GRANT.matcher(text);
            while (m.find()) {
                found++;
                Set<String> granted = Set.copyOf(Arrays.asList(m.group(1).split(",")));
                if (!recorded.contains(granted)) {
                    drifted.add(script.getFileName() + ": " + m.group(1));
                }
            }
        }

        final int grantsFound = found;
        assertTrue(grantsFound >= 6,
                () -> "only " + grantsFound + " capability grants found across the harness"
                        + " scripts;"
                        + " the pattern has stopped matching and this guard is reading"
                        + " nothing");

        assertTrue(drifted.isEmpty(),
                () -> "these capability grants are in a harness script and not in"
                        + " harness/principals.txt: " + drifted
                        + ". A grant that lives only in a shell script is one the credential"
                        + " smoke cannot check, which is how four callers were found one"
                        + " session run at a time. DECISIONS 10.3.");
    }

    @Test
    @DisplayName("the grant docs/install.md tells operators to use is recorded too")
    void documentedGrantIsRecorded() throws IOException {
        // The smoke covers the harness's own principals. The grant an OPERATOR
        // types comes from docs/install.md, and nothing checked that -- so it
        // sat there missing link-state-reader until a live Discord run had
        // /whoami refused with "this credential does not hold the capability
        // this operation requires". Fifth caller of identity.describe to be
        // broken by that grant (DECISIONS 9.8, 10.3).
        //
        // A document is not a script, so the drift check above could not see
        // it. This one reads the document.
        Path install = SourceTree.repoRoot().resolve("docs/install.md");
        assertTrue(Files.isRegularFile(install), "docs/install.md is missing");

        Set<String> recorded = new HashSet<>();
        for (String line : Files.readAllLines(PRINCIPALS)) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            String[] columns = text.split("\\|");
            if (columns.length >= 2) {
                recorded.add(normalise(columns[1]));
            }
        }
        assertFalse(recorded.isEmpty(), "parsed no grants out of principals.txt");

        Matcher m = Pattern.compile("--capabilities\\s+([a-z0-9,\\-]+)")
                .matcher(Files.readString(install));
        List<String> undocumented = new ArrayList<>();
        int found = 0;
        while (m.find()) {
            found++;
            String grant = normalise(m.group(1));
            if (!recorded.contains(grant)) {
                undocumented.add(m.group(1));
            }
        }

        assertTrue(found > 0,
                "docs/install.md contains no --capabilities example, so this guard asserted"
                        + " nothing. If the registration section moved, follow it.");
        assertTrue(undocumented.isEmpty(),
                "docs/install.md tells operators to register with grants that"
                        + " harness/principals.txt does not record, so nothing ever checks"
                        + " that they are sufficient: " + undocumented
                        + ". Add each to principals.txt with the operations it must be"
                        + " permitted -- credential-smoke.sh then attempts them against a"
                        + " real core in about thirty seconds.");
    }

    /** Capability lists compared as sets, so ordering and spacing do not matter. */
    private static String normalise(String capabilities) {
        return Arrays.stream(capabilities.split(","))
                .map(String::strip)
                .filter(c -> !c.isEmpty())
                .sorted()
                .collect(Collectors.joining(","));
    }
}
