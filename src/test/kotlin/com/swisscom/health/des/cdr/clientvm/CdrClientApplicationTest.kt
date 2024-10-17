package com.swisscom.health.des.cdr.clientvm

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Path

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
internal class CdrClientApplicationTest {

    @Autowired
    private lateinit var cdrClientApplication: CdrClientApplication

    @Test
    fun `test that application is starting`() {
        assertNotNull(cdrClientApplication)
    }

    private companion object {
        @TempDir(factory = AlwaysSameTempDirFactory::class)
        @JvmStatic
        @Suppress("unused")
        private lateinit var inflightDirInApplicationTestYaml: Path
    }

}
