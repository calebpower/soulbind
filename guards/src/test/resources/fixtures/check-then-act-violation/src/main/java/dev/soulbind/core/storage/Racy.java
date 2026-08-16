// Deliberately broken fixture. NOT compiled, NOT on any source set.
//
// It exists so the check-then-act guard can be observed REJECTING the shape,
// rather than only observed passing on a tree that happens not to contain it.
package dev.soulbind.core.storage;

final class Racy {
    void seen(Jdbc jdbc, String kind) {
        jdbc.write("racy.seen", c -> {
            try (var check = c.prepareStatement("SELECT 1 FROM thing WHERE kind = ?")) {
                check.setString(1, kind);
                try (var rs = check.executeQuery()) {
                    if (rs.next()) {
                        return null;
                    }
                }
            }
            try (var ps = c.prepareStatement("INSERT INTO thing (kind) VALUES (?)")) {
                ps.setString(1, kind);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
