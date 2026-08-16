<?php

declare(strict_types=1);

/*
 * A PSR-4 autoloader, hand-written.
 *
 * Composer's would be better and needs `composer install`, which needs the
 * Flarum dependency tree -- hundreds of packages to run a suite that tests two
 * files and reads two text files. This keeps the vector suite runnable on a
 * machine that has PHP and nothing else, which is the machine most likely to be
 * asked to check whether the two implementations still agree.
 *
 * Composer's autoloader is preferred when it is present.
 */
$composer = __DIR__ . '/../vendor/autoload.php';
if (is_file($composer)) {
    require $composer;
    return;
}

spl_autoload_register(static function (string $class): void {
    foreach ([
        'Soulbind\\Flarum\\Tests\\' => __DIR__ . '/',
        'Soulbind\\Flarum\\' => __DIR__ . '/../src/',
    ] as $prefix => $base) {
        if (!str_starts_with($class, $prefix)) {
            continue;
        }
        $relative = substr($class, strlen($prefix));
        $file = $base . str_replace('\\', '/', $relative) . '.php';
        if (is_file($file)) {
            require $file;
            return;
        }
    }
});
