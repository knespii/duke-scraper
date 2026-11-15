package cz.duke390.hlidac.ui

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private fun spaced(v: Int) = "%,d".format(v).replace(',', ' ').replace(' ', ' ')

fun fmtCzk(v: Int) = "${spaced(v)} Kč"
fun fmtEur(v: Int) = "${spaced(v)} €"
fun fmtKm(v: Int) = "${spaced(v)} km"

fun fmtWhen(iso: String): String = runCatching {
    LocalDateTime.parse(iso).format(DateTimeFormatter.ofPattern("d. M. HH:mm"))
}.getOrDefault(iso)
