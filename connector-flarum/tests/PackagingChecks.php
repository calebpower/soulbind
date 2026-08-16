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

namespace Soulbind\Flarum\Tests;

use Soulbind\Flarum\Client\CurlTransport;

/**
 * The extension's identity, as the HOST computes it.
 *
 * Flarum does not use the composer package name as the extension id. It splits
 * on the slash and strips a leading `flarum-ext-` or `flarum-` from the package
 * half:
 *
 *     soulbind/flarum-connector  ->  soulbind + connector  ->  soulbind-connector
 *
 * That is surprising, undocumented at the point of use, and invisible to every
 * check that does not involve a running forum. It cost four browser-tier
 * iterations: the harness enabled `soulbind-flarum-connector`, which is not an
 * id anything has, so the extension stayed disabled, its routes never
 * registered, and the webhook 404'd -- while composer, installed.json and
 * extend.php all looked perfect.
 *
 * The same wrong id was in the admin page, where it would have silently
 * registered settings against a nonexistent extension and shown an empty panel.
 *
 * So the rule is implemented here and asserted against the places that hardcode
 * an id. This is a static check on purpose: it runs on any machine with PHP,
 * whereas the failure it prevents needs a forum, a database and a browser.
 */
final class PackagingChecks
{
    private function __construct()
    {
    }

    /**
     * A refusal must be registered, or the person is told nothing.
     *
     * `KnownError` alone stops Flarum logging a refusal as a server fault. It
     * does NOT make Flarum render the reason: an unregistered error type falls
     * through to a generic 500, which the frontend shows as "Oops! Something
     * went wrong. Please reload the page and try again."
     *
     * So the gate refused correctly, with core's own wording attached, and the
     * person saw nothing. Every unit check passed -- they all assert the message
     * on the GateOutcome, and that message was right. The missing piece was the
     * last hop, and only a browser could see it.
     *
     * This is the cheap half of that lesson: extend.php must register a status
     * for exactly the type GateRefused reports.
     *
     * @return list<string>
     */
    public static function theRefusalTypeIsRegistered(): array
    {
        $failures = [];

        $extend = (string) file_get_contents(dirname(__DIR__) . '/extend.php');

        if (!str_contains($extend, 'ErrorHandling')) {
            $failures[] = 'extend.php registers no ErrorHandling extender, so a gate refusal '
                . 'falls through to a generic 500 and the person is shown "Oops! Something '
                . 'went wrong" instead of the reason they were refused.';
            return $failures;
        }

        // A HANDLER, not merely a status.
        //
        // Flarum resolves known error types before custom handlers, and the
        // known-type path builds a response with no details. A refusal
        // registered only by status therefore arrives as a bare code and the
        // frontend renders "Oops! Something went wrong" -- which is what it did.
        // Only a handler can attach the reason.
        if (!str_contains($extend, '->handler(GateRefused::class')) {
            $failures[] = 'extend.php does not register a HANDLER for GateRefused. A '
                . 'status alone produces a response with no details, so the person is '
                . 'refused without being told why.';
        }

        // Both types must be TRANSLATED, or Flarum falls back to a message
        // chosen by HTTP status -- "You do not have permission to do that" for a
        // 403, which is a lie when the truth is that core is unreachable.
        //
        // Flarum's frontend picks what a person reads from the error type and
        // ignores the detail in the body, so the distinction between "you are
        // not linked" and "we cannot check right now" survives the last hop only
        // if both types exist and both are translated.
        $locale = (string) file_get_contents(dirname(__DIR__) . '/locale/en.yml');
        foreach (['soulbind_gate_refused', 'soulbind_unavailable'] as $type) {
            if (!str_contains($locale, $type . ':')) {
                $failures[] = "the error type '{$type}' has no translation, so Flarum shows a "
                    . 'message chosen by status code instead of by cause';
            }
        }

        // Under core.lib.error, which is the only place Flarum looks for these.
        // An extension-scoped key would read correctly and do nothing.
        if (preg_match('/core:\s*\n\s*lib:\s*\n\s*error:/', $locale) !== 1) {
            $failures[] = 'the error translations are not under core.lib.error, which is the '
                . 'only place Flarum looks them up';
        }

        // And the exception must NOT be a KnownError, or Flarum resolves it on
        // the known path and never reaches the handler above.
        $refusalSource = (string) file_get_contents(
            dirname(__DIR__) . '/src/Listener/GateRefused.php'
        );
        if (preg_match('/implements\s+[^{]*KnownError/', $refusalSource) === 1) {
            $failures[] = 'GateRefused implements KnownError, so Flarum resolves it '
                . 'before consulting custom handlers and the handler never runs. The '
                . 'two cannot both be present.';
        }

        // Read from the SOURCE, never loaded.
        //
        // GateRefused implements a Flarum interface, so loading it needs Flarum on
        // the classpath -- and this runner exists precisely to work on a machine
        // that has PHP and nothing else. The first version referenced the constant
        // directly and died with a class-not-found inside the check, which would
        // have made the whole dependency-free suite unrunnable to test one string.
        $refusal = (string) file_get_contents(dirname(__DIR__) . '/src/Listener/GateRefused.php');
        if (preg_match("/const TYPE = '([^']+)'/", $refusal, $m) !== 1) {
            $failures[] = 'GateRefused declares no TYPE constant, so there is nothing '
                . 'for extend.php to register a status against';
        } elseif (trim($m[1]) === '') {
            $failures[] = 'GateRefused::TYPE is empty';
        }

        return $failures;
    }

