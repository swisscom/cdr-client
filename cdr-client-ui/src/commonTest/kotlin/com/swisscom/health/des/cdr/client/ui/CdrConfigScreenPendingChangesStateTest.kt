package com.swisscom.health.des.cdr.client.ui

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig as CdrClientConfigDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdrConfigScreenPendingChangesStateTest {

    @Test
    fun `initial config is not treated as loaded for EMPTY singleton`() {
        assertFalse(isInitialConfigLoaded(CdrClientConfigDto.EMPTY))
    }

    @Test
    fun `first loaded config becomes baseline`() {
        val loadedConfig = loadedConfig()

        val result = CdrConfigScreenPendingChangesState().observeConfig(
            config = loadedConfig,
            initialConfigLoaded = true,
            hasLocalConfigChanges = false,
        )

        assertEquals(loadedConfig, result.baselineConfig)
        assertFalse(result.awaitingApplyResult)
        assertFalse(result.awaitingResetResult)
    }

    @Test
    fun `observeConfig ignores unloaded config`() {
        val result = CdrConfigScreenPendingChangesState().observeConfig(
            config = CdrClientConfigDto.EMPTY,
            initialConfigLoaded = false,
            hasLocalConfigChanges = false,
        )

        assertNull(result.baselineConfig)
    }

    @Test
    fun `pending changes are detected from baseline difference`() {
        val baseline = loadedConfig()
        val changed = baseline.copy(localFolder = "/tmp/changed")
        val state = CdrConfigScreenPendingChangesState(baselineConfig = baseline)

        assertTrue(state.hasPendingChanges(changed, initialConfigLoaded = true, hasLocalConfigChanges = true))
        assertTrue(state.shouldShowPendingChangesBanner(changed, initialConfigLoaded = true, hasLocalConfigChanges = true))
    }

    @Test
    fun `pending changes disappear when config matches baseline again`() {
        val baseline = loadedConfig()
        val state = CdrConfigScreenPendingChangesState(baselineConfig = baseline)

        assertFalse(state.hasPendingChanges(baseline, initialConfigLoaded = true, hasLocalConfigChanges = true))
        assertFalse(state.shouldShowPendingChangesBanner(baseline, initialConfigLoaded = true, hasLocalConfigChanges = true))
    }

    @Test
    fun `reset click hides banner until config is reloaded`() {
        val baseline = loadedConfig()
        val changed = baseline.copy(localFolder = "/tmp/changed")
        val state = CdrConfigScreenPendingChangesState(baselineConfig = baseline)
            .onResetClicked()

        assertTrue(state.awaitingResetResult)
        assertFalse(state.shouldShowPendingChangesBanner(changed, initialConfigLoaded = true, hasLocalConfigChanges = true))
    }

    @Test
    fun `reloaded config after reset becomes new baseline and clears reset wait`() {
        val reloaded = loadedConfig().copy(localFolder = "/tmp/reloaded")
        val result = CdrConfigScreenPendingChangesState(
            baselineConfig = loadedConfig(),
            awaitingResetResult = true,
        ).observeConfig(
            config = reloaded,
            initialConfigLoaded = true,
            hasLocalConfigChanges = false,
        )

        assertEquals(reloaded, result.baselineConfig)
        assertFalse(result.awaitingResetResult)
        assertFalse(result.hasPendingChanges(reloaded, initialConfigLoaded = true, hasLocalConfigChanges = false))
    }

    @Test
    fun `apply click waits for applied config and then clears pending changes`() {
        val changed = loadedConfig().copy(localFolder = "/tmp/applied")
        val awaitingApply = CdrConfigScreenPendingChangesState(
            baselineConfig = loadedConfig(),
        ).onApplyClicked()

        assertTrue(awaitingApply.awaitingApplyResult)

        val result = awaitingApply.observeConfig(
            config = changed,
            initialConfigLoaded = true,
            hasLocalConfigChanges = false,
        )

        assertEquals(changed, result.baselineConfig)
        assertFalse(result.awaitingApplyResult)
        assertFalse(result.hasPendingChanges(changed, initialConfigLoaded = true, hasLocalConfigChanges = false))
    }

    @Test
    fun `error clears pending wait flags without changing baseline`() {
        val baseline = loadedConfig()
        val changed = baseline.copy(localFolder = "/tmp/changed")
        val result = CdrConfigScreenPendingChangesState(
            baselineConfig = baseline,
            awaitingApplyResult = true,
            awaitingResetResult = true,
        ).observeError(hasError = true)

        assertEquals(baseline, result.baselineConfig)
        assertFalse(result.awaitingApplyResult)
        assertFalse(result.awaitingResetResult)
        assertTrue(result.shouldShowPendingChangesBanner(changed, initialConfigLoaded = true, hasLocalConfigChanges = true))

    }

    @Test
    fun `external config refresh becomes new baseline when there are no local edits`() {
        val baseline = loadedConfig()
        val externalRefresh = baseline.copy(localFolder = "/tmp/external")

        val result = CdrConfigScreenPendingChangesState(baselineConfig = baseline).observeConfig(
            config = externalRefresh,
            initialConfigLoaded = true,
            hasLocalConfigChanges = false,
        )

        assertEquals(externalRefresh, result.baselineConfig)
        assertFalse(result.hasPendingChanges(externalRefresh, initialConfigLoaded = true, hasLocalConfigChanges = false))
        assertFalse(result.shouldShowPendingChangesBanner(externalRefresh, initialConfigLoaded = true, hasLocalConfigChanges = false))
    }

    @Test
    fun `config difference alone does not show pending changes without local edits`() {
        val baseline = loadedConfig()
        val externalRefresh = baseline.copy(localFolder = "/tmp/external")
        val state = CdrConfigScreenPendingChangesState(baselineConfig = baseline)

        assertFalse(state.hasPendingChanges(externalRefresh, initialConfigLoaded = true, hasLocalConfigChanges = false))
        assertFalse(state.shouldShowPendingChangesBanner(externalRefresh, initialConfigLoaded = true, hasLocalConfigChanges = false))
    }

    @Test
    fun `canEditConfig requires loaded config and online service`() {
        assertTrue(canEditConfig(true, DTOs.StatusResponse.StatusCode.SYNCHRONIZING))
        assertFalse(canEditConfig(false, DTOs.StatusResponse.StatusCode.SYNCHRONIZING))
        assertFalse(canEditConfig(true, DTOs.StatusResponse.StatusCode.OFFLINE))
    }

    private fun loadedConfig(): CdrClientConfigDto =
        CdrClientConfigDto.EMPTY.copy(
            localFolder = "/tmp/baseline",
            pullThreadPoolSize = 1,
            pushThreadPoolSize = 1,
        )
}

