<?php

declare(strict_types=1);

/*
 * The hostile run.
 *
 * A non-UTF-8 internal encoding and a non-UTF-8 locale. Any function that
 * relies on the ambient default rather than stating its encoding behaves
 * differently here -- which is the point: under a friendly default the vector
 * suite passes whether or not the code pins anything.
 *
 * ISO-8859-1 specifically, because it is the classic default and because a
 * multi-byte UTF-8 sequence read as Latin-1 becomes several characters rather
 * than failing -- silent corruption rather than a loud error, which is the
 * failure mode worth catching.
 */
mb_internal_encoding('ISO-8859-1');
setlocale(LC_ALL, 'C');
putenv('SOULBIND_HOSTILE_CHARSET=1');

require __DIR__ . '/autoload.php';
