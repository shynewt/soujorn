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

/** A flight as a row: route with flags, cities and number, and when. */
@Composable
fun FlightCard(flight: Flight, today: LocalDate, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surfaceContainerLow, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ShapeIcon(Icons.Rounded.Flight, size = 48.dp, shape = MaterialShapes.Cookie6Sided.toShape())
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(route(flight), style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(flightWhen(flight, today), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "${airportPlace(flight.origin)} → ${airportPlace(flight.destination)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(Airlines.format(flight.number).ifBlank { null }, flightTimes(flight) ?: Format.short(flight.departureDate, today)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
