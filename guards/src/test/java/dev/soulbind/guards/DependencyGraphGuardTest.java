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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards over what may enter the dependency graph.
 *
 * <p>Two rules live here because both are answered by reading declared
 * dependencies as text:
 *
 * <ul>
 *   <li><b>Config format.</b> Wherever soulbind owns a config file it is TOML.
 *       No YAML parser may appear in any Java module's dependency graph — not
 *       because YAML is bad, but because two config formats means two loaders,
 *       two sets of parsing edge cases, and a question at every new file.
 *   <li><b>Licence.</b> LGPL artifacts are permitted only unbundled. Nothing
 *       with a copyleft licence may be shaded into a distributed artifact,
 *       because that is the packaging decision that determines whether the
 *       operator can replace it — which is what satisfies LGPL in practice.
 * </ul>
 *
 * <p>What this does NOT prove: that a transitive dependency has not pulled a
 * YAML parser in behind a direct one. That claim needs a resolved dependency
 * graph, which arrives with the licence-report task in Phase 10; this guard
 * covers declared dependencies, which is where the mistake is actually made.
 */
class DependencyGraphGuardTest {

    /**
     * Coordinates that indicate a YAML parser. Substring match on the declared
     * dependency line, because a coordinate is not prose and a partial match
     * here is a true positive.
     */
    private static final List<String> YAML_PARSERS = List.of(
            "snakeyaml",
            "jackson-dataformat-yaml",
            "org.yaml",
            "yamlbeans",
            "eo-yaml");

    /**
     * Every module that produces Java bytecode, derived from the build rather
     * than hand-listed, so a new module cannot be created outside coverage.
     */
    private static List<String> javaModules() {
        return SourceTree.allModules();
    }

    /**
     * The one module permitted to declare a TOML parser.
     *
     * <p>Specification §5: soulbind's own config is TOML everywhere, through one
     * shared loader. "One loader" is only true if there is one parser — a second
     * module declaring tomlj is a second loader waiting to be written, with its
     * own idea of what an unknown key means.
     */
    private static final String TOML_OWNER = "config";

    @Test
    @DisplayName("no YAML parser is declared in any Java module")
    void noYamlParserDeclared() {
        List<String> violations = scanForYaml(SourceTree.repoRoot(), javaModules());

        assertTrue(
                violations.isEmpty(),
                () -> "soulbind's own config is TOML everywhere (specification §5). A YAML "
                        + "parser in the graph means a second config format is one import "
                        + "away.\n  " + String.join("\n  ", violations)
                        + "\nWhere a host platform imposes its own convention, the host wins "
                        + "and the config lives in the host's store -- not in a YAML file we "
                        + "parse ourselves.");
    }

