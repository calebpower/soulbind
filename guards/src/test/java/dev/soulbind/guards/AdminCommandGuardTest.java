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

package dev.soulbind.guards;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The administrative command is gated three ways, and none of them is optional.
 *
 * <p>{@code ChatConnector} refuses a non-administrator before core is asked;
 * the connector's credential does not hold {@code config-management}, so core
 * refuses too; and Discord itself denies the command to members without the
 * ADMINISTRATOR permission. The first is unit-tested, the second is a
 * deployment decision recorded in {@code harness/principals.txt}, and the third
 * lives in {@code JdaSurface} — which is deliberately untestable platform
 * wiring, so it is asserted here as source text.
 *
 * <p>Scanning source is the weakest kind of check this project writes, and it is
 * used because the alternative was nothing: the declaration cannot be exercised
 * without a live Discord. What makes it worth having is that the failure it
 * guards against is silent — the command simply appears in every member's
 * picker, and only a person trying it would ever find out.
 */
class AdminCommandGuardTest {

    private static final String SURFACE =
            "connector-discord/src/main/java/dev/soulbind/connector/discord/JdaSurface.java";
    private static final String CONNECTOR =
            "connector-discord/src/main/java/dev/soulbind/connector/discord/ChatConnector.java";

    /**
     * The chained declaration of one slash command, from {@code Commands.slash}
     * to the {@code ;} that ends the statement.
     *
     * <p>Scoped deliberately. Asserting that the FILE mentions
     * {@code setDefaultPermissions} somewhere would pass if it were attached to
     * {@code /link} instead — which would restrict the wrong command and leave
     * the administrative one open, while reading as protection.
     */
    private static String declarationOf(String source, String command) {
        int start = source.indexOf("Commands.slash(\"" + command + "\"");
        assertTrue(start >= 0,
                "no Commands.slash(\"" + command + "\") in JdaSurface. Either the command was"
                        + " renamed or registration moved, and this guard is now asserting"
                        + " nothing.");
        int end = source.indexOf(";", start);
        assertTrue(end > start, "unterminated declaration for " + command);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("Discord itself denies /soulbind to non-administrators")
    void theAdminCommandIsRestrictedAtThePlatform() throws IOException {
        String source = Files.readString(SourceTree.repoRoot().resolve(SURFACE));
        String admin = declarationOf(source, "soulbind");

        assertTrue(admin.contains("setDefaultPermissions"),
                "the /soulbind command does not set a default member permission, so Discord"
                        + " lists it to every member as \"Administrative commands\" and the"
                        + " only thing refusing them is application code. Deployed that way"
                        + " once; see DECISIONS 10.56.");
        assertTrue(admin.contains("ADMINISTRATOR"),
                "/soulbind sets a default permission that is not ADMINISTRATOR. The"
                        + " connector's own check asks hasPermission(ADMINISTRATOR), and two"
                        + " gates disagreeing about who is an administrator is worse than one"
                        + " gate: " + admin);
    }

    @Test
    @DisplayName("the ordinary commands are NOT restricted, so the check above means something")
    void theOrdinaryCommandsStayOpen() throws IOException {
        // Without this, a change that restricted everything -- including /link,
        // which every member needs -- would satisfy the test above while
        // breaking the product for exactly the people it is for.
        String source = Files.readString(SourceTree.repoRoot().resolve(SURFACE));
        for (String command : new String[] {"link", "whoami"}) {
            assertTrue(!declarationOf(source, command).contains("setDefaultPermissions"),
                    "/" + command + " now carries a default member permission. It is the"
                            + " command an ordinary member uses to link an account;"
                            + " restricting it makes soulbind unusable by the people it"
                            + " exists for.");
        }
    }

    @Test
    @DisplayName("the platform gate did not replace the connector's own")
    void theApplicationCheckSurvives() throws IOException {
        // A server owner can override a default permission per-role, so the
        // platform gate is a layer and not a floor. If somebody deletes the
        // in-connector check because "Discord handles it", this fails.
        String source = Files.readString(SourceTree.repoRoot().resolve(CONNECTOR));
        assertTrue(source.contains("isAdministrator()"),
                "ChatConnector no longer checks isAdministrator(). Discord's default"
                        + " permission can be overridden per-role by the server owner, so it"
                        + " is a layer rather than a floor, and this check is what holds when"
                        + " it is overridden.");
    }
}
