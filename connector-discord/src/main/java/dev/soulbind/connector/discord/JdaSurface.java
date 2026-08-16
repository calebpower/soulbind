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
package dev.soulbind.connector.discord;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.OptionType;

/**
 * The surface, backed by the real client library.
 *
 * <p><b>This is the only file in the connector that names a library type.</b>
 * Everything with a decision in it lives above the seam and is tested against
 * the scripted surface; what is here is translation, and translation is the part
 * a fake cannot check anyway.
 *
 * <p>Kept deliberately mechanical for that reason. If a behaviour worth
 * asserting appears in this file, it belongs in {@link ChatConnector}.
 */
public final class JdaSurface implements ChatSurface {

    private final JDA jda;
    private final String guildId;
    private final BiConsumer<String, Throwable> log;

    /**
     * Interactions awaiting a reply, by invocation.
     *
     * <p>The library hands a reply callback with the event, and the seam's
     * {@code reply} takes only the invocation — so the callback has to be found
     * again. A map rather than threading it through the interface, because
     * putting a library handle in {@link Invocation} would put a library type in
     * every file that touches one.
     */
    private final Map<Invocation, net.dv8tion.jda.api.interactions.callbacks.IReplyCallback>
            pending = new ConcurrentHashMap<>();

    public JdaSurface(JDA jda, String guildId, BiConsumer<String, Throwable> log) {
        this.jda = jda;
        this.guildId = guildId;
        this.log = log;
    }

    /** Remembers where to send a reply, and returns the invocation to hand upward. */
    public Invocation adopt(
            net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent event) {

        List<String> arguments = event.getOptions().stream()
                .map(net.dv8tion.jda.api.interactions.commands.OptionMapping::getAsString)
                .toList();

        Member member = event.getMember();
        Invocation invocation = new Invocation(
                event.getName(),
                arguments,
                new Invoker(
                        event.getUser().getId(),
                        event.getUser().getEffectiveName(),
                        // Administrator on THIS server, asked of the platform
                        // rather than inferred from a role name. A name is
                        // something anybody with rename rights can arrange.
                        member != null && member.hasPermission(Permission.ADMINISTRATOR)));

        pending.put(invocation, event);
        return invocation;
    }

    @Override
    public void reply(Invocation invocation, String message, boolean ephemeral) {
        var callback = pending.remove(invocation);
        if (callback == null) {
            // Nothing to reply to. Logged rather than thrown: the connector has
            // already done its work, and a missing callback is a bug here, not
            // a reason to fail the operation that succeeded.
            log.accept("no pending interaction for " + invocation.command(), null);
            return;
        }
        callback.reply(message).setEphemeral(ephemeral).queue(
                success -> { },
                failure -> log.accept("could not reply to " + invocation.command(), failure));
    }

    @Override
    public boolean grantRole(String platformId, String role) {
        return withRole(platformId, role, (guild, member, resolved) -> {
            if (member.getRoles().contains(resolved)) {
                // Held already. TRUE, because the contract is whether the role
                // is held afterwards -- not whether this call changed anything.
                return true;
            }
            guild.addRoleToMember(member, resolved).complete();
            return true;
        });
    }

    @Override
    public boolean revokeRole(String platformId, String role) {
        return withRole(platformId, role, (guild, member, resolved) -> {
            if (!member.getRoles().contains(resolved)) {
                return true;
            }
            guild.removeRoleFromMember(member, resolved).complete();
            return true;
        });
    }

    @Override
    public boolean hasRole(String platformId, String role) {
        return withRole(platformId, role,
                (guild, member, resolved) -> member.getRoles().contains(resolved));
    }

    @Override
    public void registerCommands(List<String> commands) {
        Guild guild = guild().orElse(null);

        var link = Commands.slash("link", "Link this account to another platform")
                .addOptions(new OptionData(
                        OptionType.STRING, "code",
                        "a code issued on the other platform; omit to get one", false));
        var whoami = Commands.slash("whoami", "Show what this account is linked to");
        var admin = Commands.slash("soulbind", "Administrative commands")
                .addOptions(new OptionData(
                        OptionType.STRING, "subcommand", "connectors", false));

        if (guild != null) {
            // Guild-scoped registration appears immediately; global takes up to
            // an hour to propagate. For a deployment with one server that is a
            // strictly better experience, and for a test it is the difference
            // between a run and a wait.
            guild.updateCommands().addCommands(link, whoami, admin).queue(
                    success -> log.accept("registered " + commands.size() + " commands", null),
                    failure -> log.accept("could not register commands", failure));
        } else {
            jda.updateCommands().addCommands(link, whoami, admin).queue(
                    success -> log.accept(
                            "registered " + commands.size() + " commands globally; they can "
                                    + "take up to an hour to appear", null),
                    failure -> log.accept("could not register commands", failure));
        }
    }

    @FunctionalInterface
    private interface RoleWork {
        boolean apply(Guild guild, Member member, Role role);
    }

    /**
     * Resolves the server, member and role, or reports why not.
     *
     * <p>Every failure returns false rather than throwing, and says which of the
     * three was missing. "Could not grant role" is a message an operator cannot
     * act on; "no role named X on this server" is one they can.
     */
    private boolean withRole(String platformId, String role, RoleWork work) {
        Optional<Guild> guild = guild();
        if (guild.isEmpty()) {
            log.accept("no server configured, so roles cannot be applied", null);
            return false;
        }

        List<Role> roles = guild.get().getRolesByName(role, true);
        if (roles.isEmpty()) {
            log.accept("no role named '" + role + "' on this server", null);
            return false;
        }

        try {
            Member member = guild.get().retrieveMemberById(platformId)
                    .timeout(10, TimeUnit.SECONDS).complete();
            if (member == null) {
                log.accept("no member " + platformId + " on this server", null);
                return false;
            }
            return work.apply(guild.get(), member, roles.get(0));
        } catch (RuntimeException e) {
            // The member left, the bot lost its permission, the gateway
            // hiccupped. Reported and false -- the effector then does not
            // acknowledge, and the event comes back.
            log.accept("could not apply role '" + role + "' to " + platformId, e);
            return false;
        }
    }

    private Optional<Guild> guild() {
        if (guildId == null || guildId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jda.getGuildById(guildId));
    }
}
