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

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.DataExtension;
import com.djrapitops.plan.extension.FormatType;
import com.djrapitops.plan.extension.annotation.BooleanProvider;
import com.djrapitops.plan.extension.annotation.NumberProvider;
import com.djrapitops.plan.extension.annotation.PluginInfo;
import com.djrapitops.plan.extension.annotation.StringProvider;
import com.djrapitops.plan.extension.annotation.TableProvider;
import com.djrapitops.plan.extension.icon.Color;
import com.djrapitops.plan.extension.icon.Family;
import com.djrapitops.plan.extension.table.Table;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What Plan renders. One line per question, and no judgement anywhere.
 *
 * <p>Every decision lives in {@link LinkDataSource}, which knows nothing about
 * Plan and is tested without a Minecraft server. This class exists to be
 * annotated: it cannot be exercised without Plan scanning it, so the less it
 * decides the less goes untested.
 *
 * <p><b>Read-only, and not by politeness.</b> The connector is registered with
 * capabilities that permit inspection and nothing else, so a provider here
 * cannot mutate the identity graph even if somebody added one that tried. The
 * plan's "mutations stay on the admin API" is enforced by the credential rather
 * than by this file remembering.
 *
 * <p>The roster comes from the caller, because Plan asks about the players it
 * knows and core does not know who is online.
 */
@PluginInfo(
        name = "soulbind",
        iconName = "link",
        iconFamily = Family.SOLID,
        color = Color.BLUE)
public final class SoulbindDataExtension implements DataExtension {

    private final LinkDataSource data;
    private final Supplier<Map<UUID, String>> roster;

    public SoulbindDataExtension(LinkDataSource data, Supplier<Map<UUID, String>> roster) {
        this.data = data;
        this.roster = roster;
    }

    /**
     * When Plan should call these providers.
     *
     * <p>Not {@code PLAYER_JOIN}: a join is the moment a player is least likely
     * to have just linked, and calling then would put a round trip on the join
     * path -- the one path a proxy plugin must never make slower.
     */
    @Override
    public CallEvents[] callExtensionMethodsOn() {
        return new CallEvents[] {CallEvents.PLAYER_LEAVE, CallEvents.SERVER_PERIODICAL};
    }

    // --- per player ---------------------------------------------------------

    @BooleanProvider(
            text = "Linked",
            description = "Whether this account is linked to another platform",
            iconName = "link",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE)
    public boolean linked(UUID playerUuid) {
        // A player core could not be asked about reports false here, because a
        // boolean has no third value -- which is exactly why the string below
        // exists and says "unknown" in words. Reading only this one would be
        // reading a confident answer that was never given.
        return data.player(playerUuid.toString()).linked();
    }

    @StringProvider(
            text = "Link status",
            description = "linked, not linked, or unknown when core is unreachable",
            iconName = "link",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE)
    public String linkStatus(UUID playerUuid) {
        return data.player(playerUuid.toString()).describe();
    }

    @StringProvider(
            text = "Platforms",
            description = "The platform kinds this account is verified on",
            iconName = "users",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE)
    public String platforms(UUID playerUuid) {
        List<String> kinds = data.player(playerUuid.toString()).kinds();
        return kinds.isEmpty() ? "-" : String.join(", ", kinds);
    }

    @StringProvider(
            text = "Proof",
            description = "How each link was proven",
            iconName = "shield-alt",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE)
    public String proof(UUID playerUuid) {
        List<String> methods = data.player(playerUuid.toString()).proofMethods();
        return methods.isEmpty() ? "-" : String.join(", ", methods);
    }

    @NumberProvider(
            text = "Linked since",
            description = "When this account first became linked",
            iconName = "calendar",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE,
            format = FormatType.DATE_YEAR)
    public long linkedSince(UUID playerUuid) {
        // Milliseconds: Plan's date formats expect them, and core speaks
        // seconds. A factor of a thousand here renders 1970 on every page,
        // which looks like a data problem rather than a units one.
        return data.player(playerUuid.toString())
                .verifiedAtEpochSeconds()
                .map(seconds -> seconds * 1000L)
                .orElse(0L);
    }

    @StringProvider(
            text = "Subject",
            description = "The soulbind subject id, when the operator has opted in",
            iconName = "fingerprint",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE)
    public String subject(UUID playerUuid) {
        return data.player(playerUuid.toString()).subjectId().orElse("-");
    }

    // --- server wide --------------------------------------------------------

    @NumberProvider(
            text = "Linked players",
            description = "Known players whose accounts are linked",
            iconName = "link",
            iconFamily = Family.SOLID,
            iconColor = Color.BLUE)
    public long linkedPlayers() {
        return summary().linked();
    }

    @NumberProvider(
            text = "Unlinked players",
            description = "Known players whose accounts are not linked",
            iconName = "unlink",
            iconFamily = Family.SOLID,
            iconColor = Color.RED)
    public long unlinkedPlayers() {
        return summary().unlinked();
    }

    @NumberProvider(
            text = "Unknown",
            description = "Players core could not be asked about",
            iconName = "question",
            iconFamily = Family.SOLID,
            iconColor = Color.AMBER)
    public long unknownPlayers() {
        // Its own number, on the page, next to the other two. Without it an
        // operator sees the linked and unlinked counts not adding up to the
        // roster and has nothing to attribute the difference to.
        return summary().unknown();
    }

    @TableProvider(tableColor = Color.BLUE)
    public Table unlinkedTable() {
        Table.Factory table = Table.builder()
                .columnOne("Player", com.djrapitops.plan.extension.icon.Icon.called("user").build());

        for (String name : summary().unlinkedNames()) {
            table.addRow(name);
        }
        return table.build();
    }

    private ServerLinkSummary summary() {
        Map<UUID, String> players = roster.get();
        return data.summary(
                players.keySet().stream().map(UUID::toString).toList(),
                players.entrySet().stream().collect(
                        java.util.stream.Collectors.toMap(
                                e -> e.getKey().toString(), Map.Entry::getValue)));
    }
}
