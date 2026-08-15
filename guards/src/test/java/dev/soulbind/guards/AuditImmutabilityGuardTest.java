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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The audit-immutability guard.
 *
 * <p>Audit is append-only <b>in fact rather than in policy</b>: the repository
 * interface exposes no update and no delete, so the capability to alter a
 * recorded event does not exist for any caller to acquire — by accident, by
 * refactor, or under deadline pressure at two in the morning.
 *
 * <p>An interface can grow a method, though, and a SQL string can be written
 * inside an implementation without one. This guard asserts both directions:
 *
 * <ol>
 *   <li>No {@code UPDATE}, {@code DELETE} or {@code TRUNCATE} targeting the
 *       audit table appears anywhere in production source.
 *   <li>{@link dev.soulbind.core.storage.AuditRepository} declares no method
 *       whose name suggests mutation.
 * </ol>
 *
 * <p><b>What this does NOT prove:</b> that everything which should be audited
 * is. That is a completeness claim, asserted from both sides by the
 * simulated-user tier. This proves only that what was recorded cannot be
 * unrecorded.
 *
 * <p>A retention policy, if one is ever wanted, is a separate deliberate
 * mechanism with its own audit trail — and it will have to change this guard,
 * on purpose, in a commit somebody signs.
 */
class AuditImmutabilityGuardTest {

    /**
     * Statements that would alter recorded history.
     *
     * <p>Scoped to the audit tables specifically rather than banning the verbs
     * outright: every other table in the system is legitimately mutable, and a
     * guard that fired on {@code UPDATE connectors} would be suppressed within
     * the week.
     */
    private static final Pattern AUDIT_MUTATION = Pattern.compile(
            "(?i)\\b(UPDATE\\s+audit\\b|DELETE\\s+FROM\\s+audit\\b|TRUNCATE\\s+(TABLE\\s+)?audit\\b"
                    + "|DROP\\s+TABLE\\s+(IF\\s+EXISTS\\s+)?audit\\b)");

    /**
     * The allocator row is not history.
     *
     * <p>{@code audit_seq} holds one integer: the next sequence to hand out.
     * Updating it is how a sequence gets allocated, and it records nothing that
     * happened. The exemption covers exactly that table — a mutation of
     * {@code audit} itself is still a violation, and the word boundary in the
     * pattern above is what keeps the two apart.
     */
    private static final Pattern ALLOCATOR = Pattern.compile("(?i)\\baudit_seq\\b");

    /** Method names on the audit repository that would mean history is editable. */
    private static final Pattern MUTATING_NAME = Pattern.compile(
            "(?i)\\b(update|delete|remove|purge|truncate|clear|erase|redact|rewrite|prune)\\w*\\s*\\(");

