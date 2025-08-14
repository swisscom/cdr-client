package com.swisscom.health.des.cdr.client.handler

import io.github.oshai.kotlinlogging.KLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * Helper function to check for each call if a path is a directory and writable.
 * This to prevent unexpected behaviour should access rights change during runtime.
 */
fun pathIsDirectoryAndWritable(path: Path, what: String, logger: KLogger): Boolean =
    when {
        !Files.isDirectory(path) -> {
            logger.error { "Configured path '$path' isn't a directory. Therefore no files are $what until a directory is configured." }
            false
        }

        !Files.isWritable(path) -> {
            logger.error { "Configured path '$path' isn't writable by running user. Therefore no files are $what until access rights are corrected." }
            false
        }

        else -> true
    }
