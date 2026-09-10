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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.config.Config;
import dev.soulbind.config.ConfigLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The templates an operator owns, and the one property that is not cosmetic.
 *
 * <p>Most of this file is about colour, which is the easy half. The half that
 * matters is that a VALUE never becomes markup: a player's name, a refusal
 * quoted from core and a link code all pass through here, and a parsed
 * insertion would let a player choose what somebody else's screen says.
 */
class MessagesTest {

    private final List<String> warnings = new ArrayList<>();

    private static final String CUSTOM = """
            [messages]
            playersonly = "console only"
            unavailable = "no core"
            """;

    /** The one key the schema requires, so each case states only what it is about. */
    private static final String CORE = """
            [core]
            url = "http://127.0.0.1:7000"
            """;

    private Messages messages(String toml) {
        Config config =
                ConfigLoader.parse(CORE + toml, "test", VelocityConfig.SCHEMA, Map.of());
        return new Messages(config, (message, cause) -> warnings.add(message));
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    // --- the defaults ---------------------------------------------------------

    @Test
    @DisplayName("with nothing configured, the built-in templates render and are coloured")
    void defaultsRender() {
        Component code = messages("").code("BCDFGHJK", "9 minutes");

        assertTrue(plain(code).contains("BCDFGHJK"), plain(code));
        assertTrue(plain(code).contains("9 minutes"), plain(code));
        assertTrue(plain(code).contains("soulbind"), "the default prefix did not render");
        assertFalse(plain(code).contains("<"),
                "a tag survived into the rendered text, so it was never parsed: " + plain(code));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    @DisplayName("the plural agrees with the count")
    void pluralAgrees() {
        // Asserted with the trailing STOP, and that is not fussiness: "1 other
        // account" is a prefix of "1 other accounts", so the obvious contains()
        // assertion passes whichever branch ran. LinkCommandLogicTest carries
        // the same warning about the same sentence; the first version of this
        // test ignored it and let the mutant straight through.
        assertTrue(plain(messages("").linked(1)).endsWith(" 1 other account."),
                plain(messages("").linked(1)));
        assertTrue(plain(messages("").linked(3)).endsWith(" 3 other accounts."),
                plain(messages("").linked(3)));
    }

    @Test
    @DisplayName("the two messages nobody links to still say something useful")
    void theSourcelessMessagesRender() {
        // playersOnly and unavailable are reached from the plugin, which no
        // test constructs -- so without this they are the templates that would
        // ship unrendered. Both are what somebody sees at the exact moment
        // linking is not working, which is the worst time for a blank line.
        assertTrue(plain(messages("").playersOnly()).contains("/link"));
        assertTrue(plain(messages("").unavailable()).toLowerCase().contains("not configured"));

        assertEquals("console only", plain(messages(CUSTOM).playersOnly()));
        assertEquals("no core", plain(messages(CUSTOM).unavailable()));
    }

    // --- what an operator changes ---------------------------------------------

    @Test
    @DisplayName("an operator's template replaces the default, prefix and all")
    void operatorTemplateWins() {
        Messages m = messages("""
                [messages]
                prefix = "<gold>>> "
                code = "<prefix><yellow>Code: <code>"
                """);

        assertEquals(">> Code: BCDFGHJK", plain(m.code("BCDFGHJK", "9 minutes")));
    }

    @Test
    @DisplayName("a blank template falls back rather than rendering nothing")
    void blankIsNotEmpty() {
        // An operator who clears a key means "I do not want to customise this",
        // not "say nothing to the player" -- and silence in response to /link
        // reads as a broken plugin.
        assertFalse(plain(messages("""
                [messages]
                usage = ""
                """).usage()).isBlank());
    }

    @Test
    @DisplayName("the kick screen uses the gate's own wording, and takes no prefix")
    void kickIsTheGatesWording() {
        Messages m = messages("");
        Component kick = m.kick("<red>Link first. (missing: discord)");

        assertEquals("Link first. (missing: discord)", plain(kick));
        assertFalse(plain(kick).contains("soulbind"),
                "a chat prefix appeared on a kick screen");
        assertEquals(NamedTextColor.RED, kick.children().isEmpty()
                ? kick.color() : kick.children().get(0).color());
    }

    @Test
    @DisplayName("an untagged kick message renders as itself, so existing estates are unchanged")
    void plainKickIsUnchanged() {
        // gate.kickmessage predates templating and is full of ordinary prose on
        // every deployment that already runs this. Parsing it must be a no-op
        // for text with no tags in it, or this change is a silent regression on
        // the one message every denied player sees.
        assertEquals(VelocityConfig.DEFAULT_KICK_MESSAGE,
                plain(messages("").kick(VelocityConfig.DEFAULT_KICK_MESSAGE)));
    }

    @Test
    @DisplayName("an unset kick message falls back to the gate's documented default")
    void unsetKickFallsBack() {
        // JoinGate always supplies one today, so this guards the branch rather
        // than a live path -- but the branch is the difference between a
        // documented sentence and an empty kick screen, which reads to a player
        // as the proxy dropping them for no reason.
        for (String nothing : new String[] {null, "", "   "}) {
            assertEquals(VelocityConfig.DEFAULT_KICK_MESSAGE,
                    plain(messages("").kick(nothing)));
        }
    }

    // --- which template a reply gets ------------------------------------------

    @Test
    @DisplayName("each reply kind reaches its own template")
    void everyKindIsRouted() {
        Messages m = messages("");
        Map<String, String> code = Map.of("code", "BCDFGHJK", "expires", "9 minutes");

        // One case per arm, because a switch that sends two kinds to the same
        // template is invisible until a player is told their link failed and
        // handed a code in the same breath.
        assertTrue(plain(m.render(new LinkCommandLogic.Reply(
                true, "sentence", LinkCommandLogic.Reply.Kind.CODE, code)))
                .contains("BCDFGHJK"));
        assertTrue(plain(m.render(new LinkCommandLogic.Reply(
                true, "sentence", LinkCommandLogic.Reply.Kind.LINKED, Map.of("count", "2"))))
                .endsWith(" 2 other accounts."));
        assertTrue(plain(m.render(new LinkCommandLogic.Reply(
                false, "sentence", LinkCommandLogic.Reply.Kind.USAGE, Map.of())))
                .contains("/link"));
        assertTrue(plain(m.render(new LinkCommandLogic.Reply(false, "that code expired")))
                .contains("that code expired"));
    }

    @Test
    @DisplayName("a plain reply is delivered as written rather than dropped")
    void plainIsDelivered() {
        // The arm that exists so a reply shape added later is uncoloured, not
        // silent. Deleting it would compile -- switch arms over an enum are
        // exhaustive by kind, not by intent -- and lose a message.
        assertEquals("something new", plain(messages("").render(
                new LinkCommandLogic.Reply(true, "something new"))));
    }

    // --- the property that is not cosmetic -------------------------------------

    @Test
    @DisplayName("a value is never parsed, so no player can forge markup through one")
    void valuesAreNeverParsed() {
        // The reason this is a test and not a comment. `reason` is quoted from
        // core's refusal, which can name the account somebody typed; a parsed
        // insertion would let a player called "<red>..." colour another
        // player's screen, and a determined one forge a system message.
        Component failure = messages("").failure("<red><bold>Server</bold></red> says no");

        assertTrue(plain(failure).contains("<red><bold>Server</bold></red> says no"),
                "the value was parsed as markup: " + plain(failure));
    }

    @Test
    @DisplayName("EVERY value is unparsed, not only the one an attacker reaches first")
    void everyValueIsUnparsed() {
        // `reason` has the shortest path from a player, so it is the value that
        // gets the attention -- and asserting only it leaves the property half
        // held. The code and the expiry are core's today; the insertion rule is
        // exactly what keeps that from being load-bearing.
        assertTrue(plain(messages("").code("<red>x", "<bold>soon")).contains("<red>x"));
        assertTrue(plain(messages("").code("<red>x", "<bold>soon")).contains("<bold>soon"));

        // The plural is derived here rather than supplied, and it must stay
        // unreachable as markup for the same reason.
        assertTrue(plain(messages("""
                [messages]
                linked = "<prefix><plural>"
                """).linked(1)).endsWith("account"));
    }

    @Test
    @DisplayName("a value that is not valid MiniMessage does not break the message")
    void malformedValueIsInert() {
        // An unclosed tag is a parse error where it is parsed. Inserted
        // unparsed, it is just characters -- which is the whole point.
        assertTrue(plain(messages("").code("<click:run_command:", "9 minutes"))
                .contains("<click:run_command:"));
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    // --- when the operator gets it wrong ---------------------------------------

    @Test
    @DisplayName("a typo'd tag reaches the player as text, and the message still arrives")
    void typoIsShownNotSwallowed() {
        // `<darkred>` is not a tag; the real one is `dark_red`. `<colour:red>`
        // would NOT have worked as this test's typo -- MiniMessage takes the
        // British spelling as an alias for `color`, which is how the first
        // version of this test managed to assert that a valid template was
        // broken. MiniMessage is lenient: this does not throw, and the fallback branch
        // in render() is therefore never taken. Asserted rather than assumed,
        // because the whole shape of the failure mode depends on it -- if a
        // future version starts throwing here, this test says so, and the
        // decision recorded in Messages' javadoc has to be revisited.
        Messages m = messages("""
                [messages]
                code = "<prefix><darkred>Code: <code>"
                """);
        String rendered = plain(m.code("BCDFGHJK", "9 minutes"));

        assertTrue(rendered.contains("<darkred>"),
                "the typo was not shown to the operator who made it: " + rendered);
        assertTrue(rendered.contains("BCDFGHJK"),
                "a typo elsewhere in the template ate the value: " + rendered);
        assertTrue(rendered.contains("soulbind"), "the prefix was lost: " + rendered);
        assertTrue(warnings.isEmpty(), warnings::toString);
    }

    @Test
    @DisplayName("an unknown placeholder is inert rather than fatal")
    void unknownPlaceholderIsInert() {
        // Somebody will write <name> because every other plugin has one. It has
        // to be a visible no-op, not a message the player never receives.
        assertTrue(plain(messages("""
                [messages]
                usage = "<prefix>Ask <name> for help"
                """).usage()).contains("<name>"));
    }

}
