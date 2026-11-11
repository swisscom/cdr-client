package com.swisscom.health.des.cdr.client.model

internal sealed interface UploadOutcome {
    data object Retry : UploadOutcome
    data object Uploaded : UploadOutcome
    data class Rejected(val code: Int) : UploadOutcome
    data class DeferredUntilRestart(val code: Int) : UploadOutcome
    data object FileBusy : UploadOutcome
}
