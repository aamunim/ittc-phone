package org.fossify.phone.helpers

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcItemContactBinding

/**
 * 🎓 ITTC 3.8: the Contacts tab's look — each letter's contacts in one rounded card under its letter
 * (drawn by [Headers], so adapter positions and the fast scroller stay as they were), the photo or
 * the initials, "Mobile · 01712 345678", and a "Lead" pill on an ITTC lead's number.
 * Only ContactsFragment passes one to ContactsAdapter; every other list keeps the old rows.
 * A row's shapes are made once with the row ([onCreateRow]); a bind only changes their colours.
 */
class IttcContactRows(private val context: Context) {
    var palette: IttcTheme.Palette = IttcTheme.of(context)
        private set

    /** Contact rawId → its lead (filled off the main thread, see [leadsOf]) */
    var leads: Map<Int, IttcBridge.Lead> = emptyMap()

    /** Letter cards with headers (off while searching or with a sort that is not by name: one card) */
    var sections = true

    private class Avatar(val initials: String, val bg: Int, val fg: Int)

    private val avatarCache = HashMap<String, Avatar>()
    private val stageCache = HashMap<String, Pair<Int, Int>>()

    /** The theme changed: colours again */
    fun refreshPalette() {
        palette = IttcTheme.of(context)
        avatarCache.clear()
        stageCache.clear()
    }

    private fun dp(v: Float): Int = IttcTheme.dp(context, v)

    // ----------------------------------------------------------------------------- sections
    // (each row's letter, worked out once when first needed; a new list starts again)
    private var lettersOf: List<Contact>? = null
    private var letters: Array<String?> = arrayOfNulls(0)

    fun letterAt(list: List<Contact>, position: Int): String {
        if (list !== lettersOf || letters.size != list.size) {
            lettersOf = list
            letters = arrayOfNulls(list.size)
        }
        letters[position]?.let { return it }
        val first = list[position].getFirstLetter()
        // (digits, "+" and other signs: one "#" card)
        val letter = if (first.isEmpty() || !first[0].isLetter()) "#" else first
        letters[position] = letter
        return letter
    }

    fun isSectionStart(list: List<Contact>, position: Int): Boolean =
        position == 0 || (sections && letterAt(list, position) != letterAt(list, position - 1))

    fun isSectionEnd(list: List<Contact>, position: Int): Boolean =
        position >= list.size - 1 || (sections && letterAt(list, position) != letterAt(list, position + 1))

    // ----------------------------------------------------------------------------- rows
    class RowState(
        val card: GradientDrawable,
        val avatarBg: GradientDrawable,
        val pillBg: GradientDrawable,
        val photoPlaceholder: GradientDrawable,
    ) {
        var cardColor = 0
        var corners: FloatArray? = null
        var hasPhoto = false
        var pillColor = 0
    }

