package com.swisscom.health.des.cdr.client.ui

import com.swisscom.health.des.cdr.client.common.DTOs
import com.swisscom.health.des.cdr.client.common.DTOs.CdrClientConfig as CdrClientConfigDto

internal data class CdrConfigScreenPendingChangesState(
    val baselineConfig: CdrClientConfigDto? = null,
    val awaitingApplyResult: Boolean = false,
    val awaitingResetResult: Boolean = false,
) {
    fun observeConfig(
        config: CdrClientConfigDto,
        initialConfigLoaded: Boolean,
        hasLocalConfigChanges: Boolean,
    ): CdrConfigScreenPendingChangesState {
        if (!initialConfigLoaded) return this

        return when {
            baselineConfig == null -> copy(baselineConfig = config)
            awaitingApplyResult -> copy(
                baselineConfig = config,
                awaitingApplyResult = false,
            )
            awaitingResetResult -> copy(
                baselineConfig = config,
                awaitingResetResult = false,
            )
            !hasLocalConfigChanges && baselineConfig != config -> copy(baselineConfig = config)
            else -> this
        }
    }

    fun observeError(hasError: Boolean): CdrConfigScreenPendingChangesState =
        if (hasError) {
            copy(
                awaitingApplyResult = false,
                awaitingResetResult = false,
            )
        } else {
            this
        }

    fun onApplyClicked(): CdrConfigScreenPendingChangesState =
        copy(
            awaitingApplyResult = true,
            awaitingResetResult = false,
        )

    fun onResetClicked(): CdrConfigScreenPendingChangesState =
        copy(
            awaitingApplyResult = false,
            awaitingResetResult = true,
        )

    fun hasPendingChanges(
        currentConfig: CdrClientConfigDto,
        initialConfigLoaded: Boolean,
        hasLocalConfigChanges: Boolean,
    ): Boolean = hasLocalConfigChanges && initialConfigLoaded && baselineConfig != null && currentConfig != baselineConfig

    fun shouldShowPendingChangesBanner(
        currentConfig: CdrClientConfigDto,
        initialConfigLoaded: Boolean,
        hasLocalConfigChanges: Boolean,
    ): Boolean = hasPendingChanges(currentConfig, initialConfigLoaded, hasLocalConfigChanges) && !awaitingResetResult
}

internal fun isInitialConfigLoaded(config: CdrClientConfigDto): Boolean = config !== CdrClientConfigDto.EMPTY

internal fun canEditConfig(
    initialConfigLoaded: Boolean,
    status: DTOs.StatusResponse.StatusCode,
): Boolean = initialConfigLoaded && status.isOnlineCategory
