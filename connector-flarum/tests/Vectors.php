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

use RuntimeException;

/**
 * Reads the committed golden vector files.
 *
 * Deliberately a hand-written parser over a line-oriented format, mirroring the
 * Java consumer: the fewer dependencies a vector reader needs, the fewer
 * reasons there are for one side to read the file differently from the other.
 */
final class Vectors
{
    private function __construct()
    {
    }

    /**
     * @return list<array{line:int, fields:list<string>}>
     */
    public static function read(string $name, int $expectedFields): array
    {
        $file = self::repoRoot() . '/vectors/' . $name;

        if (!is_file($file)) {
            throw new RuntimeException(
                "vector file not found: {$file}. The vectors are the oracle proving two "
                . 'implementations agree; running the suite without them would be a green run '
                . 'that compared nothing.'
            );
        }

        $rows = [];
        $lines = file($file, FILE_IGNORE_NEW_LINES);
        if ($lines === false) {
            throw new RuntimeException("cannot read {$file}");
        }

        foreach ($lines as $index => $line) {
            if (trim($line) === '' || str_starts_with($line, '#')) {
                continue;
            }

            // No limit argument: trailing empty fields are significant, and
            // dropping a final empty expectation would turn "normalises to
            // nothing" into "has no expectation".
            $parts = explode("\t", $line);
            if (count($parts) !== $expectedFields) {
                throw new RuntimeException(
                    basename($file) . ':' . ($index + 1) . ' has ' . count($parts)
                    . " fields, expected {$expectedFields}"
                );
            }

            $rows[] = [
                'line' => $index + 1,
                'fields' => array_map(self::unescape(...), $parts),
            ];
        }

        if ($rows === []) {
            throw new RuntimeException("{$file} parsed to zero rows");
        }

        return $rows;
    }

    /** The literal four characters NULL mean absent, stated rather than inferred. */
    public static function isNull(string $field): bool
    {
        return $field === 'NULL';
    }

    private static function repoRoot(): string
    {
        $directory = __DIR__;
        while ($directory !== '/' && $directory !== '') {
            if (is_file($directory . '/settings.gradle.kts')) {
                return $directory;
            }
            $directory = dirname($directory);
        }
        throw new RuntimeException('no repository root above ' . __DIR__);
    }

    /** \uXXXX and the usual escapes to the characters they name. */
    private static function unescape(string $value): string
    {
        $out = '';
        $length = strlen($value);

        for ($i = 0; $i < $length; $i++) {
            if ($value[$i] !== '\\' || $i + 1 >= $length) {
                $out .= $value[$i];
                continue;
            }

            $next = $value[$i + 1];
            switch ($next) {
                case 't':
                    $out .= "\t";
                    $i++;
                    break;
                case 'n':
                    $out .= "\n";
                    $i++;
                    break;
                case 'r':
                    $out .= "\r";
                    $i++;
                    break;
                case '\\':
                    $out .= '\\';
                    $i++;
                    break;
                case 'u':
                    if ($i + 6 <= $length) {
                        $codepoint = hexdec(substr($value, $i + 2, 4));
                        // mb_chr with an explicit encoding: the whole point of
                        // these escapes is that the file carries no raw
                        // multi-byte data, so the decoding must not depend on
                        // an ambient default.
                        $out .= mb_chr((int) $codepoint, 'UTF-8');
                        $i += 5;
                    } else {
                        $out .= $value[$i];
                    }
                    break;
                default:
                    $out .= $value[$i];
            }
        }

        return $out;
    }
}
