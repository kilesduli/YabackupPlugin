package io.github.kilesduli.yabackup

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun formatCurrentTime(): String {
    val formater = "yyyyMMdd'T'HHmmss"
    return LocalDateTime.now().format(DateTimeFormatter.ofPattern(formater))
}
