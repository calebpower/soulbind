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

package dev.soulbind.protocol;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the committed golden vector files.
 *
 * <p>Deliberately a hand-written parser over a line-oriented format rather than
 * JSON. The other implementation of this protocol is in PHP, and the fewer
 * dependencies a vector consumer needs, the fewer reasons there are for one
 * side to read the file differently from the other. A tab-separated file with
 * {@code \\uXXXX} escapes can be read by anything.
 */
final class Vectors {

    private Vectors() {
        throw new AssertionError("no instances");
    }

    /** One line of a vector file, already unescaped. */
    record Row(int line, List<String> fields) {

        String field(int index) {
            return fields.get(index);
        }

        boolean isNull(int index) {
            // The literal four characters NULL mean absent. A vector file has
            // no type system, so the sentinel is stated rather than inferred
            // from emptiness -- an empty string is a legitimate value here and
            // conflating the two would silently weaken every empty-input case.
            return "NULL".equals(fields.get(index));
        }
    }

    static List<Row> read(String name, int expectedFields) {
        Path file = repoRoot().resolve("vectors").resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException(
                    "vector file not found: " + file + ". The vectors are the oracle proving "
                            + "two implementations agree; running the suite without them would "
                            + "be a green run that compared nothing.");
        }

        List<Row> rows = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                // -1: trailing empty fields are significant. Splitting with the
                // default limit would drop a final empty expected-value, turning
                // "normalises to nothing" into "has no expectation".
                String[] parts = line.split("\t", -1);
                if (parts.length != expectedFields) {
                    throw new IllegalStateException(
                            file.getFileName() + ":" + (i + 1) + " has " + parts.length
                                    + " fields, expected " + expectedFields);
                }
                List<String> fields = new ArrayList<>(parts.length);
                for (String part : parts) {
                    fields.add(unescape(part));
                }
                rows.add(new Row(i + 1, List.copyOf(fields)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }

        if (rows.isEmpty()) {
            throw new IllegalStateException(file + " parsed to zero rows");
        }
        return rows;
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no repository root above " + here);
    }

    /** {@code \\uXXXX} and {@code \\t} to the characters they name. */
    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char next = s.charAt(i + 1);
            switch (next) {
                case 't' -> {
                    out.append('\t');
                    i++;
                }
                case 'n' -> {
                    out.append('\n');
                    i++;
                }
                case 'r' -> {
                    out.append('\r');
                    i++;
                }
                case '\\' -> {
                    out.append('\\');
                    i++;
                }
                case 'u' -> {
                    if (i + 6 <= s.length()) {
                        out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 5;
                    } else {
                        out.append(c);
                    }
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
