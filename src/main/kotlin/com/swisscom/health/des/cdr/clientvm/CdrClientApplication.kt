package com.swisscom.health.des.cdr.clientvm

import com.swisscom.health.des.cdr.clientvm.config.CdrClientConfig
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling


/**
 * Spring Boot entry point
 */
@SpringBootApplication
@EnableConfigurationProperties(CdrClientConfig::class)
@EnableScheduling
class CdrClientApplication

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    runApplication<CdrClientApplication>(*args)
}
