package com.msmobile.aurum.util

import java.time.LocalDateTime

class DateTimeProvider {
    fun nowLocalDateTime(): LocalDateTime = LocalDateTime.now()
    fun currentTimeMillis(): Long = System.currentTimeMillis()
}