    /**
     * The endpoint is the configured base plus the protocol's path.
     *
     * The same config value is handed to both connectors, so it has to mean the
     * same thing to both. The Java SDK builds
     * `trimTrailingSlash(coreUrl) + "/v1/rpc"`; this side treated the setting as
     * a complete endpoint and posted to the base URL.
     *
     * Core does not answer there. So every decide was an outage, every gate
     * failed closed, and the forum refused everybody with reason `unreachable`
     * -- while core sat answering the other connector perfectly. Nothing in this
     * suite could see it, because nothing in this suite opens a socket; the
     * harness saw it the moment it asked the gate a question.
     *
     * @return list<string>
     */
    public static function theEndpointMatchesTheProtocolPath(): array
    {
        $failures = [];

        $cases = [
            'https://core.example.com' => 'https://core.example.com/v1/rpc',
            // A trailing slash is what an operator pastes out of a browser, and
            // must not produce a doubled separator.
            'https://core.example.com/' => 'https://core.example.com/v1/rpc',
            'https://core.example.com///' => 'https://core.example.com/v1/rpc',
            // A base path is legitimate: core can sit behind a prefix.
            'https://example.com/soulbind' => 'https://example.com/soulbind/v1/rpc',
            'http://host:8477' => 'http://host:8477/v1/rpc',
        ];

        foreach ($cases as $configured => $expected) {
            $actual = (new CurlTransport((string) $configured, 2000))->endpoint();
            if ($actual !== $expected) {
                $failures[] = "the base '{$configured}' produced '{$actual}', expected "
                    . "'{$expected}'";
            }
        }

        // And the path itself is the protocol's, not a local invention.
        if (CurlTransport::RPC_PATH !== '/v1/rpc') {
            $failures[] = 'the RPC path is ' . CurlTransport::RPC_PATH
                . ", but docs/protocol.md says /v1/rpc. The path is part of the wire "
                . 'contract, and the two sides must agree about it.';
        }

        return $failures;
    }

