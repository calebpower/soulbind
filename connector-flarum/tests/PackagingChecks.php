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
