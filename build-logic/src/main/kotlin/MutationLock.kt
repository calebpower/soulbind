import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * Serialises PIT runs across modules.
 *
 * `org.gradle.parallel=true`, eight cores, and each `mutationTest` asks PIT for
 * `cores / 2` threads — so `./gradlew mutationRatchet` over eight modules
 * launched several PIT runs at once, each forking minion JVMs, and the coverage
 * minion died with `UNKNOWN_ERROR`. Every module passes on its own; the failure
 * only appears when the whole tree is asked for at once, which is precisely how
 * the ratchet is meant to be run.
 *
 * `maxParallelUsages = 1`, rather than dropping PIT's own thread count: the
 * threads inside one run are what make a run bearable, and starving them to buy
 * cross-module parallelism trades the fast case for the slow one. Mutation runs
 * are minutes either way and this is the manifest's slowest tier by design.
 */
abstract class MutationLock : BuildService<BuildServiceParameters.None>
