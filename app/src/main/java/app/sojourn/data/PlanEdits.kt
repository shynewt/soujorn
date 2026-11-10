package app.sojourn.data

import app.sojourn.data.db.Plan
import java.time.LocalDate

/** How a range of days is changed in the calendar. */
enum class RangeEdit { SET, ADD, REMOVE, CLEAR }

/**
 * Pure plan arithmetic for [Repository.editRange]: cutting days out of plans, adding a plan,
 * and joining plans that touch, worked out as rows to delete and rows to write.
 */
object PlanEdits {
    data class Change(val removed: List<Long>, val added: List<Plan>) {
        operator fun plus(other: Change): Change {
            // A plan cut twice keeps only what both cuts left, so the second cut works on the
            // first one's pieces; callers pass disjoint country filters, so a plain union is enough.
            return Change((removed + other.removed).distinct(), added + other.added)
        }
    }

    /**
     * Cuts [from]..[to] out of every plan for [country] (or every plan, cleared ones too, when
     * it's null), keeping the pieces before and after. [except] leaves one country's plans alone.
     */
    fun remove(plans: List<Plan>, from: LocalDate, to: LocalDate, country: String?, except: String? = null): Change {
        if (from > to) return Change(emptyList(), emptyList())
        val removed = mutableListOf<Long>()
        val added = mutableListOf<Plan>()
        for (plan in plans) {
            if (country != null && (plan.country != country || plan.cleared)) continue
            if (except != null && plan.country == except && !plan.cleared) continue
            if (plan.end < from || plan.start > to) continue
            removed += plan.id
            if (plan.start < from) added += plan.copy(id = 0, end = from.minusDays(1))
            if (plan.end > to) added += plan.copy(id = 0, start = to.plusDays(1))
        }
        return Change(removed, added)
    }

    fun add(plans: List<Plan>, from: LocalDate, to: LocalDate, country: String): Change =
        remove(plans, from, to, country) + Change(emptyList(), listOf(Plan(country = country, start = from, end = to)))

    /** Cleared plans over each run of consecutive [days]. */
    fun clear(days: List<LocalDate>): Change {
        val runs = mutableListOf<Plan>()
        for (day in days.sorted()) {
            val last = runs.lastOrNull()
            if (last != null && last.end.plusDays(1) == day) runs[runs.lastIndex] = last.copy(end = day)
            else runs += Plan(country = "", start = day, end = day, cleared = true)
        }
        return Change(emptyList(), runs)
    }

    /** Joins plans of the same country that touch or overlap, keeping the first one's note if they differ. */
    fun merge(plans: List<Plan>): Change {
        val removed = mutableListOf<Long>()
        val added = mutableListOf<Plan>()
        plans.groupBy { it.country to it.cleared }.values.forEach { group ->
            val sorted = group.sortedBy { it.start }
            var current = sorted.firstOrNull() ?: return@forEach
            var changed = false
            for (next in sorted.drop(1)) {
                val joinable = next.start <= current.end.plusDays(1) && (next.note.isBlank() || current.note.isBlank() || next.note == current.note)
                if (joinable) {
                    removed += next.id
                    current = current.copy(end = maxOf(current.end, next.end), note = current.note.ifBlank { next.note })
                    changed = true
                } else {
                    if (changed) {
                        removed += current.id
                        added += current
                    }
                    current = next
                    changed = false
                }
            }
            if (changed) {
                removed += current.id
                added += current
            }
        }
        return Change(removed, added.map { it.copy(id = 0) })
    }
}
