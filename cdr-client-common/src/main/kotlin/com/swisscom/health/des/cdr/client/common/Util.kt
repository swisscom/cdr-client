package com.swisscom.health.des.cdr.client.common

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.walk

/**
 * Recursively searches for a file or directory with the given name, starting from the specified directory.
 * If the name cannot be found in the directory tree starting from the given directory, then the search gets
 * escalated to the parent directory, and so on, until the filesystem root is reached.
 *
 * @param fileName the name of the file or directory to search for
 * @param startDir the directory from which to start the search
 * @return a list of paths where the file or directory with the given name was found, or an empty list if it was not found.
 */
@Suppress("ReturnCount")
tailrec fun escalatingFind(fileName: String, startDir: Path): List<Path> {
    val found: List<Path> =
        if (!startDir.exists() || !startDir.isDirectory() || startDir.parent == null) {
            // Either the starting point is invalid or we have reached the filesystem root -> give up (and do not search starting from the filesystem root)
            return emptyList()
        } else {
            startDir.walk().filter { it.name == fileName }.toList()

        }
    if (found.isNotEmpty()) {
        return found
    } else {
        return escalatingFind(fileName, startDir.parent)
    }
}

tailrec fun getRootestCause(e: Throwable): Throwable =
    if (e.cause == null)
        e
    else {
        getRootestCause(e.cause!!)
    }
