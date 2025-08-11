package com.swisscom.health.des.cdr.client

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.scheduling.config.TaskExecutionOutcome.Status.STARTED
import org.springframework.scheduling.config.TaskExecutionOutcome.Status.SUCCESS
import org.springframework.test.context.ActiveProfiles
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.jmx.enabled=false",
        "client.idp-credentials.renew-credential=true",
    ]
)
@ActiveProfiles("test")
@Tag("integration-test")
internal class CdrClientApplicationTest {

    @Autowired
    private lateinit var cdrClientApplication: CdrClientApplication

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

    @Test
    fun `test that application is starting`() {
        assertNotNull(cdrClientApplication)
        val scheduleTasks =
            scheduledTaskHolder.scheduledTasks.filter { it.task.toString().endsWith("launchFileWatcher") } +
                    scheduledTaskHolder.scheduledTasks.filter { it.task.toString().endsWith("launchFilePoller") }
        assertEquals(2, scheduleTasks.size)
        // those two scheduled tasks never finish and thus remain in the STARTED state
        await().until { scheduleTasks.all { it.task.lastExecutionOutcome.status == STARTED } }

        // the secret renewal task is scheduled to run immediately as `application-test.yaml` sets the last-updated timestamp to the start of the Unix epoch
        val secretRenewalTask = scheduledTaskHolder.scheduledTasks.first { it.task.toString().endsWith("renewClientSecret") }
        await().until { secretRenewalTask.task.lastExecutionOutcome.status == SUCCESS }
        // successful secret renewal triggers a shutdown of the application context;
        // but the call to the mockserver fails, and thus no restart gets triggered
    }

    private companion object {
        @TempDir(factory = AlwaysSameTempDirFactory::class)
        @JvmStatic
        @Suppress("unused")
        private lateinit var inflightDirInApplicationTestYaml: Path

        @JvmStatic
        @BeforeAll
        fun createDirs() {
            Files.createDirectories(inflightDirInApplicationTestYaml.resolve("cdr_download"))
        }

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

