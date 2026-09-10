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

import dev.soulbind.connector.plan.playtime.ActivitySource;
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
 * Tells core how engaged each player is, on the dashboard's own scale.
 *
 * <p>Core holds one observation per account and re-decides the moment it is
 * told, so this is the whole mechanism by which a trailing window advances:
 * nothing waits on a clock but this.
 *
 * <p><b>The population is the interesting part.</b> Each cycle it is
 *
 * <pre>
 *   everyone seen in the window  ∪  everyone last reported above the floor
 * </pre>
 *
 * <p>The second half is what makes revocation work without core sweeping
 * anything, and it <b>drains</b>: once somebody is reported at zero they are
 * dropped, because zero is below every threshold and repeating it says nothing.
 * So the set is bounded by "played recently, or recently stopped" rather than by
 * the size of the roster.
 *
 * <p>Losing the drain set on restart costs one delayed revocation and no more:
 * the roster query alone still finds everybody who has played, and anybody who
 * has not is already below every threshold the moment their measurement goes
 * stale.
 *
 * <p>The WINDOW reported alongside the value is the span the host's index
 * covers — three weeks — and not the sweep interval. A rule matches it exactly,
 * so getting it wrong would refuse everybody rather than admit them, which is
 * the safe direction and still a misconfiguration.
 *
 * <p><b>Nothing is ever reported as a floor because it could not be read.</b>
 * Both halves of {@link ActivitySource} answer with an empty optional on
 * failure, and an empty answer means this reporter says nothing at all about
 * that player. The failure being avoided is not a missing grant but a mass
 * revocation — every holder dropping to the floor on the sweep after somebody
 * renames a column.
 *
 * <p><b>The value is scaled.</b> A measure is a {@code long} and the host's index
 * is a double between 0 and 5, so an unscaled report would collapse five bands
 * into six integers. Multiplying by {@link #SCALE} keeps three decimal places,
 * and it means a rule's threshold reads as 2000 rather than 2 — which is opaque
 * enough that the scale belongs in the gate's description as well as here.
 */
public final class MeasureReporter {

    /**
     * What the host's 0-to-5 index is multiplied by before it is reported.
     *
     * <p>Three decimal places, which is far finer than the bands need and costs
     * nothing. Chosen once and fixed: changing it silently rescales every rule
     * threshold already written against it, so it is not configurable.
     */
    public static final long SCALE = 1000L;

    private final SoulbindClient client;
    private final ActivitySource source;
    private final String platformKind;
    private final String measureName;
    private final Duration window;
    private final Clock clock;
    private final BiConsumer<String, Throwable> log;

    /** Who was last reported above the floor, and so must be re-reported to fall. */
    private final Set<UUID> lastReportedAboveFloor = new LinkedHashSet<>();

    /** Whether the last sweep failed, so an outage costs one line rather than one per cycle. */
    private boolean sweepFailing;

    public MeasureReporter(
            SoulbindClient client,
            ActivitySource source,
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

        Optional<Set<UUID>> active = source.playersSeenSince(since);
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
        population.addAll(lastReportedAboveFloor);

        Instant now = clock.instant();
        int reported = 0;
        for (UUID player : population) {
            java.util.OptionalDouble measured = source.indexOf(player, now);
            if (measured.isEmpty()) {
                // Say nothing about this player this cycle. Their previous
                // observation stands and goes stale on its own schedule, which
                // is a rule's business rather than this one's.
                continue;
            }

            long scaled = Math.round(measured.getAsDouble() * SCALE);
            if (!report(player, scaled)) {
                continue;
            }
            reported++;

            if (scaled > 0) {
                lastReportedAboveFloor.add(player);
            } else {
                // Drained. A floor is below every threshold, so repeating it
                // next cycle would say nothing and keep the set growing forever.
                lastReportedAboveFloor.remove(player);
            }
        }
        return new Swept(population.size(), reported, true);
    }

    private boolean report(UUID player, long value) {
        SoulbindClient.Outcome outcome = client.call("measure.report",
                new ReportBody(platformKind, player.toString(), measureName, value,
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
