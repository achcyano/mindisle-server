package me.hztcm.mindisle.scale.service

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneOffset

internal fun utcNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)

internal fun LocalDateTime.toIsoInstant(): String = atOffset(ZoneOffset.UTC).toInstant().toString()

internal fun BigDecimal.roundScale2(): BigDecimal = setScale(2, RoundingMode.HALF_UP)
