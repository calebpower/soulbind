package dev.soulbind.connector.plan;

import java.sql.PreparedStatement;

/** One package OUT from the exemption. Must be flagged. */
final class NotExempt {
    static final String SQL = "SELECT a FROM plan_sessions WHERE b = ?";
    PreparedStatement statement;
}
