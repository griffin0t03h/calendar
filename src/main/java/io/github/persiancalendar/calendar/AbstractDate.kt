package io.github.persiancalendar.calendar

/**
 * Abstract class representing a date.
 *
 * @author Amir
 */
abstract class AbstractDate {
    // Concrete things
    val year: Int
    val month: Int
    val dayOfMonth: Int

    /* What JDN (Julian Day Number) means?
     *
     * From https://en.wikipedia.org/wiki/Julian_day:
     * Julian day is the continuous count of days since the beginning of the
     * Julian Period and is used primarily by astronomers, and in software for
     * easily calculating elapsed days between two events (e.g. food production
     * date and sell by date).
     */
    constructor(year: Int, month: Int, dayOfMonth: Int) {
        this.year = year
        this.month = month
        this.dayOfMonth = dayOfMonth
    }

    constructor(jdn: Long) {
        val result = fromJdn(jdn)
        this.year = result[0]
        this.month = result[1]
        this.dayOfMonth = result[2]
    }

    constructor(date: AbstractDate) : this(date.toJdn())

    // Things needed to be implemented by subclasses
    abstract fun toJdn(): Long

    protected abstract fun fromJdn(jdn: Long): IntArray

    override fun equals(obj: Any?): Boolean {
        if (obj == null || this::class != obj::class || obj !is AbstractDate) return false
        return year == obj.year && month == obj.month && dayOfMonth == obj.dayOfMonth
    }
}
