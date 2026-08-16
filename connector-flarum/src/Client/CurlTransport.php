<?php

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

declare(strict_types=1);

namespace Soulbind\Flarum\Client;

/**
 * The real transport. The only file in this connector that opens a socket.
 *
 * Everything interesting is in {@see SoulbindClient} and is tested against a
 * fake; this class holds the parts that can only be exercised against a real
 * network, and deliberately holds nothing else. Every decision here is about
 * failing quickly and unambiguously.
 */
final class CurlTransport implements Transport
{
    /** The path every soulbind request goes to. Part of the wire contract. */
    public const RPC_PATH = '/v1/rpc';

    private readonly string $endpoint;

    /**
     * @param string $coreUrl the BASE url, as configured -- not the endpoint.
     *
     * The path is appended here, exactly as the Java SDK does
     * (`trimTrailingSlash(coreUrl) + "/v1/rpc"`). It has to be the same on both
     * sides, because an operator configures both connectors with the same value
     * and reasonably expects it to mean the same thing.
     *
     * It did not. This side treated the setting as a complete endpoint and
     * posted to the base URL, where core does not answer -- so every decide was
     * an outage, every gate failed closed, and the forum refused everybody with
     * 'unreachable' while core sat there answering the other connector fine.
     */
    public function __construct(
        string $coreUrl,
        private readonly int $timeoutMs
    ) {
        $this->endpoint = rtrim($coreUrl, '/') . self::RPC_PATH;
    }

    /** Exposed so a check can assert it without opening a socket. */
    public function endpoint(): string
    {
        return $this->endpoint;
    }

    public function send(string $body, array $headers): string
    {
        $handle = curl_init();
        if ($handle === false) {
            throw new TransportException('could not initialise an HTTP client');
        }

        $encoded = [];
        foreach ($headers as $name => $value) {
            // A header value carrying CR or LF would let a caller inject
            // additional headers. Nothing here is caller-controlled today --
            // the credential comes from settings and the nonce is generated --
            // but "nothing today" is not a property, and the cost of refusing
            // is one comparison.
            if (preg_match('/[\r\n]/', $name . $value) === 1) {
                curl_close($handle);
                throw new TransportException("the {$name} header contains a line break");
            }
            $encoded[] = $name . ': ' . $value;
        }

        curl_setopt_array($handle, [
            CURLOPT_URL => $this->endpoint,
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => $body,
            CURLOPT_HTTPHEADER => $encoded,
            CURLOPT_RETURNTRANSFER => true,
            // Milliseconds, and applied to the WHOLE exchange, not just the
            // connect. A connect timeout alone lets a server accept the socket
            // and then hold it open forever, which is the hang this bound
            // exists to prevent.
            CURLOPT_TIMEOUT_MS => $this->timeoutMs,
            CURLOPT_CONNECTTIMEOUT_MS => $this->timeoutMs,
            // Redirects are NOT followed. A redirect would resend a signed body
            // to somewhere the signature was not computed for, and an endpoint
            // that moved is a configuration change an operator should make
            // deliberately -- not one a 302 makes for them.
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
            // Errors are returned, not printed. Anything written to stdout here
            // would land in the middle of a page the forum is rendering.
            CURLOPT_FAILONERROR => false,
        ]);

        $response = curl_exec($handle);
        $error = curl_error($handle);
        $status = (int) curl_getinfo($handle, CURLINFO_RESPONSE_CODE);
        curl_close($handle);

        if ($response === false || $error !== '') {
            throw new TransportException(
                $error === '' ? 'the request did not complete' : $error
            );
        }

        // A 5xx is core failing to answer, which is an OUTAGE -- the cache and
        // the fail mode should see it. A 4xx is core answering, and the envelope
        // inside carries the refusal, so it is passed through for the client to
        // read. Collapsing the two would turn a capability problem into an
        // intermittent fault, or an outage into a permanent denial.
        if ($status >= 500) {
            throw new TransportException("core answered HTTP {$status}");
        }

        return (string) $response;
    }
}
