@file:UseSerializers(InstantSerializer::class)

package com.swisscom.health.des.cdr.client.common

import kotlinx.serialization.Serializable
import java.time.Instant

import kotlinx.serialization.KSerializer
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

class DTOs {

    @Serializable
    data class StatusResponse(
        val statusCode: StatusCode,
        val errorCodes: List<String> = emptyList(),
    ) {

        enum class StatusCode {
            UNKNOWN,
            SYNCHRONIZING,
            DISABLED,
            STOPPED,
            ERROR,
            OFFLINE,
        }

        enum class ErrorCode(val code: String) {
            UNKNOWN("unknown"),
            CONFIGURATION_ERROR("configuration_error"),
        }
    }

    @Serializable
    data class ShutdownResponse(
        val shutdownScheduledOn: Instant = Instant.now(),
        val shutdownScheduledFor: Instant,
        val trigger: String,
        val exitCode: Int,
    )

}
