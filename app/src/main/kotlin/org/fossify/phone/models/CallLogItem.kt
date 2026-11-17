package org.fossify.phone.models

import org.fossify.commons.helpers.DAY_SECONDS

sealed class CallLogItem {
    data class Date(
        val timestamp: Long,
        val dayCode: String,
    ) : CallLogItem()

    fun getItemId(): Int {
        return when (this) {
            // 🎓 ITTC: the phone's own day, not the UTC one — in Bangladesh (UTC+6) "today" after
            // midnight and "yesterday" shared one UTC day, so two date rows had the same id and
            // the list stopped the app (RecyclerView: stable ids must be unique)
            is Date -> -((timestamp + java.util.TimeZone.getDefault().getOffset(timestamp)) / (DAY_SECONDS * 1000L)).toInt()
            is RecentCall -> id
        }
    }
}
