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
 * The storage seam guard.
 *
 * <p>Persistence sits behind repository interfaces with two implementations.
 * The moment a SQL string or a JDBC type appears outside the storage package,
 * a caller has learned which database is in use — and the next change will
 * quietly assume it, until the second backend stops working in a way nobody
 * notices because nobody runs it.
 *
 * <p>Two separate claims, asserted separately:
 * <ol>
 *   <li>No SQL and no {@code java.sql} type outside {@code core/storage}.
 *   <li>No <em>backend-conditional</em> logic outside the storage package —
 *       a mention of one backend's name in a branch, anywhere else, is the
 *       seam leaking even if no SQL came with it.
 * </ol>
 */
class StorageSeamGuardTest {

    /**
     * The package permitted to know about databases.
     *
     * <p>Matched on the PACKAGE path, not the source-set path: the storage
     * package's own tests must name both backends -- parameterising over them is
     * their entire job -- and must be able to hold a SQL-shaped corpus value.
     * Exempting only src/main would have made the guard fire on the tests that
     * prove the seam works, which is how a guard gets suppressed rather than
     * obeyed.
     */
    private static final String STORAGE_PACKAGE = "dev/soulbind/core/storage";

    /** Modules whose source must not know about databases. */
    private static final List<String> GUARDED_MODULES =
            List.of("core", "protocol", "connector-sdk",
                    "connector-discord", "connector-velocity", "connector-plan");

    private static final Pattern JDBC_TYPE = Pattern.compile(
            "\\bjava\\.sql\\.|\\bjavax\\.sql\\.|\\bConnection\\b|\\bPreparedStatement\\b"
                    + "|\\bResultSet\\b|\\bSQLException\\b|\\bDataSource\\b|\\bHikari");

    private static final Pattern SQL_TEXT = Pattern.compile(
            "(?i)\\b(SELECT\\s+.*\\bFROM\\b|INSERT\\s+INTO\\b|UPDATE\\s+\\w+\\s+SET\\b"
                    + "|DELETE\\s+FROM\\b|CREATE\\s+TABLE\\b|ALTER\\s+TABLE\\b|DROP\\s+TABLE\\b)");

    private static final Pattern BACKEND_NAME = Pattern.compile("(?i)\\b(sqlite|mariadb|mysql)\\b");

    @Test
    @DisplayName("no SQL and no JDBC type escapes the storage package")
    void seamHolds() {
        List<String> violations = scan(SourceTree.repoRoot(), GUARDED_MODULES);
        assertTrue(
                violations.isEmpty(),
                () -> "persistence sits behind the storage seam. A SQL string or JDBC type "
                        + "outside it means a caller has learned which database is in use.\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("GUARD FIRES: SQL outside the storage package is rejected")
    void sqlFixtureIsRejected() {
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");
        List<String> violations = scan(fixtures, List.of("storage-seam-violation"));

        assertFalse(
                violations.isEmpty(),
                "the must-fail fixture was not rejected: either it stopped containing SQL, or "
                        + "the guard stopped detecting it");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("SQL")),
                () -> "expected the SQL to be named in the violation: " + violations);
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("JDBC")),
                () -> "expected the JDBC type to be named too: " + violations);
    }

    @Test
    @DisplayName("GUARD FIRES: backend-conditional logic outside storage is rejected")
    void backendBranchFixtureIsRejected() {
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");
        List<String> violations = scan(fixtures, List.of("storage-backend-branch-violation"));

        assertFalse(
                violations.isEmpty(),
                "a module branching on the backend name was not rejected. That is the seam "
                        + "leaking even when no SQL comes with it.");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("backend name")),
                () -> "expected the backend name to be named in the violation: " + violations);
    }

    /**
     * The scanning engine, parameterised so the fixtures drive exactly this code.
     */
    private static List<String> scan(Path root, List<String> modules) {
        List<String> violations = new ArrayList<>();
        for (String module : modules) {
            for (Path src : SourceTree.javaSourcesUnder(root.resolve(module))) {
                String rel = SourceTree.rel(src).replace('\\', '/');
                if (rel.contains(STORAGE_PACKAGE)) {
                    continue; // the one package permitted to know
                }
                String[] lines = SourceTree.read(src).split("\n", -1);
                boolean inBlockComment = false;
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    String code = stripComment(line, inBlockComment);
                    inBlockComment = updateBlockState(line, inBlockComment);
                    if (code.isBlank()) {
                        continue;
                    }
                    if (JDBC_TYPE.matcher(code).find()) {
                        violations.add("%s:%d uses a JDBC type -> %s"
                                .formatted(rel, i + 1, line.strip()));
                    }
                    if (SQL_TEXT.matcher(code).find()) {
                        violations.add("%s:%d contains SQL -> %s"
                                .formatted(rel, i + 1, line.strip()));
                    }
                    if (BACKEND_NAME.matcher(code).find()) {
                        violations.add("%s:%d names a backend name in code -> %s"
                                .formatted(rel, i + 1, line.strip()));
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Removes comments before matching.
     *
     * <p>Prose explaining the seam necessarily mentions SQL and backend names —
     * this very repository's READMEs and javadoc do. A guard that fired on its
     * own explanation would be suppressed rather than obeyed, so it reads code
     * only. That is a real narrowing: a violation hidden inside a comment is not
     * caught. It is also not a violation, because a comment does not execute.
     */
    private static String stripComment(String line, boolean inBlockComment) {
        if (inBlockComment) {
            int end = line.indexOf("*/");
            return end < 0 ? "" : line.substring(end + 2);
        }
        String out = line;
        int blockStart = out.indexOf("/*");
        if (blockStart >= 0) {
            int blockEnd = out.indexOf("*/", blockStart);
            out = blockEnd >= 0
                    ? out.substring(0, blockStart) + out.substring(blockEnd + 2)
                    : out.substring(0, blockStart);
        }
        int lineComment = out.indexOf("//");
        if (lineComment >= 0) {
            out = out.substring(0, lineComment);
        }
        return out;
    }

    private static boolean updateBlockState(String line, boolean wasInBlock) {
        int lastOpen = line.lastIndexOf("/*");
        int lastClose = line.lastIndexOf("*/");
        if (wasInBlock) {
            return lastClose < 0;
        }
        return lastOpen >= 0 && lastClose < lastOpen;
    }

    /** Unused; kept so the locale rule is stated where the matching happens. */
    private static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
