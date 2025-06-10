package com.swisscom.health.des.cdr.client.handler

import com.swisscom.health.des.cdr.client.AlwaysSameTempDirFactory
import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.annotation.DirtiesContext.ClassMode
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Path

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.main.lazy-initialization=true",
        "spring.jmx.enabled=false",
        "client.idp-credentials.renew-credential=false",
    ]
)
@ActiveProfiles("test", "noPollingUploadScheduler", "noEventTriggerUploadScheduler", "noDownloadScheduler")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
internal class ClientSecretRenewalServiceConfigurationTest {

    @Value("\${client.idp-credentials.client-secret}")
    private lateinit var clientSecret: String

    @Autowired
    private lateinit var config: CdrClientConfig

    @Test
    fun `verify client secret is loaded from configuration with path 'client - idp-credentials - client-secret'`() {
        assertTrue(
            clientSecret.isNotBlank(),
            "no client secret was loaded from the property path `client.idp-credentials.client-secret`. If you rename the property you also must amend the " +
                    "property path in `com.swisscom.health.des.cdr.client.handler.ClientSecretRenewalService` and its unit test. Once all is done, amend " +
                    "the path in this test."
        )
        assertTrue(config.idpCredentials.clientSecret.value == clientSecret)
        assertTrue(clientSecret == "test-client-secret")
    }

    private companion object {

        @TempDir(factory = AlwaysSameTempDirFactory::class)
        @JvmStatic
        @Suppress("unused")
        private lateinit var inflightDirInApplicationTestYaml: Path

    }

}
