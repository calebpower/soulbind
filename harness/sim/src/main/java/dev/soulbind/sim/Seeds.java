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

package dev.soulbind.sim;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The committed seed set, and the rule for growing it.
 *
 * <p>Fixed seeds by default: a tier that draws a fresh seed every run produces a
 * result not comparable to yesterday's, and a failure nobody can reproduce is a
 * failure nobody will fix.
 *
 * <p><b>Promotion is permanent and is a human's job.</b> A seed that has ever
 * found a defect is added to {@code seeds.txt} with a dated note and never
 * removed — least of all once the defect is fixed, which is exactly when it
 * becomes a regression test. The runner prints the line to add rather than
 * adding it: a harness that could edit its own seed file would eventually
 * curate it, and the seeds it dropped would be the inconvenient ones.
 */
public final class Seeds {

    private Seeds() {
        throw new AssertionError("no instances");
    }

    /** One committed seed and the note explaining why it is committed. */
    public record Seed(long value, String why) {}

    /** The committed set, in file order. */
    public static List<Seed> fixed() {
        List<Seed> seeds = new ArrayList<>();
        try (InputStream in = Seeds.class.getResourceAsStream("/seeds.txt")) {
            if (in == null) {
                throw new IllegalStateException(
                        "seeds.txt is not on the classpath. The tier would fall back to"
                                + " whatever seed somebody typed, and no run would be"
                                + " comparable to the last.");
            }
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s+", 2);
                if (parts.length < 2 || parts[1].isBlank()) {
                    throw new IllegalStateException(
                            "seed '" + parts[0] + "' has no note. A seed nobody can explain"
                                    + " is a seed the next person deletes.");
                }
                seeds.add(new Seed(Long.parseLong(parts[0]), parts[1].strip()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (seeds.isEmpty()) {
            throw new IllegalStateException("seeds.txt lists no seeds");
        }
        return List.copyOf(seeds);
    }

    /**
     * The line a human should add when a seed finds something.
     *
     * <p>Printed rather than written. The date is passed in rather than read
     * from a clock, because everything else in this module is reproducible and
     * a function that quietly consults the wall clock is the one thing that
     * would stop being.
     */
    public static String promotionLine(long seed, String isoDate, String whatItFound) {
        return seed + "  found " + whatItFound + " on " + isoDate;
    }
}
