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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The platform vocabulary guard.
 *
 * <p>Core is the single authority on the identity graph, and it learns platform
 * kinds at runtime from connector registration. The moment a platform's name
 * appears in {@code core/} or {@code protocol/}, that claim stops being true:
 * a special case for one platform has been compiled in, and the hub-and-spoke
 * architecture has quietly become a mesh with a favourite.
 *
 * <p>This is scanning, not parsing. A name inside a string literal or a comment
 * counts, because a comment naming one platform is evidence the author was
 * thinking about that platform while writing general code — which is the thing
 * the guard exists to catch early.
 */
final class PlatformVocabulary {

    /**
     * The forbidden words.
     *
     * <p>Deliberately a fixed list rather than something derived: derivation
     * would make the guard depend on runtime state, and a guard whose strictness
     * varies with the data it is guarding is not a guard.
     */
    static final List<String> FORBIDDEN = List.of(
            "discord", "flarum", "minecraft", "velocity", "plan",
            "geyser", "floodgate", "luckperms", "mojang", "bukkit",
            "paper", "spigot", "bedrock", "java edition");

    /** Modules whose source must never name a platform. */
    static final List<String> GUARDED_MODULES = List.of("core", "protocol");

    private PlatformVocabulary() {
        throw new AssertionError("no instances");
    }

    /** One violation: a file, a line, and the word that should not be there. */
    record Violation(String file, int line, String word, String text) {
        @Override
        public String toString() {
            return "%s:%d names '%s' -> %s".formatted(file, line, word, text.strip());
        }
    }

    /**
     * The allowlist, read from {@code guards/platform-vocabulary-allowlist.txt}.
     *
     * <p>It starts empty. Every entry is {@code <relative-path>:<word> # reason},
     * and the reason must cover exactly what it narrows — an entry that
     * suppresses a whole file behind a justification for one line is a bug in
     * the justification, per the methodology's §2.
     *
     * <p>An entry without a reason is itself a violation: silence is how a
     * narrowing becomes permanent without anyone deciding it should be.
     */
    static Set<String> allowlist(Path repoRoot) {
        Path f = repoRoot.resolve("guards/platform-vocabulary-allowlist.txt");
        Set<String> out = new LinkedHashSet<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        for (String raw : SourceTree.read(f).lines().toList()) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int hash = line.indexOf('#');
            if (hash < 0 || line.substring(hash + 1).isBlank()) {
                throw new IllegalStateException(
                        "allowlist entry has no stated reason, which is not permitted: " + line);
            }
            out.add(line.substring(0, hash).strip().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** Scan the guarded modules under {@code repoRoot} and return every violation. */
    static List<Violation> scan(Path repoRoot) {
        return scan(repoRoot, GUARDED_MODULES, allowlist(repoRoot));
    }

    /**
     * The scanning engine, parameterised so the must-fail fixture drives exactly
     * this code rather than a copy of it.
     */
    static List<Violation> scan(Path repoRoot, List<String> modules, Set<String> allowlist) {
        List<Violation> violations = new ArrayList<>();
        for (String module : modules) {
            for (Path src : SourceTree.javaSourcesUnder(repoRoot.resolve(module))) {
                String rel = SourceTree.rel(src);
                String[] lines = SourceTree.read(src).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    for (String word : FORBIDDEN) {
                        if (!containsWord(lines[i], word)) {
                            continue;
                        }
                        if (allowlist.contains((rel + ":" + word).toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        violations.add(new Violation(rel, i + 1, word, lines[i]));
                    }
                }
            }
        }
        return violations;
    }

    /**
     * Case-insensitive whole-word match.
     *
     * <p>Word boundaries matter: {@code plan} must not fire on {@code planned}
     * or {@code explanation}, or the guard becomes something people route around
     * rather than obey — and a guard that is routinely suppressed protects
     * nothing.
     */
    private static boolean containsWord(String line, String word) {
        Pattern p = Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(word) + "(?![A-Za-z])");
        Matcher m = p.matcher(line);
        return m.find();
    }
}
