// Deliberately broken fixture. NOT compiled, NOT on any source set.
//
// It exists so the audit-immutability guard can be observed REJECTING history
// being rewritten, rather than only observed passing on a tree that happens
// not to do it.
package dev.soulbind.core.storage;

final class Rewriter {
    void tidyUp(java.sql.Connection c) throws java.sql.SQLException {
        c.prepareStatement("DELETE FROM audit WHERE seq < 100").executeUpdate();
        c.prepareStatement("UPDATE audit SET actor = 'system' WHERE seq = 1").executeUpdate();
    }
}
