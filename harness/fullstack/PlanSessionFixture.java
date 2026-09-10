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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * One finished play session, written into the dashboard's own tables.
 *
 * <p>Run as a single-file source program against core's installed driver, the
 * same way {@code MigrationFingerprint.java} is. See {@code measures-check.sh}
 * for WHY a session has to be supplied here rather than played: the harness
 * runs the dashboard on the proxy only, and a proxy-side dashboard records no
 * sessions -- backends do. The estate runs it in both places, so there the
 * roster this fills is filled by the backends.
 *
 * <p><b>This is a fixture, not a fake of anything under test.</b> It writes one
 * row to a third party's table and stops. Everything the stage asserts on --
 * the roster read, the index the dashboard computes from this row, the report
 * to core, what core stores, and which identities it then emits transitions
 * for -- runs afterwards, unmodified, and is soulbind's own code.
 *
 * <p>Every foreign key is resolved rather than guessed, and every lookup that
 * comes back empty is a loud failure with the reason in it. Passing {@code NULL}
 * for the join address would work today -- the column carries a default -- and
 * was rejected for that reason: a fixture that is correct only while a third
 * party's schema happens to allow it fails as a mystery two versions from now.
 *
 * <pre>
 *   PlanSessionFixture &lt;url&gt; &lt;user&gt; &lt;password&gt; &lt;player-uuid&gt; &lt;seconds&gt; &lt;ended-seconds-ago&gt;
 * </pre>
 *
 * <p>Prints the identifiers it resolved and the row it wrote, because a fixture
 * that succeeds silently is one nobody can tell apart from one that did nothing.
 */
public final class PlanSessionFixture {

    private PlanSessionFixture() {
        throw new AssertionError("no instances");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) {
            System.err.println("usage: PlanSessionFixture <url> <user> <password>"
                    + " <player-uuid> <seconds> <ended-seconds-ago>");
            System.exit(3);
        }
        String url = args[0];
        String user = args[1];
        String password = args[2];
        String playerUuid = args[3];
        long seconds = Long.parseLong(args[4]);
        long endedAgo = Long.parseLong(args[5]);

        if (seconds <= 0) {
            fail("a session of " + seconds + "s is not a session");
        }

        long now = System.currentTimeMillis();
        long end = now - (endedAgo * 1000L);
        long start = end - (seconds * 1000L);

        try (Connection db = DriverManager.getConnection(url, user, password)) {
            // The player. Not created if absent: the stage waits on THIS uuid,
            // and inventing a row for it would make the fixture pass while the
            // thing that registers players had stopped working.
            int userId = id(db,
                    "SELECT id FROM plan_users WHERE uuid = ?", playerUuid,
                    "no plan_users row for " + playerUuid + "; the dashboard has not"
                            + " seen this player, so the stage is waiting on the wrong one");

            // The server. Any one: this row's purpose is to satisfy the foreign
            // key and to be visible to a roster query that does not filter on it.
            int serverId = id(db, "SELECT id FROM plan_servers LIMIT 1", null,
                    "no plan_servers row; the dashboard registered no server, which"
                            + " means it did not finish starting");

            // The join address. Created if the dashboard has not recorded one,
            // because on a proxy-only install it may never have.
            int joinAddressId = joinAddressId(db);

            try (PreparedStatement insert = db.prepareStatement(
                    "INSERT INTO plan_sessions"
                            + " (user_id, server_id, session_start, session_end,"
                            + "  mob_kills, deaths, afk_time, join_address_id)"
                            + " VALUES (?, ?, ?, ?, 0, 0, 0, ?)")) {
                insert.setInt(1, userId);
                insert.setInt(2, serverId);
                insert.setLong(3, start);
                insert.setLong(4, end);
                insert.setInt(5, joinAddressId);
                insert.executeUpdate();
            }

            // Read back rather than trust the update count. The row has to be
            // visible to the roster query -- which joins plan_users and bounds
            // on session_end -- and that is a different question from whether
            // an INSERT reported one row.
            try (PreparedStatement check = db.prepareStatement(
                    "SELECT COUNT(*) FROM plan_sessions s"
                            + " JOIN plan_users u ON u.id = s.user_id"
                            + " WHERE u.uuid = ? AND s.session_end > ?")) {
                check.setString(1, playerUuid);
                check.setLong(2, start - 1L);
                try (ResultSet rs = check.executeQuery()) {
                    rs.next();
                    int visible = rs.getInt(1);
                    if (visible < 1) {
                        fail("the session was written but the roster query cannot see it");
                    }
                    System.out.println("[measures] seeded a " + seconds + "s session for "
                            + playerUuid + " (user_id=" + userId + ", server_id=" + serverId
                            + ", join_address_id=" + joinAddressId + ")");
                    System.out.println("[measures] session_start=" + start
                            + " session_end=" + end + "; roster now sees " + visible);
                }
            }
        }
    }

    /** One identifier, or a loud failure that says what was missing. */
    private static int id(Connection db, String sql, String param, String absent)
            throws Exception {
        try (PreparedStatement select = db.prepareStatement(sql)) {
            if (param != null) {
                select.setString(1, param);
            }
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) {
                    fail(absent);
                }
                return rs.getInt(1);
            }
        }
    }

    /**
     * The join address row, created if the dashboard has not recorded one.
     *
     * <p>Unlike the player and the server, this one IS created when absent. It
     * carries no meaning the stage asserts on -- it exists because the column
     * is a foreign key -- and a proxy-side dashboard that has recorded no
     * session has had no reason to write one.
     */
    private static int joinAddressId(Connection db) throws Exception {
        try (Statement select = db.createStatement();
                ResultSet rs = select.executeQuery("SELECT id FROM plan_join_address LIMIT 1")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        try (PreparedStatement insert = db.prepareStatement(
                "INSERT INTO plan_join_address (join_address) VALUES (?)",
                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, "unknown");
            insert.executeUpdate();
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }
        return id(db, "SELECT id FROM plan_join_address LIMIT 1", null,
                "could not create a plan_join_address row");
    }

    private static void fail(String why) {
        System.err.println("[measures] " + why);
        System.exit(1);
    }
}
