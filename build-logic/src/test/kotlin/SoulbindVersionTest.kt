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

import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.NullAndEmptySource
import org.junit.jupiter.params.provider.ValueSource

/**
 * What the version derivation does with each shape git can hand it.
 *
 * The point of splitting `fromDescribe` out of `describe` is that these cases
 * are reachable at all. A test that could only run `git describe` against this
 * checkout would assert one shape -- whichever this working tree is in today --
 * and silently stop covering the others the moment somebody tagged.
 */
class SoulbindVersionTest {

    @ParameterizedTest
    @CsvSource(
        // on a version tag: the `v` comes off, nothing else moves
        "v0.1.0,             0.1.0",
        "v1.2.3,             1.2.3",
        "v10.20.30,          10.20.30",
        // past a tag: the suffix stays, because it is what says "not a release"
        "v0.1.1-3-gabc1234,  0.1.1-3-gabc1234",
        // a dirty tree, on and off a tag
        "v0.1.1+dirty,       0.1.1+dirty",
        "v0.1.1-3-gabc1234+dirty, 0.1.1-3-gabc1234+dirty",
        // a tag written without the prefix is still a version
        "0.1.0,              0.1.0")
    @DisplayName("a version tag becomes the version, suffix and all")
    fun versionTagsAreAccepted(describe: String, expected: String) {
        assertEquals(expected, SoulbindVersion.fromDescribe(describe))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        // Not versions, however plausible they look. Each of these would name
        // an artifact after something that does not identify a release.
        "nightly",
        "nightly-4-gabc1234",
        "v9-final",
        "release-2026",
        "v0.1",              // two components is not this project's shape
        "v0.1.x",
        "0.1",
        "vnext",
        "  ",
    ])
    @DisplayName("anything that is not release-shaped is refused, not guessed at")
    fun nonVersionsBecomeUnversioned(describe: String) {
        assertEquals(SoulbindVersion.UNVERSIONED, SoulbindVersion.fromDescribe(describe))
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("git having no answer is not the same as git saying 0.1.0")
    fun noAnswerBecomesUnversioned(describe: String?) {
        assertEquals(SoulbindVersion.UNVERSIONED, SoulbindVersion.fromDescribe(describe))
    }

    @Test
    @DisplayName("git's trailing newline is not part of the version")
    fun outputIsTrimmed() {
        // Every real call arrives this way -- `git describe` terminates its
        // line -- so an untrimmed value would make EVERY build unversioned,
        // and none of the parameterised cases above would notice because
        // @CsvSource cannot express a newline.
        assertEquals("0.1.0", SoulbindVersion.fromDescribe("v0.1.0\n"))
        assertEquals("0.1.0", SoulbindVersion.fromDescribe("  v0.1.0  \n"))
    }

    @Test
    @DisplayName("exactly one leading v comes off, and the shape check does the rest")
    fun onlyOneVPrefixIsRemoved() {
        // `vv0.1.0` is the case that pins "one, not all": stripping repeatedly
        // would yield 0.1.0 and accept a tag nobody meant.
        assertEquals(SoulbindVersion.UNVERSIONED, SoulbindVersion.fromDescribe("vv0.1.0"))
        assertEquals("0.1.0", SoulbindVersion.fromDescribe("v0.1.0"))

        // And a v-initial word is refused for its shape, which is the whole
        // reason the strip needs no cleverness of its own.
        assertEquals(SoulbindVersion.UNVERSIONED, SoulbindVersion.fromDescribe("velocity-1.0"))
    }

    @Test
    @DisplayName("the fallback cannot be mistaken for a release")
    fun theFallbackIsObviouslyNotARelease() {
        // If this ever becomes something plausible, an artifact built without
        // git would claim a release's name. Asserting the string keeps that a
        // deliberate edit rather than a passing thought.
        assertEquals("0.0.0-unversioned", SoulbindVersion.UNVERSIONED)
    }

    @Test
    @DisplayName("a directory that is not a repository yields no answer, and does not throw")
    fun describeOutsideARepositoryIsNull() {
        // The source-archive case: somebody downloaded a tarball, there is no
        // .git, and the build must still configure. A thrown exception here
        // would be a build that cannot run at all rather than one that admits
        // it does not know its own version.
        val outside = Files.createTempDirectory("soulbind-not-a-repo").toFile()
        try {
            assertEquals(SoulbindVersion.UNVERSIONED, SoulbindVersion.of(outside))
        } finally {
            outside.delete()
        }
    }

    @Test
    @DisplayName("the command asks git the right question")
    fun theCommandIsRight() {
        // The half of the plumbing that can be checked ANYWHERE, which matters
        // because the reaper guest builds in a JDK image with no git binary.
        // Without this, every remaining assertion in this class would still
        // pass if describe() had been wired to the wrong subcommand.
        val argv = SoulbindVersion.describeCommand()

        assertEquals("git", argv[0])
        assertEquals("describe", argv[1])
        assertTrue(argv.contains("--tags"),
            "lightweight tags are what `git tag v0.1.0` makes; without --tags they do"
                + " not count and a release tag would be invisible: $argv")
        assertEquals("v[0-9]*", argv[argv.indexOf("--match") + 1],
            "without this match a `nightly` tag elsewhere in the history could name an"
                + " artifact: $argv")
        assertTrue(argv.any { it.startsWith("--dirty") },
            "a jar built from an edited tree must not be able to claim a release's"
                + " name: $argv")
    }

    @Test
    @DisplayName("with git it answers, without git it says so -- and never throws")
    fun describeMatchesWhatTheEnvironmentCanDo() {
        // NOT a skip. Both branches assert something the build depends on, and
        // the second is the one the reaper guest exercises: a JDK image with no
        // git must still CONFIGURE, reporting that it does not know its version
        // rather than failing to build at all.
        val root = repoRoot()

        if (gitIsOnPath()) {
            assertNotNull(SoulbindVersion.describe(root),
                "git is on PATH and this checkout has a v* tag, so describe answering"
                    + " nothing means the plumbing is broken.")
            assertTrue(SoulbindVersion.of(root) != SoulbindVersion.UNVERSIONED,
                "git answered but the derivation refused it, so this checkout builds"
                    + " artifacts named " + SoulbindVersion.UNVERSIONED + ".")
        } else {
            assertNull(SoulbindVersion.describe(root),
                "there is no git on PATH, so describe cannot have got an answer from it.")
            assertEquals(SoulbindVersion.UNVERSIONED, SoulbindVersion.of(root))
        }
    }

    /**
     * Whether a git binary exists, decided without running one.
     *
     * Deliberately not `try { run git --version }`: that is the same call
     * [SoulbindVersion.describe] makes, so a test using it to decide what to
     * expect would be asking the code under test what its own answer should be.
     */
    private fun gitIsOnPath(): Boolean =
        (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .any { File(it, "git").canExecute() }

    /**
     * The working tree's root, found rather than assumed.
     *
     * The test runs with build-logic as its working directory today, but that
     * is a Gradle detail rather than a promise, and a hard `parentFile` would
     * turn a change in it into a confusing failure in a version test.
     */
    private fun repoRoot(): File {
        var candidate: File? = File(System.getProperty("user.dir")).absoluteFile
        while (candidate != null) {
            if (File(candidate, ".git").exists()) return candidate
            candidate = candidate.parentFile
        }
        throw AssertionError("no .git above " + System.getProperty("user.dir"))
    }
}
