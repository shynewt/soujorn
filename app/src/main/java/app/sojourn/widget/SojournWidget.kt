package app.sojourn.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.sojourn.MainActivity
import app.sojourn.container
import app.sojourn.data.Countries
import app.sojourn.domain.Level
import app.sojourn.domain.RuleStatus
import java.time.LocalDate
import kotlinx.coroutines.flow.first

private data class WidgetData(
    val country: String?,
    val streak: Int,
    val yearDays: Int,
    val year: Int,
    val countries: Int,
    val rule: RuleStatus?,
)

class SojournWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = context.container.repository
        val book = repository.currentBook()
        val current = book.current()
        val country = current?.last
        val year = LocalDate.now().year
        val summary = book.summary(LocalDate.of(year, 1, 1), book.today)
        // The rule closest to its limit is the one worth a glance.
        val rule = repository.statuses.first().filter { it.applies }.maxByOrNull { if (it.met) -1f else it.level.ordinal * 10 + it.progress }
        val data = WidgetData(
            country = country,
            streak = country?.let { book.streak(it) } ?: 0,
            yearDays = summary.countries.firstOrNull { it.country == country }?.days ?: 0,
            year = year,
            countries = summary.countries.count { it.days > 0 },
            rule = rule,
        )
        provideContent { GlanceTheme { Content(data) } }
    }

    @Composable
    private fun Content(data: WidgetData) {
        val wide = LocalSize.current.width >= WIDE.width
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(24.dp)
                .padding(16.dp)
                .clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val title = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val body = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp)
            if (data.country == null) {
                Text("🧭", style = TextStyle(fontSize = 30.sp))
                Spacer(GlanceModifier.height(6.dp))
                Text("Where are you?", style = title)
                Text("Open Sojourn to start", style = body)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Countries.flag(data.country), style = TextStyle(fontSize = 32.sp))
                Spacer(GlanceModifier.width(10.dp))
                Column {
                    Text(Countries.name(data.country), style = title, maxLines = 1)
                    Text(if (data.streak > 0) "Day ${data.streak}" else "Last seen here", style = body)
                }
            }
            Spacer(GlanceModifier.height(10.dp))
            Text("${data.yearDays} days here in ${data.year} · ${data.countries} countries", style = body, maxLines = 2)
            val rule = data.rule
            if (wide && rule != null) {
                Spacer(GlanceModifier.height(6.dp))
                val color = when (rule.level) {
                    Level.GOOD -> GlanceTheme.colors.primary
                    Level.CAUTION -> GlanceTheme.colors.tertiary
                    Level.ALERT -> GlanceTheme.colors.error
                }
                Text("${rule.rule.name}: ${rule.headline}", style = TextStyle(color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium), maxLines = 1)
            }
        }
    }

    companion object {
        private val SMALL = DpSize(110.dp, 110.dp)
        private val WIDE = DpSize(250.dp, 110.dp)

        suspend fun refresh(context: Context) {
            runCatching { SojournWidget().updateAll(context) }
        }
    }
}

class SojournWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SojournWidget()
}