    @Test
    @DisplayName("nothing in production source mutates the audit table")
    void noAuditMutation() {
        List<String> violations = scan(SourceTree.repoRoot(), SourceTree.productionModules());
        assertTrue(
                violations.isEmpty(),
                () -> "audit is append-only in fact, not in policy. A statement that alters a "
                        + "recorded event makes the log evidence of nothing.\n  "
                        + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("the audit repository declares no mutating method")
    void repositoryHasNoMutator() {
        Path repository = SourceTree.repoRoot().resolve(
                "core/src/main/java/dev/soulbind/core/storage/AuditRepository.java");
        assertTrue(
                java.nio.file.Files.isRegularFile(repository),
                () -> "the audit repository is not where this guard expects it (" + repository
                        + "); a guard that cannot find its subject is not passing, it is blind");

        List<String> violations = declaredMutators(repository);
        assertTrue(
                violations.isEmpty(),
                () -> "the capability to alter a recorded event must not exist for a caller to "
                        + "acquire.\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("GUARD FIRES: a statement mutating the audit table is rejected")
    void mutationFixtureIsRejected() {
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");
        List<String> violations = scan(fixtures, List.of("audit-mutation-violation"));

        assertFalse(
                violations.isEmpty(),
                "the must-fail fixture was not rejected: either it stopped mutating audit, or "
                        + "the guard stopped detecting it");
        assertTrue(
                violations.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("delete")),
                () -> "expected the offending statement to be named: " + violations);
    }

    @Test
    @DisplayName("GUARD FIRES: a mutating method on the repository interface is rejected")
    void mutatingMethodFixtureIsRejected() {
        Path fixture = SourceTree.repoRoot().resolve(
                "guards/src/test/resources/fixtures/audit-mutation-violation/AuditRepository.java");
        List<String> violations = declaredMutators(fixture);

        assertFalse(
                violations.isEmpty(),
                "a repository declaring a delete method was not rejected");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("deleteBefore")),
                () -> "expected the method to be named: " + violations);
    }

    @Test
    @DisplayName("the allocator row stays writable -- it is not history")
    void allocatorIsNotHistory() {
        // Stated as a test rather than left as a comment, because the exemption
        // is the kind of thing a later reader would assume was an oversight and
        // "fix" by tightening the pattern -- breaking every append.
        String statement = "UPDATE audit_seq SET next_seq = next_seq + 1 WHERE id = 1";
        assertTrue(ALLOCATOR.matcher(statement).find());
        assertFalse(
                AUDIT_MUTATION.matcher(statement).find()
                        && !ALLOCATOR.matcher(statement).find(),
                "allocating a sequence number must not read as rewriting history");

        // And the real thing still does.
        assertTrue(AUDIT_MUTATION.matcher("DELETE FROM audit WHERE seq < 100").find());
    }

    private static List<String> scan(Path root, List<String> modules) {
        List<String> violations = new ArrayList<>();
        for (String module : modules) {
            for (Path src : SourceTree.javaSourcesUnder(root.resolve(module))) {
                String rel = SourceTree.rel(src).replace('\\', '/');

                // PRODUCTION source only, and the reason covers exactly that.
                //
                // The guard fired on its first run against
                // AuditRepositoryTest, which holds `"'; DROP TABLE audit; --"`
                // -- a hostile-input value in the test that proves the
                // repository resists injection. A guard that fires on the test
                // proving the defence works is a guard that gets suppressed
                // rather than obeyed.
                //
                // What this does not cover: a test that deletes audit rows
                // directly to mask a failure. The repository interface offers
                // no way to, which the second test in this class asserts, and
                // reaching past it means writing raw JDBC in a test -- visible
                // in review in a way a quiet call to `delete()` would not be.
                if (!rel.contains("/src/main/")) {
                    continue;
                }

                String[] lines = SourceTree.read(src).split("\n", -1);
                boolean inBlockComment = false;
                for (int i = 0; i < lines.length; i++) {
                    String code = stripComment(lines[i], inBlockComment);
                    inBlockComment = updateBlockState(lines[i], inBlockComment);
                    if (code.isBlank() || ALLOCATOR.matcher(code).find()) {
                        continue;
                    }
                    Matcher m = AUDIT_MUTATION.matcher(code);
                    if (m.find()) {
                        violations.add("%s:%d %s -> %s"
                                .formatted(rel, i + 1, m.group().strip(), lines[i].strip()));
                    }
                }
            }
        }
        // The SQL migrations too: a mutation hidden in DDL is still a mutation,
        // and the source scan above only reads Java.
        violations.addAll(scanMigrations(root));
        return violations;
    }

    private static List<String> scanMigrations(Path root) {
        List<String> violations = new ArrayList<>();
        Path migrations = root.resolve("core/src/main/resources/db/migration");
        if (!java.nio.file.Files.isDirectory(migrations)) {
            return violations;
        }
        try (var walk = java.nio.file.Files.walk(migrations)) {
            for (Path sql : walk.filter(java.nio.file.Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".sql")).toList()) {
                String[] lines = SourceTree.read(sql).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    int comment = line.indexOf("--");
                    String code = comment >= 0 ? line.substring(0, comment) : line;
                    if (code.isBlank() || ALLOCATOR.matcher(code).find()) {
                        continue;
                    }
                    Matcher m = AUDIT_MUTATION.matcher(code);
                    if (m.find()) {
                        violations.add("%s:%d %s -> %s"
                                .formatted(SourceTree.rel(sql), i + 1, m.group().strip(),
                                        line.strip()));
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("cannot walk " + migrations, e);
        }
        return violations;
    }

    private static List<String> declaredMutators(Path javaFile) {
        List<String> violations = new ArrayList<>();
        String[] lines = SourceTree.read(javaFile).split("\n", -1);
        boolean inBlockComment = false;
        for (int i = 0; i < lines.length; i++) {
            String code = stripComment(lines[i], inBlockComment);
            inBlockComment = updateBlockState(lines[i], inBlockComment);
            if (code.isBlank()) {
                continue;
            }
            Matcher m = MUTATING_NAME.matcher(code);
            if (m.find()) {
                violations.add("%s:%d declares %s"
                        .formatted(SourceTree.rel(javaFile), i + 1, m.group().strip()));
            }
        }
        return violations;
    }

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
}
