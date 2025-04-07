package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.scheduling.BaseUploadScheduler.Companion.DEFAULT_INITIAL_DELAY_MILLIS
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.scheduling.config.TaskExecutionOutcome.Status.NONE
import org.springframework.scheduling.config.TaskExecutionOutcome.Status.STARTED
import org.springframework.test.context.ActiveProfiles
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
        "client.idp-credentials.renew-credential-at-startup=true",
    ]
)
@ActiveProfiles("test")
@Tag("integration-test")
internal class CdrClientApplicationTest {

    @Autowired
    private lateinit var cdrClientApplication: CdrClientApplication

    @Autowired
    private lateinit var applicationContext: ConfigurableApplicationContext

    @Autowired
    private lateinit var scheduledTaskHolder: ScheduledTaskHolder

    @BeforeEach
    fun setup() {
        // The test is in a race condition with the code under test. We need to make sure that the event watcher task is
        // not yet started to be sure that when it starts, it picks up the configuration we injected above in the @SpykBean.
        val scheduleTasks =
            scheduledTaskHolder.scheduledTasks.filter { it.task.toString().endsWith("launchFileWatcher") } +
                    scheduledTaskHolder.scheduledTasks.filter { it.task.toString().endsWith("launchFilePoller") }
        assertEquals(2, scheduleTasks.size)
        assertTrue(scheduleTasks.all { it.task.lastExecutionOutcome.status == NONE }) {
            "we cannot be sure whether we won or lost the race against the event watcher task; so let's bail out to err on the safe side"
        }
        await().until { scheduleTasks.all { it.task.lastExecutionOutcome.status == STARTED } }
        // give the tasks some time to initialize
        Thread.sleep(1_000L)
    }

    @Test
    fun `test that application is starting`() {
        assertNotNull(cdrClientApplication)
        Thread.sleep(DEFAULT_INITIAL_DELAY_MILLIS + 1_000L)
        assertTrue(applicationContext.isRunning)
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

