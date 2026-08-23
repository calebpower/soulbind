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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the repository as data.
 *
 * <p>Every guard scans source text through this one helper so that the guard
 * and its must-fail fixture exercise identical code. A fixture checked by a
 * second, parallel implementation would prove only that the second
 * implementation works.
 */
final class SourceTree {

    private static final String SEP = java.io.File.separator;

    private SourceTree() {
        throw new AssertionError("no instances");
    }

    /**
     * The repository root, supplied by the build (see guards/build.gradle.kts).
     *
     * <p>Deliberately not discovered by walking up from the working directory:
     * that makes the guard's behaviour depend on where it was invoked from,
     * which is exactly the kind of environment sensitivity that produces a
     * guard passing for the wrong reason.
     */
    static Path repoRoot() {
        String configured = System.getProperty("soulbind.repoRoot");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "soulbind.repoRoot system property is not set. The guards read the "
                            + "repository as data and cannot infer its location; see "
                            + "guards/build.gradle.kts.");
        }
        Path root = Path.of(configured);
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("soulbind.repoRoot is not a directory: " + root);
        }
        return root;
    }

    /**
     * The version this build produced, supplied by the build.
     *
     * <p>Same contract as {@link #repoRoot()} and for the same reason: a guard
     * that inferred the version -- by globbing {@code build/libs} and taking
     * what it found, say -- would be reading the answer off the thing it is
     * checking, and would silently start inspecting a stale artifact the moment
     * two builds' outputs sat side by side.
     */
    static String version() {
        String configured = System.getProperty("soulbind.version");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "soulbind.version system property is not set. A guard that reads a built "
                            + "artifact has to be told which one; see guards/build.gradle.kts.");
        }
        return configured;
    }

    /** Every {@code .java} file under the given directory, or empty if it does not exist. */
    static List<Path> javaSourcesUnder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> out = new ArrayList<>();
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    // build/ holds generated and copied output, not authored source. A guard
                    // that scanned it would report the same violation twice and would fail
                    // differently depending on whether the tree had been built.
                    .filter(p -> !p.toString().contains(SEP + "build" + SEP))
                    .forEach(out::add);
            out.sort(Path::compareTo);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + dir, e);
        }
    }


    /**
     * Every Gradle module, read from {@code settings.gradle.kts}.
     *
     * <p>Derived rather than hand-listed, and that is the whole point. Each
     * guard used to carry its own copy of the module list, so adding a module
     * meant remembering four places — and forgetting one produced a module
     * quietly outside coverage, with every guard still green. Reading the
     * build's own list means a new module is covered by default and has to be
     * excluded deliberately, with a reason, rather than by omission.
     */
    static List<String> allModules() {
        String settings = read(repoRoot().resolve("settings.gradle.kts"));
        int start = settings.indexOf("include(");
        if (start < 0) {
            throw new IllegalStateException(
                    "settings.gradle.kts has no include(...) block; the guards derive their "
                            + "module coverage from it and cannot proceed without it");
        }
        int end = settings.indexOf(')', start);
        List<String> modules = new ArrayList<>();
        Matcher m = Pattern.compile("\"([a-z0-9-]+)\"")
                .matcher(settings.substring(start, end));
        while (m.find()) {
            modules.add(m.group(1));
        }
        if (modules.isEmpty()) {
            throw new IllegalStateException("parsed no modules from settings.gradle.kts");
        }
        return List.copyOf(modules);
    }

    /**
     * Every module that ships Java the seams apply to.
     *
     * <p>{@code guards} is excluded, and only {@code guards}: it contains no
     * production code, exists to read the repository as data, and necessarily
     * names the very things the guards forbid elsewhere. The exclusion covers
     * exactly that module and nothing else.
     */
    static List<String> productionModules() {
        List<String> out = new ArrayList<>(allModules());
        out.remove("guards");
        return List.copyOf(out);
    }

    /** File contents as UTF-8. */
    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + p, e);
        }
    }

    /** A path rendered relative to the repository root, for readable failure messages. */
    static String rel(Path p) {
        Path root = repoRoot();
        return p.startsWith(root) ? root.relativize(p).toString() : p.toString();
    }
}
