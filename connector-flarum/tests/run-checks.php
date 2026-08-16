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

/*
 * Runs every check without PHPUnit.
 *
 * PHPUnit needs ext-xmlwriter, which is not present everywhere. The vectors are
 * the one claim that MUST be checkable on any machine with PHP -- a
 * cross-language oracle nobody can run is not an oracle -- and once a runner
 * exists for those, there is no reason for the rest of the suite to be less
 * runnable than its most important part.
 *
 * The assertions live in the *Checks classes and are shared with the PHPUnit
 * suite. This only decides how to report them.
 *
 *   php tests/run-checks.php            the ordinary run
 *   php tests/run-checks.php --hostile  under a non-UTF-8 internal encoding
 */

$hostile = in_array('--hostile', $argv, true);

if ($hostile) {
    mb_internal_encoding('ISO-8859-1');
    setlocale(LC_ALL, 'C');
    putenv('SOULBIND_HOSTILE_CHARSET=1');
} else {
    mb_internal_encoding('UTF-8');
}

require __DIR__ . '/autoload.php';

use Soulbind\Flarum\Tests\CacheChecks;
use Soulbind\Flarum\Tests\ClientChecks;
use Soulbind\Flarum\Tests\GateChecks;
use Soulbind\Flarum\Tests\WebhookChecks;
use Soulbind\Flarum\Tests\VectorChecks;

/*
 * Each check class, with the PHPUnit class that must also run it.
 *
 * Sharing the assertions removed the risk of two copies drifting and replaced
 * it with a smaller one: a check added to a *Checks class and wired into only
 * one runner. That failure is silent -- the suite still passes, and the missing
 * check looks like coverage. So the wiring is asserted rather than remembered,
 * below, for every class listed here.
 */
$suites = [
    'cross-language vectors' => [VectorChecks::class, __DIR__ . '/GoldenVectorTest.php'],
    'decision cache and fail mode' => [CacheChecks::class, __DIR__ . '/DecisionCacheTest.php'],
    'client: signing, outages and refusals' => [ClientChecks::class, __DIR__ . '/SoulbindClientTest.php'],
    'inbound webhook' => [WebhookChecks::class, __DIR__ . '/WebhookTest.php'],
    'register and post gates' => [GateChecks::class, __DIR__ . '/GateTest.php'],
];

echo 'soulbind checks (', $hostile ? 'HOSTILE charset' : 'ordinary', ")\n";
echo '  internal encoding: ', mb_internal_encoding(), "\n";

$totalFailures = 0;
$totalChecks = 0;
$unwired = [];

foreach ($suites as $suiteName => [$class, $phpunitFile]) {
    echo "\n", $suiteName, "\n";

    $reflection = new ReflectionClass($class);
    $checks = [];
    // isPublic() AND isStatic(), tested separately and deliberately.
    // getMethods() takes its filter as a bitmask that ORs: passing
    // IS_PUBLIC | IS_STATIC returns everything public OR static, which
    // includes the private static helpers these classes use to build
    // fixtures. The first version did that and tried to call one.
    foreach ($reflection->getMethods() as $method) {
        if (!$method->isPublic() || !$method->isStatic()) {
            continue;
        }
        $type = $method->getReturnType();
        if ($type instanceof ReflectionNamedType && $type->getName() === 'array') {
            $checks[] = $method->getName();
        }
    }

    if ($checks === []) {
        $unwired[] = $reflection->getShortName() . ' declares no checks at all, so listing it '
            . 'here reports coverage that does not exist';
    }

    $phpunit = is_file($phpunitFile) ? file_get_contents($phpunitFile) : '';
    if ($phpunit === '') {
        $unwired[] = 'no PHPUnit counterpart at ' . basename($phpunitFile);
    }

    sort($checks);
    foreach ($checks as $name) {
        $totalChecks++;

        if ($phpunit !== '' && !str_contains($phpunit, $reflection->getShortName() . "::{$name}(")) {
            $unwired[] = $reflection->getShortName() . "::{$name} is not run by "
                . basename($phpunitFile);
        }

        $failures = $class::$name();
        $totalFailures += count($failures);

        $label = preg_replace('/(?<!^)[A-Z]/', ' $0', $name);
        $label = strtolower((string) $label);

        if ($failures === []) {
            echo "  ok    {$label}\n";
            continue;
        }

        echo '  FAIL  ', $label, ' (', count($failures), ")\n";
        foreach ($failures as $failure) {
            echo '          ', $failure, "\n";
        }
    }
}

echo "\n";

if ($unwired !== []) {
    echo "  FAIL  every check runs from both entry points\n";
    foreach ($unwired as $line) {
        echo '          ', $line, "\n";
    }
    echo "\n", count($unwired), " wiring failures\n";
    exit(1);
}

echo '  ok    all ', $totalChecks, " checks run from both entry points\n\n";

if ($totalFailures > 0) {
    echo $totalFailures, " failures\n";
    exit(1);
}

echo "all ", $totalChecks, " checks pass\n";
