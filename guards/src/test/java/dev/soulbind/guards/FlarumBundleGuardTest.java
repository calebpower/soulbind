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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every frontend bundle {@code extend.php} registers is present and built.
 *
 * <p>Composer cannot run npm. A Flarum extension therefore distributes its
 * compiled bundles or it does not work — and until v0.1.2 this one did not:
 * {@code js/.gitignore} hid {@code js/dist}, so every tag shipped an
 * {@code extend.php} pointing at files no consumer received. Installing it gave
 * {@code InvalidArgumentException: File not found at path: .../js/dist/forum.js}
 * and HTTP 500 on the first frontend request. That was found by installing it on
 * a live forum, because nothing else could have found it.
 *
 * <p>Why nothing else could: {@code harness/flarum/stack.sh} runs {@code npm run
 * build} before every session and then asserts the bundles are non-empty. It was
 * right, and it tested bundles it had just built itself. The distribution shipped
 * different content, and no check compared the two. Two paths to one fact with
 * no shared witness — the same shape as DECISIONS 10.52 and 10.54.
 *
 * <p>This reads the registration and the repository, so it holds for anyone
 * cloning the tree, with no Node and no network.
 */
class FlarumBundleGuardTest {

    private static final String EXTENSION = "connector-flarum";

    /**
     * Bundles named by {@code extend.php}, e.g. {@code __DIR__ . '/js/dist/forum.js'}.
     *
     * <p>Read out of the source rather than hard-coded. A hard-coded list would
     * pass while {@code extend.php} registered a third bundle nobody built,
     * which is precisely the defect this exists for.
     */
    private static final Pattern REGISTERED =
            Pattern.compile("__DIR__\\s*\\.\\s*'(/js/dist/[A-Za-z0-9_.-]+\\.js)'");

    private static List<String> registeredBundles(String extendPhp) {
        List<String> paths = new ArrayList<>();
        Matcher m = REGISTERED.matcher(extendPhp);
        while (m.find()) {
            paths.add(m.group(1));
        }
        return paths;
    }

    @Test
    @DisplayName("every bundle extend.php registers exists in the tree, and is not empty")
    void everyRegisteredBundleIsBuiltAndCommitted() throws IOException {
        Path root = SourceTree.repoRoot().resolve(EXTENSION);
        String extendPhp = Files.readString(root.resolve("extend.php"));

        List<String> registered = registeredBundles(extendPhp);
        assertFalse(registered.isEmpty(),
                "extend.php registers no js/dist bundle at all. Either the frontend was"
                        + " removed or this guard's pattern stopped matching it, and the"
                        + " second would make every assertion below vacuous.");

        for (String relative : registered) {
            Path bundle = root.resolve(relative.substring(1));
            assertTrue(Files.isRegularFile(bundle),
                    "extend.php registers " + relative + " and it is not in the repository."
                            + " Composer cannot run npm, so this file has to be committed:"
                            + " a consumer installing the package gets a Flarum that throws"
                            + " InvalidArgumentException on the first frontend request."
                            + " Build with `npm run build` in " + EXTENSION + "/js.");
            assertTrue(Files.size(bundle) > 0,
                    relative + " is committed but empty. webpack exits 0 with no output when"
                            + " its entry resolves to nothing, which is why size is asserted"
                            + " and not just existence.");
        }
    }

    @Test
    @DisplayName("the bundles are this project's code, not somebody else's build")
    void theBundlesAreOurs() throws IOException {
        // Existence and size would both be satisfied by a stray file. The
        // bundles minify our identifiers away, but the extension's own name
        // survives as a string in the settings keys it registers.
        Path root = SourceTree.repoRoot().resolve(EXTENSION);
        for (String relative : registeredBundles(Files.readString(root.resolve("extend.php")))) {
            Path bundle = root.resolve(relative.substring(1));
            if (!Files.isRegularFile(bundle)) {
                // Absent and empty are the sibling test's cases, and it explains
                // them properly. Reading the file here would throw IOException
                // first and report a missing bundle under this test's name, with
                // a stack trace instead of the sentence that says what to do.
                continue;
            }
            String text = Files.readString(bundle);
            assertTrue(text.contains("soulbind"),
                    relative + " does not mention soulbind anywhere. A bundle that is present,"
                            + " non-empty and not ours satisfies every other assertion here.");
        }
    }

    @Test
    @DisplayName("the harness still rebuilds them, so a stale commit cannot hide a broken build")
    void theHarnessStillBuildsThem() throws IOException {
        // The committed bundles are what ships; the battery builds fresh ones
        // and runs against those. Both matter, and this guard covers the seam
        // between them: if the harness ever stops rebuilding, the committed
        // copies become the only thing tested and could drift from src/
        // indefinitely without anybody noticing.
        String stack = Files.readString(
                SourceTree.repoRoot().resolve("harness/flarum/stack.sh"));
        assertTrue(stack.contains("npm run build"),
                "harness/flarum/stack.sh no longer builds the frontend. The committed bundles"
                        + " would then be the only ones ever exercised, and nothing would"
                        + " catch them going stale against js/src.");
    }
}
