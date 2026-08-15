package dev.soulbind.broken;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * MUST-FAIL FIXTURE. Not compiled.
 *
 * A module outside the storage package reaching for JDBC directly. It works,
 * which is the problem: the next change assumes this database, and the second
 * backend stops being real without anyone noticing.
 */
public final class LeakySql {
    public void countSubjects(Connection c) throws Exception {
        PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM subject");
        ps.executeQuery();
    }

    private LeakySql() {}
}
