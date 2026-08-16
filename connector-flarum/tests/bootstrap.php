<?php

declare(strict_types=1);

// The ordinary run. UTF-8 everywhere, which is what a sane deployment has --
// and which is exactly why it cannot prove the code pins its encoding.
mb_internal_encoding('UTF-8');

require __DIR__ . '/autoload.php';
