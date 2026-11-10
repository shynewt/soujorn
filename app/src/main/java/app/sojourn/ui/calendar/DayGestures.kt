package app.sojourn.ui.calendar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.toSize
import java.time.LocalDate

/**
 * Finds the day under a finger. Every day cell reports where it is, so the same gestures work
 * in the month pager and in the year views, whatever their layout.
 *
 * Pagers and lists reuse a cell for another date (the next month, the year before), and keep
 * cells that scrolled away composed and attached, but no longer placed. So each cell owns one
 * [Slot] that it moves to its current date and takes back when it goes, and only a slot that
 * was placed in the latest layout, inside the visible container, can be hit: a stale date
 * left under the finger once made a tap in one month edit another.
 */
class DayHitTest {
    private var container: LayoutCoordinates? = null
    private val slots = LinkedHashMap<LocalDate, Slot>()

    /** Which dates can be hit now; the month view only takes days of the month it shows. */
    var accepts: (LocalDate) -> Boolean = { true }

    class Slot {
        var coordinates: LayoutCoordinates? = null
        var placed = false
    }

    fun container(coordinates: LayoutCoordinates) {
        container = coordinates
    }

    /** Registers [slot] as the cell showing [date]; returns what to call when it stops showing it. */
    fun register(date: LocalDate, slot: Slot): () -> Unit {
        slots[date] = slot
        return { if (slots[date] === slot) slots.remove(date) }
    }

    fun at(position: Offset): LocalDate? {
        val root = container?.takeIf { it.isAttached } ?: return null
        val visible = Rect(Offset.Zero, root.size.toSize())
        if (!visible.contains(position)) return null
        return slots.entries.firstOrNull { (date, slot) ->
            val c = slot.coordinates
            accepts(date) && slot.placed && c != null && c.isAttached && root.localBoundingBoxOf(c).contains(position)
        }?.key
    }
}

/** What the calendar does with a tap, a long press, and a drag after it. */
class DayGestureCallbacks(
    val onTap: (LocalDate) -> Unit,
    val onLongPress: (LocalDate) -> Unit,
    val onDragTo: (LocalDate) -> Unit,
)

/**
 * Tap opens a day (or ends a range being picked); long press starts a range and dragging
 * stretches it. Swipes and scrolls that start on a day are left to the pager and lists.
 */
@Composable
fun Modifier.dayGestures(hits: DayHitTest, callbacks: DayGestureCallbacks): Modifier {
    val haptics = LocalHapticFeedback.current
    return this
        .onGloballyPositioned { hits.container(it) }
        .pointerInput(hits, callbacks) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = hits.at(down.position) ?: return@awaitEachGesture
                var up: PointerInputChange? = null
                var moved = false
                val pressedLong = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUp()) {
                            up = change
                            break
                        }
                        if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                            break
                        }
                    }
                } == null
                if (!pressedLong) {
                    up?.takeIf { !moved }?.let {
                        it.consume()
                        callbacks.onTap(start)
                    }
                    return@awaitEachGesture
                }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                callbacks.onLongPress(start)
                var last = start
                drag(down.id) { change ->
                    hits.at(change.position)?.takeIf { it != last }?.let {
                        last = it
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                        callbacks.onDragTo(it)
                    }
                    change.consume()
                }
            }
        }
}

/** Registers a day cell for hit testing, and gives screen readers the same two actions. */
@Composable
fun Modifier.dayCell(date: LocalDate, hits: DayHitTest, callbacks: DayGestureCallbacks, description: String): Modifier {
    val label = remember(description) { description }
    val slot = remember { DayHitTest.Slot() }
    DisposableEffect(date, hits) {
        val unregister = hits.register(date, slot)
        onDispose {
            slot.placed = false
            unregister()
        }
    }
    return this
        .onPlaced { slot.placed = true }
        .onGloballyPositioned {
            slot.coordinates = it
            slot.placed = true
        }
        .semantics(mergeDescendants = true) {
            contentDescription = label
            onClick(label = "Open day") {
                callbacks.onTap(date)
                true
            }
            onLongClick(label = "Select days from here") {
                callbacks.onLongPress(date)
                true
            }
        }
}