    /**
     * Every translation key the frontend asks for must exist.
     *
     * This is the T3 message-key guard, extended to the extension as the plan
     * asks. It exists because a missing key does not fail: Flarum renders the
     * key itself, so a member sees
     * `soulbind-connector.forum.link.not_linked` where a sentence should be.
     *
     * That happened, and it happened for a reason worth stating: the locale
     * file declared the namespace `soulbind-flarum` while the frontend asked
     * for `soulbind-connector`. The namespace is the EXTENSION ID, which Flarum
     * derives by stripping `flarum-` from the package name -- the same trap that
     * had already cost four iterations at the `.for()` call, in a second file,
     * found only because a browser rendered the keys at me.
     *
     * @return list<string>
     */
    public static function everyMessageKeyExists(): array
    {
        $failures = [];

        $locale = (string) file_get_contents(dirname(__DIR__) . '/locale/en.yml');
        $namespace = self::flarumExtensionId(self::composerName());

        // The namespace must BE the extension id, or every key beneath it is
        // unreachable no matter how carefully it is spelled.
        if (preg_match('/^' . preg_quote($namespace, '/') . ':\s*$/m', $locale) !== 1) {
            $failures[] = "locale/en.yml does not declare the namespace '{$namespace}'. "
                . 'Flarum derives that from the package name, and keys under any other '
                . 'namespace resolve to nothing -- the frontend then renders the key itself '
                . 'where a sentence should be.';
        }

        // Gather what the frontend asks for.
        $asked = [];
        foreach (['forum', 'admin'] as $side) {
            foreach (glob(dirname(__DIR__) . "/js/src/{$side}/*.js") ?: [] as $file) {
                $js = (string) file_get_contents($file);

                // Comments stripped BEFORE matching.
                //
                // This file quotes Flarum's own error switch, translator call and
                // all, to explain why the forum bundle exists -- and the first
                // version of this guard read that quotation as a demand. It is the
                // same mistake the admin-id check made against its own explanatory
                // comment, reintroduced in a new guard three hundred lines away.
                //
                // A guard that cannot tell code from prose about the code gets
                // silenced rather than obeyed.
                $js = preg_replace('#/\*.*?\*/#s', '', $js) ?? $js;
                $js = preg_replace('#^\s*//.*$#m', '', $js) ?? $js;

                // Literal keys: trans('a.b.c')
                if (preg_match_all("/trans\(\s*['\"]([a-z0-9_.-]+)['\"]/i", $js, $m)) {
                    foreach ($m[1] as $key) {
                        $asked[$key] = basename($file);
                    }
                }
                // Template keys: trans(`ns.section.${key}`) -- the prefix is
                // checkable even when the leaf is not, and a wrong prefix is
                // exactly the failure this guard was written for.
                if (preg_match_all('/trans\(\s*`([a-z0-9_.-]+)\$\{/i', $js, $m)) {
                    foreach ($m[1] as $prefix) {
                        $asked[rtrim($prefix, '.') . '.*'] = basename($file);
                    }
                }
            }
        }

        if ($asked === []) {
            $failures[] = 'no translation keys were found in the frontend at all. Either the '
                . 'guard stopped matching, or the frontend stopped translating -- both are '
                . 'worth knowing, and a silent pass is worth nothing.';
        }

        foreach ($asked as $key => $file) {
            // Only keys in THIS extension's namespace. `core.*` belongs to
            // Flarum and legitimately will not be in this file; demanding it
            // here would force somebody to copy Flarum's translations in to
            // silence a guard, which is worse than the guard not existing.
            if (!str_starts_with($key, $namespace . '.')) {
                continue;
            }

            $isPrefix = str_ends_with($key, '.*');
            $path = explode('.', $isPrefix ? substr($key, 0, -2) : $key);

            // Walk the YAML by indentation. A parser would be better and needs
            // ext-yaml, which this runner deliberately does not require.
            $depth = 0;
            $found = true;
            foreach ($path as $segment) {
                $indent = str_repeat(' ', $depth * 2);
                if (preg_match('/^' . $indent . preg_quote($segment, '/') . ':/m', $locale) !== 1) {
                    $found = false;
                    break;
                }
                $depth++;
            }

            if (!$found) {
                $failures[] = "{$file} asks for '{$key}', which locale/en.yml does not define. "
                    . 'A missing key does not fail: Flarum renders the key itself, so a member '
                    . 'reads it instead of a sentence.';
            }
        }

        return $failures;
    }

