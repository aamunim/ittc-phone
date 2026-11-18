package org.fossify.phone.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityAddLeadBinding
import org.fossify.phone.direct.IttcApi
import org.fossify.phone.direct.IttcDirect
import org.fossify.phone.direct.IttcLeadApi
import org.fossify.phone.helpers.IttcDisclosure

/**
 * 🎓 ITTC direct mode: add a lead (from a number, after a call with someone who is not a lead, or
 * the Leads tab). Needs internet: the server checks it (a course, a number that is no lead yet) and
 * gives it a stage and a person, as the Tracker's form does.
 */
class IttcAddLeadActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityAddLeadBinding::inflate)

    private var courses: List<IttcLeadApi.Course> = emptyList()
    private var courseId: String? = null
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomImeAndSystem = listOf(ittcAddScrollview))
            setupMaterialScrollListener(ittcAddScrollview, ittcAddAppbar)
            if (savedInstanceState == null) {
                ittcAddPhone.setText(intent.getStringExtra(NUMBER)?.let { IttcDirect.normalizeBd(it) ?: it }.orEmpty())
                ittcAddName.setText(intent.getStringExtra(NAME).orEmpty())
            }
            ittcAddSameWhatsapp.setOnCheckedChangeListener { _, same -> ittcAddWhatsapp.beVisibleIf(!same) }
            ittcAddWhatsapp.beVisibleIf(!ittcAddSameWhatsapp.isChecked)
            val primary = getProperPrimaryColor()
            ittcAddSave.backgroundTintList = ColorStateList.valueOf(primary)
            ittcAddSave.setTextColor(primary.getContrastColor())
            ittcAddSave.setOnClickListener { save() }
            ittcAddCourseStatus.setOnClickListener { if (courses.isEmpty()) loadCourses() }
        }
        updateTextColors(binding.ittcAddHolder)
        loadCourses()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcAddAppbar, NavigationIcon.Arrow)
    }

    private fun alive() = !isFinishing && !isDestroyed

    // ------------------------------------------------------------------ courses
    private fun loadCourses() {
        binding.ittcAddCourseStatus.setText(R.string.ittc_lp_loading)
        binding.ittcAddCourseStatus.beVisible()
        ensureBackgroundThread {
            val result = try {
                IttcLeadApi.courses(this) to null
            } catch (e: Throwable) {
                null to readable(e)
            }
            runOnUiThread {
                if (!alive()) return@runOnUiThread
                val (list, error) = result
                if (list == null) {
                    binding.ittcAddCourseStatus.text = getString(R.string.ittc_al_courses_failed, error) + "\n" + getString(R.string.ittc_al_retry)
                    return@runOnUiThread
                }
                courses = list
                if (list.size == 1) courseId = list[0].id
                binding.ittcAddCourseStatus.beGone()
                renderCourses()
            }
        }
    }

    private fun renderCourses() {
        val box = binding.ittcAddCourses
        box.removeAllViews()
        val m = (resources.displayMetrics.density * 4).toInt()
        courses.chunked(2).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { c ->
                line.addView(
                    chip(c.name, c.id == courseId) {
                        courseId = c.id
                        renderCourses()
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(m, m * 2, m, 0) },
                )
            }
            if (row.size == 1) line.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { setMargins(m, m * 2, m, 0) })
            box.addView(line)
        }
    }

    private fun chip(label: String, on: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        val pad = (resources.displayMetrics.density * 10).toInt()
        setPadding(pad, pad * 3 / 4, pad, pad * 3 / 4)
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.ittc_chip_background)
        val primary = getProperPrimaryColor()
        val textColor = getProperTextColor()
        background.mutate().applyColorFilter(if (on) primary else (textColor and 0x00FFFFFF) or 0x22000000)
        setTextColor(if (on) primary.getContrastColor() else textColor)
        maxLines = 2
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------ save
    private fun showError(message: String?) {
        binding.ittcAddError.text = message
        binding.ittcAddError.setTextColor(resources.getColor(R.color.md_red_700, theme))
        binding.ittcAddError.beVisibleIf(!message.isNullOrBlank())
    }

    private fun readable(e: Throwable): String =
        if (e is IttcApi.ApiError && e.status == 0) getString(R.string.ittc_al_no_internet)
        else if (e is IttcApi.ApiError && e.status == 401) getString(R.string.ittc_al_not_signed_in)
        else e.message ?: getString(R.string.ittc_al_no_internet)

    private fun save() {
        if (saving) return
        val name = binding.ittcAddName.text?.toString()?.trim().orEmpty()
        val phoneRaw = binding.ittcAddPhone.text?.toString()?.trim().orEmpty()
        val altRaw = binding.ittcAddAlt.text?.toString()?.trim().orEmpty()
        val waRaw = binding.ittcAddWhatsapp.text?.toString()?.trim().orEmpty()
        val note = binding.ittcAddNote.text?.toString()?.trim().orEmpty()

        if (name.isEmpty()) {
            showError(getString(R.string.ittc_lp_name_needed))
            binding.ittcAddName.requestFocus()
            return
        }
        val phone = IttcDirect.normalizeBd(phoneRaw)
        if (phone == null) {
            showError(getString(R.string.ittc_lp_bad_number))
            binding.ittcAddPhone.requestFocus()
            return
        }
        val alt = if (altRaw.isEmpty()) null else IttcDirect.normalizeBd(altRaw) ?: run {
            showError(getString(R.string.ittc_lp_bad_number))
            binding.ittcAddAlt.requestFocus()
            return
        }
        val whatsapp = when {
            binding.ittcAddSameWhatsapp.isChecked -> phone
            waRaw.isEmpty() -> null
            else -> IttcDirect.normalizeBd(waRaw) ?: waRaw.filter { it.isDigit() || it == '+' }.takeIf { it.count(Char::isDigit) >= 8 } ?: run {
                showError(getString(R.string.ittc_lp_bad_number))
                binding.ittcAddWhatsapp.requestFocus()
                return
            }
        }
        val course = courseId
        if (course == null) {
            showError(getString(R.string.ittc_al_pick_course))
            return
        }
        if (!IttcDirect.isSignedIn(this)) {
            showError(getString(R.string.ittc_al_not_signed_in))
            return
        }
        showError(null)
        hideKeyboard()
        IttcDisclosure.showOnce(this) {
            setSaving(true)
            ensureBackgroundThread {
                // (a number that is a lead already: offer it instead of a second one)
                val dup = try {
                    IttcLeadApi.duplicateOf(this, phone)
                } catch (_: Throwable) {
                    null
                }
                if (dup != null) {
                    runOnUiThread {
                        if (!alive()) return@runOnUiThread
                        setSaving(false)
                        showDuplicate(dup)
                    }
                    return@ensureBackgroundThread
                }
                var error: String? = null
                val id = try {
                    IttcLeadApi.createLead(this, name, phone, alt, whatsapp, course, note.ifEmpty { null })
                } catch (e: Throwable) {
                    error = readable(e)
                    null
                }
                runOnUiThread {
                    if (!alive()) return@runOnUiThread
                    setSaving(false)
                    if (id == null) {
                        showError(error)
                        return@runOnUiThread
                    }
                    toast(R.string.ittc_al_saved)
                    // (added right after a call with the number: that call goes to the new lead too)
                    val startedAt = intent.getLongExtra(STARTED_AT, 0L)
                    if (startedAt > 0) {
                        val app = applicationContext
                        val callNumber = intent.getStringExtra(NUMBER)?.takeIf { it.isNotBlank() } ?: phone
                        ensureBackgroundThread {
                            try {
                                IttcDirect.queueCallAt(app, callNumber, startedAt)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                    IttcDirect.refreshAsync(applicationContext, force = true)
                    IttcLeadActivity.open(this, id)
                    finish()
                }
            }
        }
    }

    private fun setSaving(on: Boolean) {
        saving = on
        binding.ittcAddSave.isEnabled = !on
        binding.ittcAddSave.setText(if (on) R.string.ittc_al_saving else R.string.ittc_al_save)
    }

    private fun showDuplicate(dup: IttcLeadApi.Duplicate) {
        val extra = listOfNotNull(dup.stage, dup.owner).joinToString(" · ")
        if (!dup.canOpen) {
            showError(getString(R.string.ittc_al_already_other, listOfNotNull(dup.name.takeIf { it.isNotBlank() }, extra.takeIf { it.isNotBlank() }).joinToString(" · ")))
            return
        }
        val title = getString(R.string.ittc_al_already, dup.name.ifBlank { "—" })
        getAlertDialogBuilder()
            .setTitle(title)
            .apply { if (extra.isNotEmpty()) setMessage(extra) }
            .setPositiveButton(R.string.ittc_al_open) { _, _ ->
                IttcLeadActivity.open(this, dup.leadId)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val NUMBER = "ittc_number"
        private const val NAME = "ittc_name"
        private const val STARTED_AT = "ittc_started_at"

        /**
         * "Add a lead", the phone (and a name, e.g. the contact's) filled in; [startedAt] (> 0: right
         * after a call with the number) sends that call for the new lead once it is added
         */
        fun open(context: Context, number: String?, name: String?, startedAt: Long = 0L) {
            try {
                context.startActivity(
                    Intent(context, IttcAddLeadActivity::class.java)
                        .putExtra(NUMBER, number)
                        .putExtra(NAME, name)
                        .putExtra(STARTED_AT, startedAt)
                        .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                )
            } catch (_: Throwable) {
            }
        }
    }
}
