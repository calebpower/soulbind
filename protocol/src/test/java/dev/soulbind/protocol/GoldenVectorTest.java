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

package dev.soulbind.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The golden vectors, consumed from Java.
 *
 * <p>These files are the oracle proving the Java and PHP implementations of the
 * protocol agree. Neither side generates them: they are committed, produced by
 * a third tool, and both implementations are checked against them. A vector
 * suite that regenerated its expectations would prove only that the code agrees
 * with itself.
 *
 * <p><b>Tagged {@code charset}</b>, so the whole suite runs a second time under
 * a non-UTF-8 default charset. That second run is the point: normalisation and
 * signing both claim to be encoding-independent, and on this JVM the ordinary
 * run cannot tell the difference.
 *
 * <p>What these do NOT prove: that the PHP implementation agrees. Only the PHP
 * consumer, reading the same files, carries that claim — and it arrives with
 * the forum connector.
 */
@Tag("charset")
class GoldenVectorTest {

    @Test
    @DisplayName("every link-code normalisation vector holds")
    void linkCodeNormalisation() {
        assertHostileCharsetTookEffectWhenAsked();

        List<Vectors.Row> rows = Vectors.read("link-code-normalisation.tsv", 2);
        for (Vectors.Row row : rows) {
            String raw = row.field(0);
            Optional<String> actual = LinkCode.normalise(raw);

            if (row.isNull(1)) {
                assertTrue(
                        actual.isEmpty(),
                        () -> "line " + row.line() + ": '" + visible(raw)
                                + "' should be REJECTED but normalised to '"
                                + actual.orElse("") + "'. Repairing a code is worse than "
                                + "refusing it: it silently redeems a different one.");
            } else {
                assertEquals(
                        row.field(1),
                        actual.orElse(null),
                        () -> "line " + row.line() + ": '" + visible(raw) + "'");
            }
        }
    }

    @Test
    @DisplayName("every HMAC signing vector holds")
    void hmacSigning() {
        assertHostileCharsetTookEffectWhenAsked();

        List<Vectors.Row> rows = Vectors.read("hmac-signing.tsv", 5);
        for (Vectors.Row row : rows) {
            byte[] key = row.field(0).getBytes(StandardCharsets.UTF_8);
            long timestamp = Long.parseLong(row.field(1));
            String nonce = row.field(2);
            String body = row.isNull(3) ? null : row.field(3);
            String expected = row.field(4);

            assertEquals(
                    expected,
                    RequestSigner.sign(key, timestamp, nonce, body),
                    () -> "line " + row.line() + ": signature disagrees with the committed "
                            + "vector. Either the canonical form changed -- which is a wire "
                            + "break with the other implementation -- or the vector is wrong. "
                            + "Do not update the vector to match the code without deciding "
                            + "which.");

            assertTrue(
                    RequestSigner.verify(key, timestamp, nonce, body, expected),
                    () -> "line " + row.line() + ": signing agrees with the vector but "
                            + "verifying does not, so the two disagree with each other");
        }
    }

    @Test
    @DisplayName("the signing vectors are distinct -- a constant signature would pass otherwise")
    void signingVectorsAreDistinct() {
        // Without this, an implementation returning one fixed string would fail
        // every row for a visible reason -- but a REDUCED corpus, or a future
        // refactor comparing only the first row, would pass vacuously. Stating
        // the distinctness makes the corpus's coverage part of the assertion.
        List<Vectors.Row> rows = Vectors.read("hmac-signing.tsv", 5);
        Set<String> signatures = new HashSet<>();
        for (Vectors.Row row : rows) {
            assertTrue(
                    signatures.add(row.field(4)),
                    () -> "line " + row.line() + " repeats an earlier signature");
        }
        assertTrue(rows.size() >= 10, () -> "only " + rows.size() + " signing vectors");
    }

    @Test
    @DisplayName("the normalisation corpus covers both outcomes, in quantity")
    void normalisationCorpusIsBalanced() {
        // A corpus of forty accepted inputs and no rejected ones would pass with
        // rejection deleted entirely.
        List<Vectors.Row> rows = Vectors.read("link-code-normalisation.tsv", 2);
        long rejected = rows.stream().filter(r -> r.isNull(1)).count();
        long accepted = rows.size() - rejected;

        assertTrue(rejected >= 10, () -> "only " + rejected + " rejection vectors");
        assertTrue(accepted >= 10, () -> "only " + accepted + " acceptance vectors");
    }

    @Test
    @DisplayName("normalisation is idempotent -- normalising twice changes nothing")
    void normalisationIsIdempotent() {
        // The property that makes it safe to store the normalised form and
        // compare against a freshly normalised input. If it were not idempotent,
        // a code would stop matching itself after a round trip through storage.
        for (Vectors.Row row : Vectors.read("link-code-normalisation.tsv", 2)) {
            if (row.isNull(1)) {
                continue;
            }
            String once = row.field(1);
            assertEquals(
                    Optional.of(once),
                    LinkCode.normalise(once),
                    () -> "line " + row.line() + ": '" + once + "' does not survive a second "
                            + "normalisation, so a stored code would stop matching itself");
        }
    }

    private static String visible(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Asserts the charset-hostility run is really hostile.
     *
     * <p>The whole reason this suite is tagged: on this JVM the default charset
     * is UTF-8, so an encoding-independence claim cannot fail in the ordinary
     * run. If a future JDK ignores {@code file.encoding}, the second run would
     * silently become a duplicate of the first.
     */
    private static void assertHostileCharsetTookEffectWhenAsked() {
        if (!Boolean.getBoolean("soulbind.hostileCharset")) {
            return;
        }
        assertNotEquals(
                StandardCharsets.UTF_8,
                Charset.defaultCharset(),
                "the charset-hostility task asked for a non-UTF-8 default charset and did not "
                        + "get one, so this run proves nothing the ordinary run did not");
    }
}
