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
 * Runs the vector checks without PHPUnit.
 *
 * PHPUnit needs ext-xmlwriter, which is not present everywhere, and the
 * vectors are the one claim that must be checkable on any machine with PHP: a
 * cross-language oracle nobody can run is not an oracle.
 *
 * The assertions themselves live in VectorChecks and are shared with the
 * PHPUnit suite. This only decides how to report them.
 *
 *   php tests/run-vectors.php            the ordinary run
 *   php tests/run-vectors.php --hostile  under a non-UTF-8 internal encoding
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

use Soulbind\Flarum\Tests\VectorChecks;

$checks = [
    'hostility took effect' => VectorChecks::hostilityTookEffect(...),
    'normalisation vectors' => VectorChecks::normalisation(...),
    'signing vectors' => VectorChecks::signing(...),
    'normalisation is idempotent' => VectorChecks::idempotence(...),
    'the corpus is balanced' => VectorChecks::corpusShape(...),
    'folding cannot synthesise a code' => VectorChecks::foldingCannotSynthesise(...),
];

echo 'soulbind vector check (', $hostile ? 'HOSTILE charset' : 'ordinary', ")\n";
echo '  internal encoding: ', mb_internal_encoding(), "\n\n";

/*
 * Both entry points must run EVERY check.
 *
 * Sharing the assertions removed the risk of two copies drifting and replaced
 * it with a smaller one: a check added to VectorChecks and wired into only one
 * runner. That failure is silent -- the suite still passes, and the missing
 * check looks like coverage. So the wiring is asserted rather than remembered.
 */
$declared = [];
foreach (
    (new ReflectionClass(VectorChecks::class))
        ->getMethods(ReflectionMethod::IS_PUBLIC | ReflectionMethod::IS_STATIC) as $method
) {
    $type = $method->getReturnType();
    if ($type instanceof ReflectionNamedType && $type->getName() === 'array') {
        $declared[] = $method->getName();
    }
}

$wiredHere = [];
foreach ($checks as $closure) {
    $wiredHere[] = (new ReflectionFunction($closure))->getName();
}

$phpunit = file_get_contents(__DIR__ . '/GoldenVectorTest.php');
$unwired = [];
foreach ($declared as $name) {
    if (!in_array($name, $wiredHere, true)) {
        $unwired[] = "VectorChecks::{$name} is not run by run-vectors.php";
    }
    if (!str_contains($phpunit, "VectorChecks::{$name}(")) {
        $unwired[] = "VectorChecks::{$name} is not run by GoldenVectorTest.php";
    }
}

if ($unwired !== []) {
    echo "  FAIL  every check is wired into both entry points\n";
    foreach ($unwired as $line) {
        echo '          ', $line, "\n";
    }
    echo "\n", count($unwired), " failures\n";
    exit(1);
}
echo "  ok    all ", count($declared), " checks are wired into both entry points\n";

$total = 0;

foreach ($checks as $name => $check) {
    $failures = $check();
    $total += count($failures);

    if ($failures === []) {
        echo "  ok    {$name}\n";
        continue;
    }

    echo '  FAIL  ', $name, ' (', count($failures), ")\n";
    foreach ($failures as $failure) {
        echo '          ', $failure, "\n";
    }
}

echo "\n";

if ($total > 0) {
    echo "{$total} failures\n";
    exit(1);
}

echo "all vector checks pass\n";