    /** Flarum's own derivation, from `Flarum\Extension\Extension::assignId()`. */
    public static function flarumExtensionId(string $composerName): string
    {
        $parts = explode('/', $composerName, 2);
        if (count($parts) !== 2) {
            return $composerName;
        }
        [$vendor, $package] = $parts;
        $package = str_replace(['flarum-ext-', 'flarum-'], '', $package);
        return "{$vendor}-{$package}";
    }

    private static function composerName(): string
    {
        $json = json_decode(
            (string) file_get_contents(dirname(__DIR__) . '/composer.json'),
            true
        );
        return is_array($json) && is_string($json['name'] ?? null) ? $json['name'] : '';
    }

    /** @return list<string> */
    public static function theExtensionIdIsWhatFlarumWillCompute(): array
    {
        $failures = [];

        $name = self::composerName();
        if ($name === '') {
            return ['composer.json has no name, so no id can be derived from it'];
        }

        $id = self::flarumExtensionId($name);

        // The derivation must actually be doing something here, or this check
        // would pass while the rule it encodes went untested.
        $naive = str_replace('/', '-', $name);
        if ($id === $naive) {
            $failures[] = "the package name '{$name}' derives to '{$id}', which is also what "
                . 'naive slash-replacement gives. That is fine, but it means this check no '
                . 'longer exercises the stripping rule -- and the rule is the part that '
                . 'surprised us.';
        }

        // The admin page addresses the extension by id. Wrong, and the settings
        // panel is silently empty: no error, no missing file, just nothing.
        // The .for() ARGUMENT, not the file. Grepping the whole file matched the
        // comment that explains which id is wrong -- so the check failed on a
        // file that was correct, and its own explanation was the evidence
        // against it. A check that cannot tell code from prose about the code
        // will be silenced rather than obeyed.
        $adminJs = (string) file_get_contents(dirname(__DIR__) . '/js/src/admin/index.js');
        $declared = preg_match("/\\.for\\(\\s*'([^']+)'\\s*\\)/", $adminJs, $m) === 1
            ? $m[1]
            : null;

        if ($declared === null) {
            $failures[] = 'the admin page has no .for(...) call, so it registers nothing';
        } elseif ($declared !== $id) {
            $failures[] = "the admin page addresses the extension as '{$declared}', but "
                . "Flarum computes '{$id}' from the package name '{$name}'. Anything else "
                . 'registers settings against an extension that does not exist, and the '
                . 'panel renders empty with no error anywhere.';
        }

        return $failures;
    }

    /**
     * The derivation itself, against cases that pin each branch.
     *
     * @return list<string>
     */
    public static function theDerivationRuleIsRight(): array
    {
        $failures = [];

        $cases = [
            // The case that bit us.
            'soulbind/flarum-connector' => 'soulbind-connector',
            // The convention most Flarum extensions use.
            'acme/flarum-ext-widgets' => 'acme-widgets',
            // No prefix to strip: the id is just vendor-package.
            'acme/widgets' => 'acme-widgets',
            // Flarum's own bundled extensions.
            'flarum/tags' => 'flarum-tags',
            'flarum/flarum-ext-markdown' => 'flarum-markdown',
            // str_replace removes the SUBSTRING, so `flarum-flarum` loses its one
            // `flarum-` and leaves `flarum`. Pinned because it is the case where
            // an intuitive reading -- "strip the prefix" -- and the actual
            // implementation diverge, and a "tidier" rewrite would change it.
            'acme/flarum-flarum' => 'acme-flarum',
        ];

        foreach ($cases as $name => $expected) {
            $actual = self::flarumExtensionId($name);
            if ($actual !== $expected) {
                $failures[] = "'{$name}' derived to '{$actual}', expected '{$expected}'";
            }
        }

        return $failures;
    }
}