    private val radius = dp(16f).toFloat()
    private val cornersAll = FloatArray(8) { radius }
    private val cornersTop = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
    private val cornersBottom = floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius)
    private val cornersNone = FloatArray(8)

    /** A new row: its shapes, made once */
    fun onCreateRow(b: IttcItemContactBinding) {
        val state = RowState(
            card = GradientDrawable(),
            avatarBg = GradientDrawable().apply { shape = GradientDrawable.OVAL },
            pillBg = GradientDrawable().apply { cornerRadius = dp(999f).toFloat() },
            photoPlaceholder = GradientDrawable().apply { shape = GradientDrawable.OVAL },
        )
        b.root.background = state.card
        b.ittcContactAvatar.background = state.avatarBg
        // (40 dp to tap, a 20 dp pill drawn in its middle; an inset background sets the padding, so it comes after)
        val inset = dp(10f)
        b.ittcContactLead.background = InsetDrawable(state.pillBg, 0, inset, 0, inset)
        val side = dp(9f)
        b.ittcContactLead.setPadding(side, inset, side, inset)
        IttcUi.ripple(b.ittcContactLead)
        b.root.setTag(R.id.ittc_contact_row_state, state)
    }

    private fun state(b: IttcItemContactBinding): RowState? = b.root.getTag(R.id.ittc_contact_row_state) as? RowState

    /** The card: rounded on top for a letter's first row, at the bottom for its last; a line between */
    fun bindCard(b: IttcItemContactBinding, list: List<Contact>, position: Int, selected: Boolean) {
        val state = state(b) ?: return
        val p = palette
        val fill = if (selected) p.primarySoft else p.surface
        if (state.cardColor != fill) {
            state.cardColor = fill
            state.card.setColor(fill)
        }
        val inList = position in list.indices
        val first = !inList || isSectionStart(list, position)
        val last = !inList || isSectionEnd(list, position)
        val want = when {
            first && last -> cornersAll
            first -> cornersTop
            last -> cornersBottom
            else -> cornersNone
        }
        if (state.corners !== want) {
            state.corners = want
            state.card.cornerRadii = want
        }
        b.ittcContactDivider.visibility = if (first) View.GONE else View.VISIBLE
        if (!first) b.ittcContactDivider.setBackgroundColor(p.divider)
    }

    /** "Mobile · 01712 345678 · +1" (while searching a number: the number that matches) */
    fun bindNumber(b: IttcItemContactBinding, contact: Contact, query: String, fontSize: Float) {
        val numbers = contact.phoneNumbers
        b.ittcContactNumber.setTextColor(palette.muted)
        val size = fontSize * 13f / 16f
        if (b.ittcContactNumber.textSize != size) b.ittcContactNumber.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        if (numbers.isEmpty()) {
            b.ittcContactNumber.visibility = View.GONE
            return
        }
        b.ittcContactNumber.visibility = View.VISIBLE
        val digits = if (query.isEmpty()) "" else query.filter { it.isDigit() }
        val shown = (if (digits.length >= 2) numbers.firstOrNull { n -> n.normalizedNumber.contains(digits) || n.value.contains(digits) } else null)
            ?: numbers.firstOrNull { it.isPrimary }
            ?: numbers.first()
        val type = context.getPhoneNumberTypeText(shown.type, shown.label)
        var others = 0
        if (numbers.size > 1) {
            // (the same number saved twice, e.g. once for WhatsApp, is not another number)
            numbers.forEach { n -> if (n !== shown && n.normalizedNumber != shown.normalizedNumber) others++ }
        }
        val line = StringBuilder()
        if (type.isNotEmpty()) line.append(type).append(" · ")
        line.append(shown.value)
        if (others > 0) line.append("  ").append(context.getString(R.string.ittc_ct_more_numbers, others))
        b.ittcContactNumber.text = line
    }

    /** The contact's photo, else the initials on a soft colour (no bitmap made) */
    fun bindAvatar(activity: Activity, b: IttcItemContactBinding, contact: Contact) {
        val state = state(b) ?: return
        val name = contact.getNameToDisplay()
        val photo = contact.photoUri.isNotEmpty()
        if (photo) {
            state.photoPlaceholder.setColor(palette.surfaceAlt)
            b.itemContactImage.visibility = View.VISIBLE
            b.ittcContactAvatar.visibility = View.GONE
            if (!activity.isDestroyed && !activity.isFinishing) {
                SimpleContactsHelper(b.root.context).loadContactImage(contact.photoUri, b.itemContactImage, name, state.photoPlaceholder)
            }
        } else {
            if (state.hasPhoto && !activity.isDestroyed && !activity.isFinishing) Glide.with(activity).clear(b.itemContactImage)
            b.itemContactImage.visibility = View.GONE
            b.ittcContactAvatar.visibility = View.VISIBLE
            val avatar = avatarCache.getOrPut(name) {
                val (bg, fg) = IttcTheme.avatarColors(context, name)
                Avatar(IttcTheme.initials(name), bg, fg)
            }
            state.avatarBg.setColor(avatar.bg)
            b.ittcContactAvatar.setTextColor(avatar.fg)
            b.ittcContactAvatar.text = avatar.initials
        }
        state.hasPhoto = photo
    }

    fun leadOf(contact: Contact): IttcBridge.Lead? = if (leads.isEmpty()) null else leads[contact.rawId]

    /** The "Lead" pill in the lead's stage colours (gone for a contact who is not a lead) */
    fun bindLead(b: IttcItemContactBinding, lead: IttcBridge.Lead?) {
        val pill = b.ittcContactLead
        if (lead == null) {
            pill.visibility = View.GONE
            return
        }
        val state = state(b) ?: return
        val stage = lead.stage.orEmpty()
        val (bg, fg) = stageCache.getOrPut(stage) { IttcTheme.stageColors(context, stage) }
        if (state.pillColor != bg) {
            state.pillColor = bg
            state.pillBg.setColor(bg)
        }
        pill.setTextColor(fg)
        pill.visibility = View.VISIBLE
        pill.contentDescription = context.getString(R.string.ittc_ct_open_lead, lead.name.ifEmpty { lead.leadId })
    }

    /** Opens the lead: ITTC Phone's own lead page when signed in directly, else the Tracker's */
    fun openLead(activity: Activity, lead: IttcBridge.Lead) {
        val opened = try {
            IttcBridge.openLead(activity, lead.leadId)
        } catch (_: Throwable) {
            false
        }
        if (!opened) Toast.makeText(activity, R.string.ittc_ct_lead_failed, Toast.LENGTH_SHORT).show()
    }

    // ----------------------------------------------------------------------------- headers
    /**
     * The letter above each letter's card (and the room for it). Drawn, not a list item: positions,
     * selection and the fast scroller stay as they are. [listFor] gives the list the adapter shows.
     */
    inner class Headers(private val listFor: (RecyclerView) -> List<Contact>?) : RecyclerView.ItemDecoration() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, context.resources.displayMetrics)
            typeface = Typeface.DEFAULT_BOLD
        }
        private val headerHeight = dp(34f)
        private val gap = dp(8f)
        private val baselineAbove = dp(10f).toFloat()
        private val indent = dp(6f).toFloat()

        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            outRect.set(0, 0, 0, 0)
            val list = listFor(parent) ?: return
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION || position >= list.size) return
            if (isSectionStart(list, position)) outRect.top = if (sections) headerHeight else gap
        }

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            if (!sections) return
            val list = listFor(parent) ?: return
            val rtl = parent.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val color = palette.muted
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val position = parent.getChildAdapterPosition(child)
                if (position == RecyclerView.NO_POSITION || position >= list.size || !isSectionStart(list, position)) continue
                val letter = letterAt(list, position)
                paint.color = color
                paint.alpha = (child.alpha * 255).toInt().coerceIn(0, 255)
                val y = child.top + child.translationY - baselineAbove
                val x = if (rtl) child.right - indent - paint.measureText(letter) else child.left + indent
                c.drawText(letter, x, y, paint)
            }
        }
    }

    companion object {
        /** ContactsAdapter's view type for these rows (any number not used by Fossify's list / grid) */
        const val VIEW_TYPE = 0x1C7C

        /**
         * Which contacts are ITTC leads, by rawId — off the main thread. Uses IttcBridge's batched,
         * cached lookup (the Tracker's provider in one query per 300 numbers, or the kept lead lists
         * when signed in directly); never the network.
         */
        fun leadsOf(context: Context, contacts: List<Contact>): Map<Int, IttcBridge.Lead> {
            val numbers = ArrayList<String>()
            contacts.forEach { c -> c.phoneNumbers.forEach { n -> if (n.value.isNotEmpty()) numbers.add(n.value) } }
            if (numbers.isEmpty()) return emptyMap()
            val found = IttcBridge.leadsFor(context, numbers)
            if (found.isEmpty()) return emptyMap()
            val out = HashMap<Int, IttcBridge.Lead>()
            contacts.forEach { c ->
                for (n in c.phoneNumbers) {
                    val lead = found[n.value] ?: continue
                    out[c.rawId] = lead
                    break
                }
            }
            return out
        }
    }
}
