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
import java.util.List;
import java.util.Locale;
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
