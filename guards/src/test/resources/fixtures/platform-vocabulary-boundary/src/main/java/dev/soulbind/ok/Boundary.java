package dev.soulbind.ok;

/**
 * BOUNDARY FIXTURE. Words that CONTAIN a forbidden word but are not one.
 *
 * If the guard fires here it is matching substrings, and a guard that fires on
 * an ordinary English word is one people suppress rather than obey.
 *
 * Every token below embeds a forbidden name without being it: planned,
 * explanation, planetary, javadoc, javascript, replanning.
 */
public final class Boundary {
    /** The planned behaviour, with an explanation. */
    public static final int PLANNED = 1;

    /** Planetary scale, per the javadoc, in javascript-adjacent replanning. */
    public static final int PLANETARY = 2;

    private Boundary() {}
}
