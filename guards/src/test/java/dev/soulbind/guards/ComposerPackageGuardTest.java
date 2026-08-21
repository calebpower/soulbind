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
 * The PHP side is distributed too, and by a different route.
 *
 * <p>Composer installs a package by copying its directory. Somebody receiving
 * the Flarum connector that way gets {@code connector-flarum/} and nothing
 * above it — so the repository root's {@code LICENSE} is not something they
 * have, and §16's "in every distributed artifact" is not satisfied by it being
 * one directory up.
 *
 * <p>The Java modules get their inventory generated. This one asserts the
 * condition that makes an inventory unnecessary: the package's dependency graph
 * is empty by construction. The moment that stops being true, the claim in its
 * {@code NOTICE} stops being accurate, so it is checked rather than trusted.
 */
class ComposerPackageGuardTest {

    private static final Path PACKAGE =
            SourceTree.repoRoot().resolve("connector-flarum");

    @Test
    @DisplayName("the package carries its own LICENSE, byte-identical to the project's")
    void licenceIsPresentAndIdentical() throws IOException {
        Path packaged = PACKAGE.resolve("LICENSE");
        assertTrue(Files.isRegularFile(packaged),
                "connector-flarum has no LICENSE. Composer installs a package by copying"
                        + " its directory, so a recipient never sees the repository root's.");

        // Byte-identical, not merely present. A copy that drifts from the
        // licence it claims to be is worse than a reference, because it looks
        // authoritative.
        assertEquals(
                Files.readString(SourceTree.repoRoot().resolve("LICENSE")),
                Files.readString(packaged),
                "connector-flarum/LICENSE has drifted from the repository's LICENSE");
    }

    @Test
    @DisplayName("the package carries a NOTICE")
    void noticeIsPresent() throws IOException {
        assertTrue(Files.isRegularFile(PACKAGE.resolve("NOTICE")),
                "connector-flarum has no NOTICE. §16: it ships in every distributed"
                        + " artifact, and a composer package is one.");
    }

    @Test
    @DisplayName("the package's runtime requirements stay empty of third-party code")
    void requirementsCarryNoThirdPartyCode() throws IOException {
        // NOTICE says this package bundles nothing and needs nothing but the
        // host. That is true today and is exactly the kind of statement that
        // quietly stops being true -- somebody adds a PHP library, and a legal
        // file that nothing reads goes on saying otherwise. This is the guard
        // NOTICE's own text points at.
        String composer = Files.readString(PACKAGE.resolve("composer.json"));

        int start = composer.indexOf("\"require\"");
        assertTrue(start >= 0, "connector-flarum/composer.json has no require block");
        int open = composer.indexOf('{', start);
        int close = composer.indexOf('}', open);
        String block = composer.substring(open, close);

        List<String> unexpected = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(block);
        int found = 0;
        while (m.find()) {
            String requirement = m.group(1);
            found++;
            boolean allowed = requirement.equals("php")
                    || requirement.startsWith("ext-")
                    || requirement.equals("flarum/core");
            if (!allowed) {
                unexpected.add(requirement);
            }
        }

        assertTrue(found > 0, "parsed no requirements, so this guard asserted nothing");
        assertTrue(unexpected.isEmpty(),
                "connector-flarum now requires third-party PHP code: " + unexpected
                        + ". Its NOTICE says the package bundles none and that its"
                        + " dependency graph is empty by construction, which is why it has"
                        + " no generated inventory. Either drop the dependency, or give"
                        + " this package a real third-party inventory and update NOTICE.");
    }

    @Test
    @DisplayName("tests and vendored code are excluded from the distributed archive")
    void archiveExcludesWhatShouldNotShip() throws IOException {
        String composer = Files.readString(PACKAGE.resolve("composer.json"));
        assertTrue(composer.contains("\"archive\""),
                "connector-flarum/composer.json declares no archive exclusions, so a"
                        + " distributed package would carry its test suite, its fixtures"
                        + " and whatever is in vendor/");
        for (String excluded : List.of("/tests", "/vendor", "/build")) {
            assertTrue(composer.contains("\"" + excluded + "\""),
                    "connector-flarum's archive exclusions do not cover " + excluded);
        }
    }

    @Test
    @DisplayName("the declared licence matches the project's")
    void declaredLicenceMatches() throws IOException {
        String composer = Files.readString(PACKAGE.resolve("composer.json"));
        assertTrue(composer.contains("\"license\": \"Apache-2.0\""),
                "connector-flarum/composer.json does not declare Apache-2.0. Packagist"
                        + " and every downstream tool read that field, not the file.");
    }
}
