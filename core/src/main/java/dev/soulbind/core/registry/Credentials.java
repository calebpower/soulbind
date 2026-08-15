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

package dev.soulbind.core.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Connector credential minting and hashing.
 *
 * <p>A credential is a random 256-bit token. It is shown once, at the moment it
 * is created, and never again — only its hash is stored, so a database
 * disclosure yields nothing an attacker can present.
 */
public final class Credentials {

    /**
     * 256 bits.
     *
     * <p>Not a passphrase and not derived from one, which is why SHA-256 rather
     * than a password-hashing function is the right tool here. bcrypt/argon2
     * exist to make <em>guessing</em> expensive for low-entropy human secrets;
     * against a uniformly random 256-bit token, guessing is not the threat and
     * the work factor would only slow down every legitimate authenticated
     * request.
     */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Credentials() {
        throw new AssertionError("no instances");
    }

    /**
     * A freshly minted credential.
     *
     * @param plaintext show once, store never
     * @param hash what goes in the database
     */
    public record Minted(String plaintext, String hash) {}

    /** Mints a new credential. */
    public static Minted mint() {
        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        // URL-safe, unpadded: a credential ends up in config files, environment
        // variables and Authorization headers. Padding characters survive none
        // of those reliably.
        String plaintext = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        return new Minted(plaintext, hash(plaintext));
    }

    /** The stored form of a presented credential. */
    public static String hash(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("credential must not be empty");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(md.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Constant-time comparison of two stored hashes.
     *
     * <p>Lookup is by hash, so an attacker cannot usually steer this — but the
     * cost of being careful is a few nanoseconds and the cost of not being is a
     * class of attack that is hard to notice, so it is careful.
     */
    public static boolean hashesMatch(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
