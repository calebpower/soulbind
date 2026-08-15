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

package dev.soulbind.core.transport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared hostile-input corpus.
 *
 * <p>One list, read by every tier that needs one. A value that broke the API
 * should reach the other tiers without anyone re-typing it; separate per-tier
 * lists drift, and the drift is invisible until the tier that lacks a value is
 * the one that would have caught the defect.
 */
final class HostileInputs {

    private HostileInputs() {
        throw new AssertionError("no instances");
    }

    /**
     * Loads and unescapes the corpus.
     *
     * <p>Located from the repository root rather than the classpath: the corpus
     * is deliberately not a module resource, because it is shared with tiers
     * that are not Java at all.
     */
    static List<String> load() {
        Path corpus = repoRoot().resolve("corpus/hostile-inputs.txt");
        if (!Files.isRegularFile(corpus)) {
            throw new IllegalStateException(
                    "hostile-input corpus not found at " + corpus
                            + ". The fuzz tier is corpus-driven; running it without the corpus "
                            + "would be a green run that fuzzed nothing.");
        }
        List<String> out = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(corpus, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                out.add(unescape(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + corpus, e);
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("the corpus parsed to zero values");
        }
        return List.copyOf(out);
    }

    /** Walks up for the repository marker, since no system property is set for core's tests. */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no repository root above " + here);
    }

    /** Turns {@code \\uXXXX} into the character it names. */
    private static String unescape(String line) {
        StringBuilder out = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\' && i + 5 < line.length() + 1 && i + 1 < line.length()
                    && line.charAt(i + 1) == 'u' && i + 6 <= line.length()) {
                try {
                    out.append((char) Integer.parseInt(line.substring(i + 2, i + 6), 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                    // Not an escape after all; fall through and keep the backslash.
                }
            }
            out.append(c);
        }
        return out.toString();
    }
}
