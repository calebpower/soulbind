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

package dev.soulbind.connector.velocity;

import dev.soulbind.config.Config;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * Everything this connector says to a player, as templates an operator owns.
 *
 * <p>The text used to be plain uncoloured strings built in Java, which meant a
 * server whose every other message is formatted got one plugin's output in flat
 * white — and no way to change it short of a rebuild. These are MiniMessage
 * templates in the connector's own config, so they sit beside the rest of a
 * deployment's wording and look like it.
 *
 * <p><b>Values are inserted UNPARSED, and that is a correctness property rather
 * than a style choice.</b> A link code, a count, a refusal reason and — most of
 * all — anything derived from a player's own name can contain angle brackets. A
 * parsed insertion would let a player called {@code <red>x} colour somebody
 * else's screen, and a sufficiently determined one could forge the look of a
 * system message. Only the operator's own templates and the prefix are parsed.
 *
 * <p><b>A typo'd tag reaches the player as literal text.</b> MiniMessage is
 * lenient by default: {@code <colour:red>} renders as those characters rather
 * than throwing. That was probed against the pinned version across eighteen
 * malformed forms -- unclosed, unknown, empty and wrong-arity tags -- and none
 * of them threw. It is left that way deliberately. Strict parsing would mean a
 * mistyped colour tag replaces a player's message with a fallback and the
 * operator finds out from a log line nobody reads; leniency puts the mistake on
 * the screen of the person best placed to report it, and no message is ever
 * lost. It is also what every other plugin on a Minecraft server already does,
 * so an operator's existing instinct about templates is correct here.
 */
public final class Messages {

    /** Shown before every message, so a deployment can brand them all at once. */
    static final String DEFAULT_PREFIX = "<dark_gray>[<aqua>soulbind<dark_gray>]<reset> ";

    static final String DEFAULT_CODE =
            "<prefix><gray>Your link code is <white><bold><code></bold><gray>. "
                    + "Enter it on the other platform to finish linking. "
                    + "It expires in <white><expires><gray>.";

    static final String DEFAULT_LINKED =
            "<prefix><green>Linked. <gray>Your account is now connected to "
                    + "<white><count><gray> other <plural>.";

    static final String DEFAULT_FAILURE = "<prefix><red><reason>";

    static final String DEFAULT_USAGE =
            "<prefix><gray>Usage: <white>/link<gray>, or <white>/link CODE<gray> to finish.";

    static final String DEFAULT_PLAYERS_ONLY = "<prefix><red>/link is for players.";

    static final String DEFAULT_UNAVAILABLE =
            "<prefix><red>Linking is not configured on this proxy.";

    private final MiniMessage mini = MiniMessage.miniMessage();
    private final java.util.function.BiConsumer<String, Throwable> log;

    private final String prefix;
    private final String code;
    private final String linked;
    private final String failure;
    private final String usage;
    private final String playersOnly;
    private final String unavailable;

    public Messages(Config config, java.util.function.BiConsumer<String, Throwable> log) {
        this.log = log;
        this.prefix = read(config, VelocityConfig.MESSAGE_PREFIX, DEFAULT_PREFIX);
        this.code = read(config, VelocityConfig.MESSAGE_CODE, DEFAULT_CODE);
        this.linked = read(config, VelocityConfig.MESSAGE_LINKED, DEFAULT_LINKED);
        this.failure = read(config, VelocityConfig.MESSAGE_FAILURE, DEFAULT_FAILURE);
        this.usage = read(config, VelocityConfig.MESSAGE_USAGE, DEFAULT_USAGE);
        this.playersOnly =
                read(config, VelocityConfig.MESSAGE_PLAYERS_ONLY, DEFAULT_PLAYERS_ONLY);
        this.unavailable =
                read(config, VelocityConfig.MESSAGE_UNAVAILABLE, DEFAULT_UNAVAILABLE);
    }

    private static String read(Config config, dev.soulbind.config.ConfigKey key, String fallback) {
        return config.findString(key).filter(s -> !s.isBlank()).orElse(fallback);
    }

    /** The code a player was just issued. */
    public Component code(String linkCode, String expires) {
        return render(this.code,
                Placeholder.unparsed("code", linkCode),
                Placeholder.unparsed("expires", expires));
    }

    /** A link that completed, and how many accounts it joined. */
    public Component linked(int others) {
        return render(this.linked,
                Placeholder.unparsed("count", Integer.toString(others)),
                Placeholder.unparsed("plural", others == 1 ? "account" : "accounts"));
    }

    /** Anything that did not work, in the words the logic chose. */
    public Component failure(String reason) {
        return render(this.failure, Placeholder.unparsed("reason", reason));
    }

    public Component usage() {
        return render(this.usage);
    }

    public Component playersOnly() {
        return render(this.playersOnly);
    }

    public Component unavailable() {
        return render(this.unavailable);
    }

    /**
     * The kick screen, from the gate's own configured wording.
     *
     * <p>The template here is {@code gate.kickmessage} itself rather than a
     * separate key, because that key already exists and already holds the
     * sentence an operator wrote. Parsing it is backward compatible: text with
     * no tags in it renders as itself, so an estate that never wanted colour
     * sees exactly what it saw before.
     *
     * <p>No prefix. A kick screen is a full page, not a chat line, and a chat
     * connector's bracketed tag looks wrong across the middle of one.
     */
    public Component kick(String text) {
        return render(text == null || text.isBlank()
                ? VelocityConfig.DEFAULT_KICK_MESSAGE : text);
    }

    /**
     * A reply as the operator's templates render it.
     *
     * <p>Lives here rather than in the plugin because the plugin cannot be
     * constructed without a proxy, and a mapping that decides which template a
     * player sees is not something to leave in the one class no test can reach.
     *
     * <p>{@code PLAIN} falls through to the logic's own sentence, so a reply
     * shape added before it has a template of its own arrives uncoloured rather
     * than not at all.
     */
    public Component render(LinkCommandLogic.Reply reply) {
        return switch (reply.kind()) {
            case CODE -> code(
                    reply.values().getOrDefault("code", ""),
                    reply.values().getOrDefault("expires", ""));
            case LINKED -> linked(
                    Integer.parseInt(reply.values().getOrDefault("count", "0")));
            case USAGE -> usage();
            case FAILED -> failure(reply.message());
            case PLAIN -> Component.text(reply.message());
        };
    }

    /**
     * Renders a template with the prefix and the given values available.
     *
     * <p><b>The catch is a boundary guard, not a live path.</b>
     * {@code deserialize} is documented to throw {@code ParsingException}, and
     * the pinned version does not throw for any malformed template probed --
     * see this class's note. It stays because this runs on a command thread and
     * on the join event thread, where an escaping exception costs a player their
     * message or their connection, and because the version that changes that is
     * an upgrade away. Nothing exercises it; that is a stated narrowing rather
     * than an oversight.
     */
    private Component render(String template, TagResolver... values) {
        TagResolver[] all = new TagResolver[values.length + 1];
        all[0] = Placeholder.parsed("prefix", prefix);
        System.arraycopy(values, 0, all, 1, values.length);
        try {
            return mini.deserialize(template, all);
        } catch (RuntimeException e) {
            log.accept("a message template will not parse; showing it as written. "
                    + "Template: " + template, e);
            return Component.text(template);
        }
    }
}
