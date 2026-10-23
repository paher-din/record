package com.paherdin.record2.records

import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
internal data class ResolvedFinanceTime(val occurredAt: Long, val zoneId: String, val offsetSeconds: Int)

internal object FinanceTime {
    fun resolve(localDateTime: String, zoneId: String, clock: Clock): ResolvedFinanceTime = try {
        val local = LocalDateTime.parse(localDateTime)
        val zone = ZoneId.of(zoneId)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.isNotEmpty() && local.nano % 1_000_000 == 0)
        val offset = offsets.first()
        val instant = local.toInstant(offset)
        require(!instant.isAfter(clock.instant()))
        ResolvedFinanceTime(instant.toEpochMilli(), zone.id, offset.totalSeconds)
    } catch (error: RuntimeException) {
        throw FinanceException(FinanceError.Validation, error)
    }
}
