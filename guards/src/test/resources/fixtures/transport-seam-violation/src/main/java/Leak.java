// Deliberately broken fixture. NOT compiled, NOT on any source set.
//
// It exists so the transport seam guard can be observed REJECTING something,
// rather than only observed passing on a tree that happens to be clean.
package dev.soulbind.core.policy;

import java.net.http.HttpClient;

final class Leak {
    private final HttpClient client = HttpClient.newHttpClient();

    boolean decide(String subject) {
        return client != null;
    }
}
