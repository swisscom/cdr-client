package com.swisscom.health.des.cdr.clientvm

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
internal class CdrClientVmApplicationTest {

    @Autowired
    private lateinit var cdrClientVmApplication: CdrClientVmApplication

    @Test
    fun `test that application is starting`(){
        assertNotNull(cdrClientVmApplication)
    }

}
