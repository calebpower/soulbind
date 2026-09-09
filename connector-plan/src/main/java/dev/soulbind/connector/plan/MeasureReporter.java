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

package dev.soulbind.connector.plan;

import dev.soulbind.connector.plan.playtime.PlaytimeSource;
import dev.soulbind.sdk.SoulbindClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Tells core how much each player has played over the trailing window.
 *
 * <p>Core holds one observation per account and re-decides the moment it is
 * told, so this is the whole mechanism by which a trailing window advances:
 * nothing waits on a clock but this.
 *
 * <p><b>The population is the interesting part.</b> Each cycle it is
 *
 * <pre>
 *   everyone with a session in the window  ∪  everyone last reported non-zero
 * </pre>
 *
 * <p>The second half is what makes revocation work without core sweeping
 * anything, and it <b>drains</b>: once somebody is reported at zero they are
 * dropped, because zero is below every threshold and repeating it says nothing.
 * So the set is bounded by "played recently, or recently stopped" rather than by
 * the size of the roster.
 *
 * <p>Losing the drain set on restart costs one delayed revocation and no more:
 * the window query alone still finds everybody who has played, and anybody who
 * has not is already below every threshold the moment their measurement goes
 * stale.
 *
 * <p><b>Nothing is ever reported as zero because it could not be read.</b> Both
 * halves of {@link PlaytimeSource} answer with {@link Optional}, and an empty
 * answer means this reporter says nothing at all about that player. The failure
 * being avoided is not a missing grant but a mass revocation — every holder
 * dropping to zero on the sweep after somebody renames a column.
 */
public final class MeasureReporter {

    private final SoulbindClient client;
    private final PlaytimeSource source;
    private final String platformKind;
    private final String measureName;
    private final Duration window;
    private final Clock clock;
    private final BiConsumer<String, Throwable> log;

    /** Who was last told to core as having played something. */
    private final Set<UUID> lastReportedNonZero = new LinkedHashSet<>();

    /** Whether the last sweep failed, so an outage costs one line rather than one per cycle. */
    private boolean sweepFailing;

    public MeasureReporter(
            SoulbindClient client,
            PlaytimeSource source,
            String platformKind,
            String measureName,
            Duration window,
            Clock clock,
            BiConsumer<String, Throwable> log) {
        this.client = client;
        this.source = source;
        this.platformKind = platformKind;
        this.measureName = measureName;
        this.window = window;
        this.clock = clock;
        this.log = log;
    }

    /** What one sweep did. */
    public record Swept(int considered, int reported, boolean ran) {}

    /**
     * One sweep that cannot escape, whatever it hits.
     *
     * <p>Catches {@link Throwable} rather than {@code RuntimeException}: an
     * {@link Error} out of a scheduled task cancels it for the life of the
     * process with nothing logged, and the symptom is a role that silently stops
     * being maintained.
     */
    public void sweepQuietly() {
        try {
            sweep();
        } catch (Throwable t) {
            log.accept("playtime sweep failed; it will be retried", t);
        }
    }

    public Swept sweep() {
        Instant since = clock.instant().minus(window);

        Optional<Set<UUID>> active = source.playersActiveSince(since);
        if (active.isEmpty()) {
            // Unaskable. NOT an empty population: reporting zero for everybody
            // held over from last time would strip the role from all of them on
            // the strength of a query that failed.
            if (!sweepFailing) {
                sweepFailing = true;
                log.accept("cannot read recent activity; no measure will be reported until this "
                        + "recovers, and nothing already granted will be taken away. "
                        + "Still retrying.", null);
            }
            return new Swept(0, 0, false);
        }
        if (sweepFailing) {
            sweepFailing = false;
            log.accept("activity readable again; resuming measure reporting", null);
        }

        Set<UUID> population = new LinkedHashSet<>(active.get());
        population.addAll(lastReportedNonZero);

        int reported = 0;
        for (UUID player : population) {
            Optional<Duration> measured = source.activeSince(player, since);
            if (measured.isEmpty()) {
                // Say nothing about this player this cycle. Their previous
                // observation stands and goes stale on its own schedule, which
                // is a rule's business rather than this one's.
                continue;
            }

            long seconds = measured.get().toSeconds();
            if (!report(player, seconds)) {
                continue;
            }
            reported++;

            if (seconds > 0) {
                lastReportedNonZero.add(player);
            } else {
                // Drained. Zero is below every threshold, so repeating it next
                // cycle would say nothing and keep the set growing forever.
                lastReportedNonZero.remove(player);
            }
        }
        return new Swept(population.size(), reported, true);
    }

    private boolean report(UUID player, long seconds) {
        SoulbindClient.Outcome outcome = client.call("measure.report",
                new ReportBody(platformKind, player.toString(), measureName, seconds,
                        window.toSeconds()));
        if (outcome instanceof SoulbindClient.Outcome.Ok) {
            return true;
        }
        // Per player rather than per sweep: one refusal is one account core
        // would not accept, and abandoning the rest would let a single bad row
        // stop everybody else's role from moving.
        log.accept("core refused a measure for " + player + "; other players are unaffected",
                null);
        return false;
    }

    private record ReportBody(
            String platformKind, String platformId, String name, long value, long windowSeconds) {}
}
