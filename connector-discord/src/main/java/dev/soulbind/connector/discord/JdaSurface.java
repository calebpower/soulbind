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
import java.util.function.BooleanSupplier;
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

    /** The decisions, over a platform this class supplies. */
    private final GuildRoles roles;

    public JdaSurface(JDA jda, String guildId, BiConsumer<String, Throwable> log) {
        this.jda = jda;
        this.guildId = guildId;
        this.log = log;
        this.roles = new GuildRoles(new JdaPlatform(), log);
    }

    /**
     * With a platform supplied, so the routing itself can be tested.
     *
     * <p>The four {@code ChatSurface} role methods are one line each and every
     * one of them delegates. That is exactly the kind of code a reader checks by
     * eye and gets wrong: a grant wired to {@code revoke} would take the role
     * off everybody who earned it, and nothing but a live Discord would have
     * said so.
     */
    JdaSurface(
            JDA jda, String guildId, GuildRoles.Platform platform,
            BiConsumer<String, Throwable> log) {
        this.jda = jda;
        this.guildId = guildId;
        this.log = log;
        this.roles = new GuildRoles(platform, log);
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
        return roles.grant(platformId, role);
    }

    @Override
    public boolean revokeRole(String platformId, String role) {
        return roles.revoke(platformId, role);
    }

    @Override
    public boolean hasRole(String platformId, String role) {
        return roles.has(platformId, role);
    }

    @Override
    public List<String> membersWithRole(String role) {
        return roles.holders(role);
    }

    /**
     * The library half of the role mechanics: lookups and calls, no decisions.
     *
     * <p>Everything that was a decision moved to {@link GuildRoles}, which is
     * testable without a Discord. What is left here is the part a fake could
     * not check anyway — which is what this file always claimed to be, and was
     * not.
     */
    private final class JdaPlatform implements GuildRoles.Platform {

        /**
         * One lookup answering every question the decisions ask.
         *
         * <p>A null {@code platformId} means "about the role, not about anybody"
         * — the holders path — and skips the member retrieval rather than
         * retrieving nothing.
         */
        @Override
        public GuildRoles.Pair resolve(String platformId, String role) {
            Guild guild = guild().orElse(null);
            if (guild == null) {
                return new GuildRoles.Pair(false, false, false, false);
            }
            List<Role> found = guild.getRolesByName(role, true);
            if (found.isEmpty()) {
                return new GuildRoles.Pair(true, false, false, false);
            }
            if (platformId == null) {
                return new GuildRoles.Pair(true, true, false, false);
            }
            Member member = guild.retrieveMemberById(platformId)
                    .timeout(10, TimeUnit.SECONDS).complete();
            if (member == null) {
                return new GuildRoles.Pair(true, true, false, false);
            }
            return new GuildRoles.Pair(
                    true, true, true, member.getRoles().contains(found.get(0)));
        }

        /**
         * Resolves again before mutating, and that is a real cost stated rather
         * than hidden: on the path that actually changes something, the member
         * is retrieved twice.
         *
         * <p>The connector requests {@code GUILD_MEMBERS} and JDA answers
         * {@code retrieveMemberById} from its member cache when it can, so the
         * second lookup is usually not a round trip — and a role changes once
         * per link, not once per message. Threading the handles out of
         * {@code resolve} instead would put library types back across the seam,
         * which is the whole thing being paid for here.
         */
        @Override
        public void addRole(String platformId, String role) {
            each(platformId, role, (guild, member, resolved) ->
                    guild.addRoleToMember(member, resolved).complete());
        }

        @Override
        public void removeRole(String platformId, String role) {
            each(platformId, role, (guild, member, resolved) ->
                    guild.removeRoleFromMember(member, resolved).complete());
        }

        @Override
        public List<String> holders(String role) {
            Guild guild = guild().orElseThrow();
            List<Role> found = guild.getRolesByName(role, true);
            // loadMembers rather than the cache: GUILD_MEMBERS is requested but
            // the cache is populated lazily, and reconciling against a partial
            // view would revoke nothing from the members it had not seen --
            // silently, and differently on each restart.
            return guild.findMembers(member -> member.getRoles().contains(found.get(0)))
                    .get().stream()
                    .map(member -> member.getUser().getId())
                    .toList();
        }

        /** Re-resolves the three handles and hands them to the caller. */
        private void each(String platformId, String role, RoleWork work) {
            Guild guild = guild().orElseThrow();
            Role resolved = guild.getRolesByName(role, true).get(0);
            Member member = guild.retrieveMemberById(platformId)
                    .timeout(10, TimeUnit.SECONDS).complete();
            work.apply(guild, member, resolved);
        }
    }

    /** How long to wait for a configured guild to become visible. */
    private static final int GUILD_WAIT_ATTEMPTS = 20;

    private static final long GUILD_WAIT_MILLIS = 500L;

    /**
     * Polls until the configured guild is visible, or the budget runs out.
     *
     * <p>Extracted and given its pause as a parameter so a test can exercise
     * the decision without waiting or without a Discord. The thing worth
     * testing is not the sleeping.
     *
     * @param available whether the guild can be seen right now
     * @param attempts how many times to look
     * @param pause what to do between looks
     * @return true if it appeared within the budget
     */
    static boolean awaitGuild(BooleanSupplier available, int attempts, Runnable pause) {
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (available.getAsBoolean()) {
                return true;
            }
            pause.run();
        }
        return false;
    }

    @Override
    public void registerCommands(List<String> commands) {
        boolean wantsGuild = guildId != null && !guildId.isBlank();

        // REFUSING, rather than registering globally instead.
        //
        // The first live run of this connector registered three commands
        // globally while platform.guild named a server -- the bot had not been
        // invited to it yet, so the guild was not visible. The fallback was
        // silent apart from one log line, and it left three global commands on
        // the application: they appear in EVERY server the bot is ever added
        // to, take up to an hour to propagate, and stay until somebody deletes
        // them. Cleaning that up is manual and nothing tells the operator it
        // is needed.
        //
        // A typo in the guild id does exactly the same thing. So an operator
        // who names a server and gets global registration has a
        // misconfiguration hidden from them by the very mechanism meant to be
        // helpful. Global registration remains available -- by LEAVING
        // platform.guild unset, which is a choice rather than an accident.
        if (wantsGuild) {
            requireGuildVisible(
                    guildId, () -> guild().isPresent(), GUILD_WAIT_ATTEMPTS,
                    GUILD_WAIT_MILLIS, JdaSurface::pause);
        }

        Guild guild = wantsGuild ? guild().orElseThrow() : null;

        var link = Commands.slash("link", "Link this account to another platform")
                .addOptions(new OptionData(
                        OptionType.STRING, "code",
                        "a code issued on the other platform; omit to get one", false));
        var whoami = Commands.slash("whoami", "Show what this account is linked to");
        var admin = Commands.slash("soulbind", "Administrative commands")
                .addOptions(new OptionData(
                        OptionType.STRING, "subcommand",
                        // Describes the OPTION, not one of its values. It read
                        // "connectors", which is what Discord showed the person
                        // typing -- a hint that named half the answer.
                        "rules <gate>, or connectors", false));

        if (guild != null) {
            // Guild-scoped registration appears immediately; global takes up to
            // an hour to propagate. For a deployment with one server that is a
            // strictly better experience, and for a test it is the difference
            // between a run and a wait.
            guild.updateCommands().addCommands(link, whoami, admin).queue(
                    // The count comes from what Discord acknowledged, not from
                    // the argument: the two agreeing is a coincidence today and
                    // a lie the first time they do not.
                    registered -> log.accept(
                            "registered " + registered.size() + " commands to guild "
                                    + guildId, null),
                    failure -> log.accept("could not register commands", failure));
        } else {
            jda.updateCommands().addCommands(link, whoami, admin).queue(
                    registered -> log.accept(
                            "registered " + registered.size() + " commands globally; they can "
                                    + "take up to an hour to appear, in every server this bot "
                                    + "is in. Set platform.guild to scope them to one.", null),
                    failure -> log.accept("could not register commands", failure));
        }
    }

    /**
     * Refuses to continue if the named server never becomes visible.
     *
     * <p>Extracted for the same reason {@link #awaitGuild} was, and it should
     * have gone at the same time: the decision is the refusal, and the refusal
     * was reachable only with a live gateway. The message is long because it is
     * the whole of what an operator gets — it names the id they typed, the time
     * spent, what would otherwise have happened, and both ways out.
     *
     * @param guildId the configured server, for the message
     * @param visible whether it can be seen right now
     * @param attempts how many times to look
     * @param waitMillis how long each look waits, for the message only
     * @param pause what to do between looks
     * @throws IllegalStateException if it never appeared
     */
    static void requireGuildVisible(
            String guildId,
            BooleanSupplier visible,
            int attempts,
            long waitMillis,
            Runnable pause) {

        if (awaitGuild(visible, attempts, pause)) {
            return;
        }
        throw new IllegalStateException(
                "platform.guild names '" + guildId + "' and this bot cannot see that"
                        + " server after " + (attempts * waitMillis / 1000)
                        + "s. Registering the commands globally instead would put them in"
                        + " every server this bot is in, for up to an hour, and leave them"
                        + " there -- so this refuses rather than quietly doing something"
                        + " other than what the configuration asks. Check that the bot has"
                        + " been invited to that server and that the id is correct; or"
                        + " unset platform.guild to register globally on purpose.");
    }

    /** Sleeps between looks, restoring the interrupt rather than swallowing it. */
    private static void pause() {
        try {
            Thread.sleep(GUILD_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * What {@link JdaPlatform#each} hands its caller once the three handles are
     * resolved. Void: whether it worked is the absence of an exception, and the
     * decision about what that means lives in {@link GuildRoles}.
     */
    @FunctionalInterface
    private interface RoleWork {
        void apply(Guild guild, Member member, Role role);
    }

    private Optional<Guild> guild() {
        if (guildId == null || guildId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(jda.getGuildById(guildId));
    }
}
