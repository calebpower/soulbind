package dev.soulbind.broken;

/**
 * MUST-FAIL FIXTURE. Not compiled.
 *
 * No SQL here at all -- and it is still the seam leaking. A caller that branches
 * on which database is underneath has learned something it was the seam's job to
 * hide, and the branch will grow.
 */
public final class BackendBranch {
    public int pageSize(String backend) {
        if ("sqlite".equals(backend)) {
            return 1;
        }
        return 50;
    }

    private BackendBranch() {}
}
