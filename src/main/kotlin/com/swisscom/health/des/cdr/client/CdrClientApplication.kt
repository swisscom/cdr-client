package com.swisscom.health.des.cdr.client

import com.swisscom.health.des.cdr.client.config.CdrClientConfig
import org.springframework.boot.actuate.autoconfigure.scheduling.ScheduledTasksObservabilityAutoConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling


/**
 * Spring Boot entry point
 */
@SpringBootApplication(exclude = [ScheduledTasksObservabilityAutoConfiguration::class])
@EnableConfigurationProperties(CdrClientConfig::class)
@EnableScheduling
class CdrClientApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<CdrClientApplication>(*args)
}

internal tailrec fun getRootestCause(e: Throwable): Throwable =
    if (e.cause == null)
        e
    else {
        getRootestCause(e.cause!!)
    }
