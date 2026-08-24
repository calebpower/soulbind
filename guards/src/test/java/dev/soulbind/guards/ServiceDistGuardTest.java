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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What must be true of a service distribution, read from the built tree.
 *
 * <p>Core and connector-discord ship as {@code bin/} plus {@code lib/} rather
 * than as fat jars — a departure from §14 recorded in the README table. The
 * argument for it is that §16's rule against bundling a copyleft artifact then
 * holds <em>by construction</em> rather than by an exclusion list somebody
 * maintains.
 *
 * <p>"By construction" is a claim, and these are what check it. The relink
 * requirement is satisfied in practice by an operator being able to drop a
 * replacement jar into {@code lib/} and restart — which is only true if the
 * artifact is genuinely its own file on an explicit classpath, and that is a
 * property of the built distribution, not of the build script.
 */
class ServiceDistGuardTest {

    private static Path distributionOf(String module) {
        return SourceTree.repoRoot()
                .resolve(module).resolve("build/install").resolve(module);
    }

    private static List<String> jarsIn(String module) throws IOException {
        Path lib = distributionOf(module).resolve("lib");
        assertTrue(Files.isDirectory(lib),
                module + " has no build/install/" + module + "/lib. The guards' test task"
                        + " is supposed to depend on its installDist, so this means that"
                        + " wiring is gone and the assertions below would pass vacuously.");
        try (Stream<Path> files = Files.list(lib)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * The artifacts this module's own inventory says ship unbundled.
     *
     * <p>Read from the generated {@code THIRD-PARTY.txt} rather than listed
     * here. The first version guessed jar names from the artifact id and got
     * trove4j wrong -- its Maven artifact is literally named {@code core}, so
     * the file is {@code core-3.1.0.jar}. Guessing was the mistake, not the
     * name: deriving it ties the claim in the legal document directly to the
     * bytes on disk, which is the property worth asserting anyway.
     *
     * @return coordinate {@code group:name} to expected jar file name
     */
    private static Map<String, String> unbundledPerInventory(String module)
            throws IOException {
        Path inventory = distributionOf(module).resolve("THIRD-PARTY.txt");
        assertTrue(Files.isRegularFile(inventory), "no THIRD-PARTY.txt in " + module);

        Map<String, String> versionOf = new LinkedHashMap<>();
        Map<String, String> unbundled = new LinkedHashMap<>();
        boolean inNeverBundled = false;

        for (String raw : Files.readAllLines(inventory)) {
            String line = raw.strip();
            if (line.startsWith("Never shaded or bundled")) {
                inNeverBundled = true;
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(":");
            if (!inNeverBundled && parts.length == 3) {
                // "group:name:version   Licence"
                String version = parts[2].split("\\s+")[0];
                versionOf.put(parts[0] + ":" + parts[1], version);
            } else if (inNeverBundled && parts.length == 2) {
                String coordinate = parts[0] + ":" + parts[1];
                String version = versionOf.get(coordinate);
                if (version != null) {
                    unbundled.put(coordinate, parts[1] + "-" + version + ".jar");
                }
            }
        }
        return unbundled;
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "connector-discord"})
    @DisplayName("every artifact the inventory calls unbundled is its own jar in lib/")
    void copyleftShipsUnbundled(String module) throws IOException {
        List<String> jars = jarsIn(module);
        Map<String, String> unbundled = unbundledPerInventory(module);

        // Both services carry copyleft dependencies today. A run finding none
        // would pass every assertion below without checking anything, and the
        // honest reasons for it are "the graph changed" -- good news, worth a
        // deliberate edit -- or "the inventory stopped being parsed", which is
        // not.
        assertTrue(!unbundled.isEmpty(),
                module + "'s inventory lists nothing as shipping unbundled. Either the"
                        + " graph genuinely lost its copyleft dependencies, or this guard"
                        + " is no longer reading THIRD-PARTY.txt correctly and is about"
                        + " to assert nothing.");

        List<String> missing = new ArrayList<>();
        unbundled.forEach((coordinate, jar) -> {
            if (!jars.contains(jar)) {
                missing.add(coordinate + " (expected " + jar + ")");
            }
        });

        assertTrue(missing.isEmpty(),
                module + "'s inventory says these ship unmodified and replaceable in"
                        + " lib/, and they are not there: " + missing + ". §16's relink"
                        + " requirement is satisfied in practice by an operator dropping"
                        + " a replacement jar in and restarting, which needs the artifact"
                        + " to actually be its own file. lib/ holds: " + jars);
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "connector-discord"})
    @DisplayName("the start script names each jar, so replacing one takes effect")
    void theClasspathIsExplicit(String module) throws IOException {
        // A wildcard classpath would also work, but the start script the
        // application plugin generates enumerates jars -- so a replacement jar
        // with a DIFFERENT VERSION IN ITS NAME would silently not be on the
        // classpath at all. An operator exercising their relink right needs to
        // know that, and docs/install.md says it. This asserts the shape the
        // instruction depends on.
        Path script = distributionOf(module).resolve("bin").resolve(module);
        assertTrue(Files.isRegularFile(script), "no start script at " + script);

        String text = Files.readString(script);
        assertTrue(text.contains("CLASSPATH="),
                module + "'s start script sets no CLASSPATH");
        assertTrue(text.contains("$APP_HOME/lib/"),
                module + "'s start script does not build its classpath from lib/,"
                        + " so the unbundled-and-replaceable story does not hold");
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "connector-discord"})
    @DisplayName("the licence files and the unit file ship with the distribution")
    void packagingShips(String module) throws IOException {
        Path dist = distributionOf(module);
        for (String required : List.of("LICENSE", "NOTICE", "THIRD-PARTY.txt")) {
            assertTrue(Files.isRegularFile(dist.resolve(required)),
                    module + "'s distribution does not contain " + required
                            + ". §16: they ship in every distributed artifact.");
        }

        Path packaging = dist.resolve("packaging");
        assertTrue(Files.isDirectory(packaging),
                module + "'s distribution has no packaging/ directory");

        try (Stream<Path> files = Files.list(packaging)) {
            List<String> names = files.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(n -> n.endsWith(".service")),
                    module + " ships no systemd unit. An operator installing from a"
                            + " tarball has the tarball; sending them to find a service"
                            + " file in a source tree is how a hardened unit becomes"
                            + " `nohup java -jar &`. Found: " + names);
            assertTrue(names.stream().anyMatch(n -> n.endsWith(".toml.sample")),
                    module + " ships no sample configuration. Found: " + names);

            // Scoped to this module, because the first version shipped every
            // module's packaging to every distribution and core's tarball
            // arrived carrying the Discord connector's sample config.
            String foreign = module.equals("core") ? "discord" : "soulbind.toml";
            assertTrue(names.stream().noneMatch(n -> n.contains(foreign)),
                    module + " ships another module's packaging (" + foreign + "): "
                            + names);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"core", "connector-discord"})
    @DisplayName("a normal stop is not reported as a failure")
    void theUnitCountsSigtermAsSuccess(String module) throws IOException {
        // A JVM killed by SIGTERM exits 143, and systemd's default success set
        // does not include it -- so `systemctl stop` booked a clean shutdown as
        // "Failed with result 'exit-code'". Found on the first live deployment,
        // upgrading v0.1.1 to v0.1.2: the unit reported failed after a stop that
        // did exactly what it was asked. Every upgrade and every reboot would
        // have left that behind.
        //
        // Asserted on the SHIPPED unit rather than the one in packaging/,
        // because the operator installs from the tarball -- the same reason the
        // test above checks the distribution rather than the source tree.
        Path unit = unitFileIn(distributionOf(module).resolve("packaging"), module);
        String text = Files.readString(unit);

        assertTrue(text.lines().anyMatch(l -> l.trim().equals("SuccessExitStatus=143")),
                unit.getFileName() + " does not declare SuccessExitStatus=143. Without it a"
                        + " normal stop reports `failed`, and a status line that cries wolf"
                        + " is how a real failure goes unnoticed.");

        // The restart policy is what makes the above matter rather than merely
        // read badly: with 143 outside the success set and Restart=on-failure,
        // systemd's own view of "did this exit cleanly" is wrong.
        assertTrue(text.lines().anyMatch(l -> l.trim().startsWith("Restart=")),
                unit.getFileName() + " declares no Restart policy, so this assertion is"
                        + " checking a property nothing depends on any more -- read both"
                        + " lines before deleting either.");
    }

    private static Path unitFileIn(Path packaging, String module) throws IOException {
        try (Stream<Path> files = Files.list(packaging)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".service"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            module + " ships no .service file in " + packaging));
        }
    }
}
