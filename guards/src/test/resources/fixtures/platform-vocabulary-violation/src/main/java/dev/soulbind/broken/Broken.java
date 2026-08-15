package dev.soulbind.broken;

/**
 * MUST-FAIL FIXTURE. Not compiled, not shipped, not a template.
 *
 * This file exists so the platform vocabulary guard can be observed failing.
 * A guard never seen to fail has unmeasured value (methodology §2).
 */
public final class Broken {
    // The violation: core-side code naming a specific platform.
    private static final String KIND = "discord";

    private Broken() {}
}