    @Test
    @DisplayName("GUARD FIRES: a module declaring a YAML parser is rejected")
    void yamlFixtureIsRejected() {
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");

        List<String> violations = scanForYaml(fixtures, List.of("yaml-dependency-violation"));

        assertFalse(
                violations.isEmpty(),
                "the must-fail fixture was not rejected: either it stopped declaring a YAML "
                        + "parser, or the guard stopped detecting one");
        assertTrue(
                violations.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains("snakeyaml")),
                () -> "expected the fixture's parser to be named in the violation: " + violations);
    }

    @Test
    @DisplayName("exactly one module declares a TOML parser")
    void tomlHasOneEntryPoint() {
        List<String> declaring = new ArrayList<>();
        for (String module : javaModules()) {
            Path buildFile = SourceTree.repoRoot().resolve(module).resolve("build.gradle.kts");
            if (!Files.isRegularFile(buildFile)) {
                continue;
            }
            for (String line : SourceTree.read(buildFile).split("\n", -1)) {
                String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
                if (code.toLowerCase(Locale.ROOT).contains("libs.toml")
                        || code.toLowerCase(Locale.ROOT).contains("tomlj")) {
                    declaring.add(module);
                    break;
                }
            }
        }

        assertEquals(
                List.of(TOML_OWNER),
                declaring,
                "the shared loader in `" + TOML_OWNER + "` is the only TOML entry point "
                        + "(specification §5). A second module with a parser is a second "
                        + "loader waiting to be written -- with its own idea of whether an "
                        + "unknown key is a typo or a feature. Consumers get the loader through "
                        + "`config`, which declares tomlj as `implementation` precisely so no "
                        + "parser reaches their compile classpath.");
    }

    /**
     * An artifact the specification pins, and the configurations it may not use.
     *
     * <p>Per artifact, because the two rules §16 states are different rules.
     * Plan is "`provided` scope only ... never bundles Plan code", so anything
     * that puts it on a shipped classpath is wrong — {@code runtimeOnly}
     * included. MariaDB Connector/J is "never shaded ... ships as a separate jar
     * in lib/, replaceable by the operator", and {@code runtimeOnly} is exactly
     * how that is declared: {@code gradle/libs.versions.toml} says so in as many
     * words.
     *
     * <p>The first version of this guard used one shared list containing
     * {@code runtimeOnly}, which would have failed the build on the correct
     * declaration of Connector/J — a narrowing whose stated reason did not cover
     * what it narrowed. It did not fail only because the matcher could not see
     * catalog aliases, so two errors cancelled out and the guard could not
     * produce a violation on the real tree at all.
     */
    private record Pinned(String reason, List<String> forbiddenConfigurations) {}

    private static final Map<String, Pinned> NON_BUNDLING_ARTIFACTS = Map.of(
            "org.mariadb.jdbc:mariadb-java-client",
            new Pinned(
                    "LGPL-2.1; §16 says never shaded -- it ships as a separate jar in lib/, "
                            + "replaceable by the operator, which is what runtimeOnly expresses",
                    List.of("implementation", "api", "compileOnlyApi")),
            "com.github.plan-player-analytics:Plan",
            new Pinned(
                    "LGPL-3.0; §16 pins Plan to `provided` scope only, so the extension never "
                            + "bundles Plan code -- the host supplies it at runtime",
                    List.of("implementation", "api", "runtimeOnly", "compileOnlyApi")));

    /**
     * Version-catalog aliases that resolve to a pinned artifact.
     *
     * <p>Without this the guard reads {@code runtimeOnly(libs.mariadb.jdbc)} and
     * sees no coordinate at all. Every pinned artifact in this repository is in
     * fact declared through the catalog, so a literal-only matcher is
     * structurally incapable of finding a violation — which is what it was doing
     * before a full-battery review pointed it out.
     */
    private static Map<String, String> catalogAliases() {
        Path catalog = SourceTree.repoRoot().resolve("gradle/libs.versions.toml");
        Map<String, String> aliases = new LinkedHashMap<>();
        if (!Files.isRegularFile(catalog)) {
            return aliases;
        }
        for (String line : SourceTree.read(catalog).split("\n", -1)) {
            String code = line.contains("#") ? line.substring(0, line.indexOf('#')) : line;
            int eq = code.indexOf('=');
            if (eq < 0 || !code.contains("module")) {
                continue;
            }
            String alias = code.substring(0, eq).strip();
            for (String coordinate : NON_BUNDLING_ARTIFACTS.keySet()) {
                if (code.contains("\"" + coordinate + "\"")) {
                    // `mariadb-jdbc` in the catalog is `libs.mariadb.jdbc` in a
                    // build file: Gradle turns each dash into a dot.
                    aliases.put("libs." + alias.replace('-', '.'), coordinate);
                }
            }
        }
        return aliases;
    }

    @Test
    @DisplayName("the catalog aliases for the pinned artifacts are actually found")
    void catalogAliasesResolve() {
        Map<String, String> aliases = catalogAliases();

        // Without this the guard below can pass by resolving nothing, which is
        // precisely how it passed before: every pinned artifact is declared
        // through the catalog, so an empty alias map makes a violation
        // unreachable while the build stays green.
        assertTrue(
                aliases.containsValue("org.mariadb.jdbc:mariadb-java-client"),
                () -> "the MariaDB connector is declared in gradle/libs.versions.toml and must "
                        + "resolve to an alias, or the scan below cannot see it: " + aliases);
    }

    @Test
    @DisplayName("no copyleft artifact is declared in a configuration that bundles it")
    void copyleftStaysUnbundled() {
        List<String> violations =
                scanForBundledCopyleft(SourceTree.repoRoot(), javaModules());

        assertTrue(
                violations.isEmpty(),
                () -> "an LGPL artifact is only compliant here because an operator can replace "
                        + "it, and that is a packaging decision made by the configuration it is "
                        + "declared in (specification §16).\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("GUARD FIRES: a module bundling a copyleft artifact is rejected")
    void copyleftFixtureIsRejected() {
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");

        List<String> violations =
                scanForBundledCopyleft(fixtures, List.of("copyleft-bundling-violation"));

        // The specific forms are asserted BEFORE the count, deliberately.
        //
        // With the count first these three could never fail: the fixture has
        // exactly three dependency lines, each can yield at most one violation,
        // and only one of them can produce each form -- so `size == 3` already
        // forces all three to be true, and assertEquals throws before they run.
        // Three assertions that read as independent checks on the alias path and
        // were none. Ordered this way each one fails on its own, naming which
        // form the scan stopped seeing.
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("libs.mariadb.jdbc ->")),
                () -> "the catalog-alias form was not caught. Every pinned artifact in the real "
                        + "tree is declared that way, so this is the form that matters most: "
                        + violations);
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("as `implementation`")),
                () -> "expected a bundling scope to be named: " + violations);
        assertTrue(
                violations.stream().anyMatch(
                        v -> v.contains("Plan") && v.contains("as `runtimeOnly`")),
                () -> "runtimeOnly is forbidden for Plan and correct for the connector; the "
                        + "per-artifact rule is what distinguishes them: " + violations);
        assertEquals(
                3,
                violations.size(),
                () -> "a completeness backstop over the three checks above: the fixture declares "
                        + "Plan as `implementation`, Plan as `runtimeOnly` and the connector as "
                        + "`implementation` through a catalog alias: " + violations);
    }

    @Test
    @DisplayName("the scopes each artifact is SUPPOSED to use are not flagged")
    void permittedScopesAreNotFlagged() {
        // Without this the guard could pass by rejecting every declaration of
        // these artifacts, which would read as coverage while making the correct
        // usage impossible. It also pins the difference between the two rules:
        // runtimeOnly is correct for Connector/J and wrong for Plan.
        Path fixtures = SourceTree.repoRoot().resolve("guards/src/test/resources/fixtures");

        // The scan skips a module with no build file, so a missing fixture makes
        // this pass having examined nothing. Its sibling is protected by an
        // expected-count assertion; this one needs the file asserted directly.
        assertTrue(
                Files.isRegularFile(
                        fixtures.resolve("copyleft-permitted-scopes/build.gradle.kts")),
                "the must-pass fixture is missing, so this check would pass having scanned "
                        + "nothing at all");
        assertEquals(
                List.of(),
                scanForBundledCopyleft(fixtures, List.of("copyleft-permitted-scopes")),
                "compileOnly and the test configurations do not reach a published artifact, and "
                        + "runtimeOnly is how §16 says Connector/J ships");
    }

    /**
     * Scans build files for pinned artifacts declared in a forbidden scope.
     *
     * <p>Matches both the literal coordinate and the version-catalog accessor,
     * because this repository uses the latter everywhere.
     */
    private static List<String> scanForBundledCopyleft(Path root, List<String> modules) {
        Map<String, String> aliases = catalogAliases();
        List<String> violations = new ArrayList<>();

        for (String module : modules) {
            Path buildFile = root.resolve(module).resolve("build.gradle.kts");
            if (!Files.isRegularFile(buildFile)) {
                continue;
            }
            String[] lines = SourceTree.read(buildFile).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                // Comments stripped before matching. The fixtures name these
                // artifacts in their own explanatory prose, and a guard that
                // matches its own explanation reports a violation that is only a
                // sentence about the violation.
                String code = lines[i].contains("//")
                        ? lines[i].substring(0, lines[i].indexOf("//"))
                        : lines[i];
                String trimmed = code.strip();

                for (Map.Entry<String, Pinned> pinned : NON_BUNDLING_ARTIFACTS.entrySet()) {
                    String coordinate = pinned.getKey();
                    boolean named = code.contains(coordinate);
                    String via = coordinate;
                    if (!named) {
                        for (Map.Entry<String, String> alias : aliases.entrySet()) {
                            if (alias.getValue().equals(coordinate)
                                    && code.contains(alias.getKey())) {
                                named = true;
                                via = alias.getKey() + " -> " + coordinate;
                                break;
                            }
                        }
                    }
                    if (!named) {
                        continue;
                    }
                    for (String configuration : pinned.getValue().forbiddenConfigurations()) {
                        // Anchored to the call so `testImplementation(` is not
                        // read as `implementation(`.
                        if (trimmed.startsWith(configuration + "(")) {
                            violations.add("%s/build.gradle.kts:%d declares %s as `%s` -- %s"
                                    .formatted(module, i + 1, via, configuration,
                                            pinned.getValue().reason()));
                        }
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Scans build files for declared YAML parsers.
     *
     * <p>Parameterised so the fixture and the real tree drive identical code.
     */
    private static List<String> scanForYaml(Path root, List<String> modules) {
        List<String> violations = new ArrayList<>();
        for (String module : modules) {
            Path buildFile = root.resolve(module).resolve("build.gradle.kts");
            if (!Files.isRegularFile(buildFile)) {
                continue;
            }
            String[] lines = SourceTree.read(buildFile).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                // A commented-out dependency is not in the graph. Skipping comments
                // keeps the guard honest rather than pedantic -- it exists to stop a
                // parser being USED, not discussed.
                String withoutComment = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
                String lower = withoutComment.toLowerCase(Locale.ROOT);
                for (String parser : YAML_PARSERS) {
                    if (lower.contains(parser)) {
                        violations.add("%s/build.gradle.kts:%d declares %s -> %s"
                                .formatted(module, i + 1, parser, line.strip()));
                    }
                }
            }
        }
        return violations;
    }
}
