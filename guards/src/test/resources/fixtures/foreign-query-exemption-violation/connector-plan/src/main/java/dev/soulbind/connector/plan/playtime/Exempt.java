package dev.soulbind.connector.plan.playtime;

import java.sql.PreparedStatement;

/** Inside the exempt package. Must NOT be flagged. */
final class Exempt {
    static final String SQL = "SELECT a FROM plan_sessions WHERE b = ?";
    PreparedStatement statement;
}
