package com.swisscom.health.des.cdr.client

import org.junit.jupiter.api.extension.AnnotatedElementContext
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.io.TempDirFactory
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.exists

internal class AlwaysSameTempDirFactory : TempDirFactory {
    override fun createTempDirectory(elementContext: AnnotatedElementContext?, extensionContext: ExtensionContext?): Path =
        Path.of(System.getProperty("java.io.tmpdir"), "cdr-client-test-source")
            .also { basePath ->
                if (!basePath.exists()) basePath.createDirectory()
            }
            .also { basePath ->
                basePath.resolve("invoice-test-source").also { path -> if (!path.exists()) path.createDirectories() }
            }
            .also { basePath ->
                basePath.resolve("cdr_download").also { path -> if (!path.exists()) path.createDirectories() }
            }
}
