package app.sojourn.ui.flights

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flight
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sojourn.data.Countries
import app.sojourn.data.db.Flight
import app.sojourn.data.flights.AirportDirectory
import app.sojourn.data.flights.Airlines
import app.sojourn.ui.components.Format
import app.sojourn.ui.components.ShapeIcon
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit

/** The airport list, once loaded; screens show bare codes until then. */
val LocalAirports = staticCompositionLocalOf<AirportDirectory?> { null }

@Composable
fun airportPlace(code: String): String = LocalAirports.current?.get(code)?.place ?: code

fun formatMinute(minute: Int?): String? =
    minute?.let { LocalTime.of(it / 60, it % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)) }

/** "10:05 → 16:40 (+1)", or only the parts that are known. */
fun flightTimes(flight: Flight): String? {
    val depart = formatMinute(flight.departureMinute)
    val arrive = formatMinute(flight.arrivalMinute)
    val shift = ChronoUnit.DAYS.between(flight.departureDate, flight.arrivalDate).toInt()
    val days = when {
        shift > 0 -> " (+$shift)"
        shift < 0 -> " ($shift)"
        else -> ""
    }
    // No-break spaces keep a time range on one line instead of splitting at the arrow.
    val text = when {
        depart != null && arrive != null -> "$depart → $arrive$days"
        depart != null -> "Departs $depart"
        arrive != null -> "Arrives $arrive$days"
        else -> null
    }
    return text?.replace(' ', '\u00A0')
}

/** "In 5 days", "Tomorrow", "Today", or the date for flights already flown. */
fun flightWhen(flight: Flight, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(today, flight.departureDate).toInt()
    return when {
        days == 0 -> "Today"
        days == 1 -> "Tomorrow"
        days in 2..60 -> "In $days days"
        else -> Format.short(flight.departureDate, today)
    }
}

/** "🇵🇹 LIS → 🇨🇾 LCA" */
fun route(flight: Flight): String =
    "${Countries.flag(flight.originCountry)}\u00A0${flight.origin}  →  ${Countries.flag(flight.destinationCountry)}\u00A0${flight.destination}"

/** "DOE/JANE MRS", as airlines write a passenger, as "Jane Doe"; other spellings are kept. */
fun passengerName(passenger: String): String {
    val parts = passenger.trim().split('/')
    if (parts.size != 2) return passenger.trim()
    val given = parts[1].split(' ').filter { it.isNotBlank() && it.uppercase() !in TITLES }
    fun word(w: String) = w.lowercase().split('-').joinToString("-") { it.replaceFirstChar(Char::titlecase) }
    return (given + parts[0].split(' ').filter { it.isNotBlank() }).joinToString(" ", transform = ::word)
}

private val TITLES = setOf("MR", "MRS", "MS", "MISS", "MSTR", "DR", "PROF", "CHD", "INF")

/** "Seat 14C · Ref ABC123 · Jane Doe": what the booking adds, or null when nothing is known. */
fun bookingLine(flight: Flight): String? = listOfNotNull(
    flight.seat.ifBlank { null }?.let { "Seat $it" },
    flight.reference.ifBlank { null }?.let { "Ref $it" },
    flight.passenger.ifBlank { null }?.let(::passengerName),
).joinToString(" · ").ifEmpty { null }

/**
 * A flight as a row: route with flags, cities, number and times, and the booking details when
 * known. The one flight row of the app, for flights to come (when, in the accent colour) and
 * flights flown (their date, quieter).
 */
@Composable
fun FlightCard(flight: Flight, today: LocalDate, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surfaceContainerLow, onClick: (() -> Unit)? = null) {
    val past = flight.arrivalDate < today
    val label = flightWhen(flight, today)
    val times = flightTimes(flight)
    // The date only when the label doesn't already say it.
    val details = listOfNotNull(
        Airlines.format(flight.number).ifBlank { null },
        times,
        Format.short(flight.departureDate, today).takeIf { times == null && it != label },
    )
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(
                Icons.Rounded.Flight,
                size = 48.dp,
                shape = MaterialShapes.Cookie6Sided.toShape(),
                container = if (past) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer,
                content = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(route(flight), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (past) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
                }
                Text(
                    "${airportPlace(flight.origin)} → ${airportPlace(flight.destination)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (details.isNotEmpty()) {
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                bookingLine(flight)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    val shape = RoundedCornerShape(24.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = container, modifier = modifier.fillMaxWidth()) { content() }
    } else {
        Surface(shape = shape, color = container, modifier = modifier.fillMaxWidth()) { content() }
    }
}
