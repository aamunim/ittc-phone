package org.fossify.phone.adapters

import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import org.fossify.commons.helpers.TAB_CALL_HISTORY
import org.fossify.commons.helpers.TAB_CONTACTS
import org.fossify.commons.helpers.TAB_FAVORITES
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.config
import org.fossify.phone.fragments.MyViewPagerFragment
import org.fossify.phone.helpers.ITTC_TAB_LEADS
import org.fossify.phone.helpers.ittcVisibleTabs

/**
 * The main screen's pages, one per tab kind in [kinds] (🎓 ITTC 3.8: Call History, Leads, Contacts,
 * Favorites — see ittcVisibleTabs). The kinds are fixed for the adapter's life, so the page count
 * never changes under the pager when the tabs shown are changed in Settings.
 */
class ViewPagerAdapter(
    val activity: SimpleActivity,
    private val kinds: List<Int> = ittcVisibleTabs(activity.config.showTabs),
) : PagerAdapter() {

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val layout = getFragment(position)
        val view = activity.layoutInflater.inflate(layout, container, false)
        container.addView(view)

        (view as MyViewPagerFragment<*>).apply {
            setupFragment(activity)
            // 🎓 ITTC 3.6: dragging its list folds the main screen's dial pad
            (activity as? org.fossify.phone.activities.MainActivity)?.ittcPageCreated(this)
        }

        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
        container.removeView(item as View)
    }

    override fun getCount() = kinds.size

    override fun isViewFromObject(view: View, item: Any) = view == item

    private fun getFragment(position: Int): Int = when (kinds.getOrNull(position) ?: kinds.last()) {
        TAB_CONTACTS -> R.layout.fragment_contacts
        TAB_FAVORITES -> R.layout.fragment_favorites
        TAB_CALL_HISTORY -> R.layout.fragment_recents
        ITTC_TAB_LEADS -> R.layout.ittc_fragment_leads
        else -> R.layout.ittc_fragment_leads
    }
}
