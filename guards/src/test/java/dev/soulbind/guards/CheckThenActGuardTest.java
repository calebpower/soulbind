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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check-then-act guard.
 *
 * <p><b>Written because four defects of one shape reached the repository, and
 * no test on this workstation could have caught any of them.</b>
 *
 * <p>A write that reads a value and then writes based on it races: two callers
 * read the same state and both act. The consequences here were a duplicate
 * primary key surfacing as an HTTP 500, forty-five distinct audit sequences out
 * of two hundred appends, and an event cursor that could move backwards and
 * redeliver events a connector had already applied.
 *
 * <h2>Why a guard rather than a test</h2>
 *
 * <p>SQLite serialises write transactions at the engine level. The interleaving
 * that produces these defects <em>cannot occur</em> there — not because of how
 * this project configures it, but because of what SQLite is. A runtime test on a
 * workstation with only SQLite will pass no matter what the code does.
 *
 * <p>The concurrency contract suite runs writes unserialised and does catch some
 * of this on a real multi-writer backend; it caught none of these three when
 * they were reverted. So the defect class needs a mechanism that works by
 * reading the code, and this is it.
 *
 * <p><b>What this does NOT prove:</b> that the flagged shape is always wrong, or
 * that its absence means correctness. It proves that every read-then-write in
 * the storage layer was looked at by somebody, which is the property that was
 * actually missing.
 */
class CheckThenActGuardTest {

    /** A write path: everything inside a {@code jdbc.write(...)} lambda. */
    private static final Pattern WRITE_START = Pattern.compile("jdbc\\.write\\(");

    private static final Pattern SELECT = Pattern.compile("(?i)\"\\s*SELECT\\b");

    private static final Pattern MUTATION =
            Pattern.compile("(?i)\"\\s*(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM)\\b");

    /**
     * The one sanctioned read-then-write, and the only exemption.
     *
     * <p>{@code Jdbc.ensureExists} reads AFTER the write fails, not before it —
     * it asserts the outcome rather than predicting it, which is exactly the
     * inversion that makes the race harmless. A block using it has already had
     * this thought.
     */
    private static final Pattern SANCTIONED = Pattern.compile("Jdbc\\.ensureExists");

    /**
     * An acknowledgement that a read-then-write was considered and is safe.
     *
     * <p>Not a suppression: the comment has to say why, and it appears in the
     * failure message of nothing — it appears in the code, where the next reader
     * is. A block carrying it is a block somebody reasoned about.
     */
    private static final String REVIEWED = "CHECK-THEN-ACT REVIEWED:";

    @Test
    @DisplayName("no unreviewed read-then-write in any storage write path")
    void noCheckThenAct() {
        List<String> violations = scan(SourceTree.repoRoot().resolve("core"));
        assertTrue(
                violations.isEmpty(),
                () -> "a write path reads a value and then writes based on it. Two callers "
                        + "will read the same state and both act -- and SQLite CANNOT show you "
                        + "this, because its write transactions are serialised by the engine.\n"
                        + "  Either carry the predicate in the write (UPDATE ... WHERE), let a "
                        + "constraint decide (Jdbc.ensureExists), or write why it is safe with "
                        + "a `" + REVIEWED + "` comment.\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("GUARD FIRES: a read-then-write fixture is rejected")
    void fixtureIsRejected() {
        Path fixtures = SourceTree.repoRoot()
                .resolve("guards/src/test/resources/fixtures/check-then-act-violation");
        List<String> violations = scan(fixtures);

        assertFalse(
                violations.isEmpty(),
                "the must-fail fixture was not rejected: either it stopped reading before "
                        + "writing, or the guard stopped noticing");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("Racy")),
                () -> "expected the offending class to be named: " + violations);
    }

    /**
     * Finds write paths containing a read before a mutation.
     *
     * <p>Brace-counted rather than parsed. A real parser would be better and is
     * not worth the dependency: the shape being looked for is local, and a
     * false positive costs a comment while a false negative costs a 500.
     */
    private static List<String> scan(Path root) {
        List<String> violations = new ArrayList<>();

        for (Path src : SourceTree.javaSourcesUnder(root)) {
            String rel = SourceTree.rel(src).replace('\\', '/');
            if (!rel.contains("/src/main/")) {
                continue;
            }
            String[] lines = SourceTree.read(src).split("\n", -1);

            for (int i = 0; i < lines.length; i++) {
                if (!WRITE_START.matcher(lines[i]).find()) {
                    continue;
                }
                int depth = 0;
                boolean sawSelect = false;
                boolean reviewed = false;
                boolean sanctioned = false;
                int selectLine = -1;

                for (int j = i; j < lines.length; j++) {
                    String line = lines[j];
                    if (line.contains(REVIEWED)) {
                        reviewed = true;
                    }
                    // Matched against CODE, never prose. The first version
                    // checked the raw line, so a comment reading "see
                    // Jdbc.ensureExists for why" disarmed the guard for the
                    // whole block -- which is exactly how the reverted
                    // platformKind defect slipped past it during its own
                    // mutation check. A guard an explanation can switch off is
                    // not a guard.
                    if (SANCTIONED.matcher(stripComments(line)).find()) {
                        sanctioned = true;
                    }
                    if (SELECT.matcher(line).find()) {
                        sawSelect = true;
                        if (selectLine < 0) {
                            selectLine = j + 1;
                        }
                    }
                    if (sawSelect && MUTATION.matcher(line).find() && !reviewed && !sanctioned) {
                        violations.add("%s:%d reads at line %d then writes here -> %s"
                                .formatted(rel, j + 1, selectLine, line.strip()));
                        break;
                    }

                    depth += count(line, '{') - count(line, '}');
                    if (j > i && depth <= 0) {
                        break;
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Everything outside a comment.
     *
     * <p>Only the sanctioned-helper check uses this. The REVIEWED marker is
     * deliberately matched on the raw line, because it IS a comment — its whole
     * purpose is to be a note the next reader finds.
     */
    private static String stripComments(String line) {
        String out = line;
        int block = out.indexOf("/*");
        if (block >= 0) {
            out = out.substring(0, block);
        }
        int lineComment = out.indexOf("//");
        if (lineComment >= 0) {
            out = out.substring(0, lineComment);
        }
        return out.strip().startsWith("*") ? "" : out;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
