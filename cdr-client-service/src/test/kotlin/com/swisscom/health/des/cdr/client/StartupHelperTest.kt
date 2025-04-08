package com.swisscom.health.des.cdr.client

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

class StartupHelperTest {

    @ParameterizedTest
    @ValueSource(strings = ["dev", "test", "customer,dev"])
    fun `test check that no installation is required for the given profile`(profileName: String) {
        System.setProperty("spring.profiles.active", profileName)
        assertFalse(checkIfInstallationIsRequired(emptyArray()))
    }

    @Test
    fun `test check that no installation is required if --skip-install is set`() {
        assertFalse(checkIfInstallationIsRequired(arrayOf("--skip-installer")))
    }

    @Test
    fun `test check that no installation as additional-location file is set as property`() {
        val resourceDirectory = Paths.get("src", "test", "resources", "application-test.yaml")
        System.setProperty("spring.config.additional-location", resourceDirectory.absolutePathString())
        assertFalse(checkIfInstallationIsRequired(emptyArray()))
    }

    @Test
    fun `test check that installation is needed if nothing is set`() {
        assertTrue(checkIfInstallationIsRequired(emptyArray()))
    }

}
