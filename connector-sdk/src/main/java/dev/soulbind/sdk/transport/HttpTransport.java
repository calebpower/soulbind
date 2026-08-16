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
package dev.soulbind.sdk.transport;

import dev.soulbind.protocol.RequestSigner;
import dev.soulbind.protocol.Wire;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * The signed request transport.
 *
 * <p>For connectors that cannot hold a socket open, and for those that simply
 * would rather not. Every request stands alone and is signed, so there is no
 * session to lose and nothing to reconnect.
 *
 * <p>This is one of the two classes in the project permitted to name an HTTP
 * type, and a guard asserts the rest do not. Everything it does beyond moving
 * bytes — deciding what a response means, what to do when there is none — lives
 * above the seam in {@link dev.soulbind.sdk.SoulbindClient}, where it can be
 * tested without a network.
 */
public final class HttpTransport implements Transport {

    private final HttpClient http;
    private final URI endpoint;
    private final String credential;
    private final Clock clock;
    private final Duration timeout;

    public HttpTransport(String coreUrl, String credential, Clock clock) {
        this(coreUrl, credential, clock, Duration.ofSeconds(10));
    }

    public HttpTransport(String coreUrl, String credential, Clock clock, Duration timeout) {
        this.endpoint = URI.create(trimTrailingSlash(coreUrl) + "/v1/rpc");
        this.credential = credential;
        this.clock = clock;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                // A connect timeout as well as a request timeout: without it, a
                // host that accepts the TCP connection and then says nothing
                // holds the caller for the OS default, which is minutes.
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String send(String requestBody) throws TransportException {
        long timestamp = clock.instant().getEpochSecond();
        String nonce = UUID.randomUUID().toString();

        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header(Wire.HEADER_AUTHORIZATION, "Bearer " + credential)
                    .header(Wire.HEADER_TIMESTAMP, String.valueOf(timestamp))
                    .header(Wire.HEADER_NONCE, nonce)
                    .header(
                            Wire.HEADER_SIGNATURE,
                            RequestSigner.sign(
                                    credential.getBytes(StandardCharsets.UTF_8),
                                    timestamp,
                                    nonce,
                                    requestBody))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            // A credential containing a character no header can carry, most
            // likely. A configuration problem, and reporting it as an outage
            // would send an operator to check the network.
            throw new TransportException("this request could not be built: " + e.getMessage(), e);
        }

        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // The body is returned WHATEVER the status. Core answers 200 with
            // the outcome in the envelope, so a non-200 came from something
            // else -- and the layer above decides what a non-envelope means,
            // because that decision is about the protocol rather than about
            // bytes.
            return response.body();
        } catch (IOException e) {
            throw new TransportException("could not reach core: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransportException("interrupted while calling core", e);
        }
    }

    @Override
    public boolean isConnected() {
        // Nothing is held open, so there is nothing to be disconnected from.
        // Reporting true is honest: whether core answers is discovered per
        // request, and pretending to know in advance would be a cached opinion
        // about a thing that changes.
        return true;
    }

    @Override
    public void close() {
        // HttpClient holds no resource this class owns.
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
