package com.swisscom.health.des.cdr.client

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
@Tag("integration-test")
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

        @JvmStatic
        @BeforeAll
        fun showMsalTheWayToFakeMicrosoftHost() {
            val contextTls12: SSLContext = SSLContext.getInstance("TLSv1.2")
            val contextTls13: SSLContext = SSLContext.getInstance("TLSv1.3")
            val trustManager: Array<TrustManager> =
                arrayOf(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate?> = arrayOfNulls(0)
                    override fun checkClientTrusted(certificate: Array<X509Certificate?>?, str: String?) {
                        // noop
                    }
                    override fun checkServerTrusted(certificate: Array<X509Certificate?>?, str: String?) {
                        // noop
                    }
                }
                )
            contextTls12.init(null, trustManager, SecureRandom())
            contextTls13.init(null, trustManager, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(contextTls13.socketFactory)
        }
    }

}

