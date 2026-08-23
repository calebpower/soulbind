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
import java.util.concurrent.TimeUnit

/**
 * THE version, derived from the git tag rather than written down.
 *
 * WHY DERIVED. It used to be a literal here, and `release.yml` refused to
 * publish when it and the pushed tag disagreed. That guard worked, but it made
 * every release two acts -- bump the literal, then tag -- with a failed release
 * run as the punishment for doing them in the wrong order. Deriving removes the
 * disagreement rather than detecting it: there is no second number to be wrong.
 *
 * WHY `git describe` AND NOT `GITHUB_REF_NAME`. An environment variable exists
 * only on the runner, so `git checkout v0.1.1 && ./gradlew build` would produce
 * an artifact named differently from the one on the release page. This
 * repository re-runs the full build on the tag precisely because "it built on
 * somebody's laptop" is not a claim it makes anywhere -- handing the version to
 * something a laptop cannot see would give that claim up for a saved commit.
 * `git describe` answers identically in both places, from the checkout alone.
 *
 * WHAT THE SHAPES MEAN:
 *
 *   on a version tag      v0.1.1              -> 0.1.1
 *   past one              v0.1.1-3-gabc1234   -> 0.1.1-3-gabc1234
 *   with local edits      ...+dirty           -> ...+dirty
 *   no version tag, or no git at all          -> 0.0.0-unversioned
 *
 * The middle two are deliberately not release-shaped. A jar built from an
 * uncommitted tree should not be able to claim a release's name, and the suffix
 * is what makes an artifact found later on a disk self-describing.
 */
object SoulbindVersion {

    /**
     * What a tree with no version tag in its history is called.
     *
     * NOT a plausible-looking fallback like "0.1.0". A wrong number that looks
     * right outlives the build that produced it; this one cannot be mistaken
     * for a release by anybody reading a filename. A source archive with no
     * `.git` lands here, which is correct -- it genuinely does not know.
     */
    const val UNVERSIONED = "0.0.0-unversioned"

    /**
     * How long to wait on git before giving up and saying so.
     *
     * A build must not hang because a repository is in a state git wants to
     * think about. Timing out yields UNVERSIONED, which fails the release
     * check loudly rather than stalling a run nobody is watching.
     */
    private const val TIMEOUT_SECONDS = 20L

    /**
     * What a derived version has to look like to be believed.
     *
     * Anchored, and the leading component is three numbers. `git describe`
     * against a repository whose tags are not versions will happily return
     * something like `nightly-4-gabc1234`, and a build that accepted it would
     * name artifacts after a tag that means nothing. Refusing is not a
     * hypothetical: `--match` below narrows what git considers, and this
     * narrows what we accept from it. Neither alone covers a tag named
     * `v9-final`.
     */
    private val RELEASE_SHAPED = Regex("""^\d+\.\d+\.\d+(?:[-+].+)?$""")

    /**
     * The decision, separated from the subprocess that feeds it.
     *
     * This is the half worth asserting and the half that has edge cases, so it
     * takes a string and returns a string -- `SoulbindVersionTest` drives every
     * shape above through it without a git repository in any particular state.
     * A rule whose only witness is "the build happened to name the file right
     * that day" has no witness.
     *
     * @param describe raw `git describe` output, or null if git could not answer
     */
    fun fromDescribe(describe: String?): String {
        val raw = describe?.trim().orEmpty()
        if (raw.isEmpty()) return UNVERSIONED

        // One leading `v`, unconditionally.
        //
        // The obvious guard -- strip only ahead of a digit, so that a tag named
        // `velocity-1.0` does not become `elocity-1.0` -- was written first and
        // then removed, because no input can tell the two versions apart. For
        // the guard to matter, RELEASE_SHAPED would have to accept a string the
        // unconditional strip produced and the conditional one did not; it only
        // accepts strings beginning with a digit, which is exactly the case
        // where both behave identically. A branch no test can reach is a branch
        // with no witness, and it read as covered.
        //
        // The shape check below is the arbiter. `velocity-1.0` is refused for
        // being the wrong shape rather than for surviving a careful strip.
        val stripped = raw.removePrefix("v")

        return if (RELEASE_SHAPED.matches(stripped)) stripped else UNVERSIONED
    }

    /**
     * The question put to git, separated so it can be asserted without asking it.
     *
     * Not every environment that builds this has git: the reaper guest builds
     * inside a digest-pinned JDK image with no `git` binary in it, which is
     * correct for what that image is for. A test that could only check this
     * plumbing by running it would be a test that fails there, so the argv is
     * split out as the half that can be checked anywhere.
     *
     * `--match v[0-9]*` so only version tags are candidates: a `nightly` or
     * `last-known-good` tag elsewhere in the history must not become the name
     * of an artifact. `--tags` so lightweight tags count, because that is what
     * `git tag v0.1.0` makes by default.
     */
    fun describeCommand(): List<String> = listOf(
        "git", "describe", "--tags", "--match", "v[0-9]*",
        "--abbrev=7", "--dirty=+dirty")

    /**
     * Ask git, and return null rather than throwing if it cannot answer.
     *
     * Every failure -- no git binary, no `.git`, no matching tag, a timeout --
     * is the same answer here: null, which [fromDescribe] turns into
     * UNVERSIONED. Distinguishing them would only offer the build more ways to
     * guess, and the environments that cannot answer genuinely cannot.
     */
    fun describe(repoRoot: File): String? =
        try {
            val process = ProcessBuilder(describeCommand())
                .directory(repoRoot)
                .redirectErrorStream(false)
                .start()

            // Drain before waiting. A process whose output nobody reads can
            // fill its pipe and block forever, and the timeout below would then
            // be measuring our own deadlock.
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.errorStream.use { it.readAllBytes() }

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0) {
                null
            } else {
                out
            }
        } catch (_: Exception) {
            null
        }

    /** The version of the tree rooted at [repoRoot]. */
    fun of(repoRoot: File): String = fromDescribe(describe(repoRoot))
}
