package org.fossify.phone.extensions

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// 🎓 ITTC 3.13: one calendar per thread, not a new one (and a date formatter) for every call row.
//   Every RecentCall works out its day when it is made or copied — thousands of times while the
//   call log loads, which a 2 GB phone feels. As toDayCode("yyyy-MM-dd") in the phone's own time
//   zone, always with the digits 0–9 (the day code is only compared with other day codes).
private val dayCalendar = object : ThreadLocal<Calendar>() {
    override fun initialValue(): Calendar = Calendar.getInstance(Locale.ENGLISH)
}

fun Long.getDayCode(): String {
    // format helps with sorting in call log
    val cal = dayCalendar.get()!!
    // (the phone's time zone now, as a new calendar would take it — it may have changed)
    val zone = TimeZone.getDefault()
    if (cal.timeZone != zone) cal.timeZone = zone
    cal.timeInMillis = this
    val y = cal.get(Calendar.YEAR)
    val m = cal.get(Calendar.MONTH) + 1
    val d = cal.get(Calendar.DAY_OF_MONTH)
    val sb = StringBuilder(10)
    sb.append(y.toString().padStart(4, '0')).append('-')
    if (m < 10) sb.append('0')
    sb.append(m).append('-')
    if (d < 10) sb.append('0')
    sb.append(d)
    return sb.toString()
}
