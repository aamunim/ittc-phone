package org.fossify.phone.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.phone.BuildConfig
import org.fossify.phone.R
import org.fossify.phone.databinding.IttcActivityAboutBinding
import org.fossify.phone.helpers.IttcBridge

/**
 * 🎓 ITTC Phone's own About: the app and its version, a new version, the Tracker, privacy, and —
 * as the GNU GPL asks — the licence, the source code and the original authors (Fossify Phone).
 */
class IttcAboutActivity : SimpleActivity() {
    private val binding by viewBinding(IttcActivityAboutBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(ittcAboutScrollview))
            setupMaterialScrollListener(ittcAboutScrollview, ittcAboutAppbar)
            ittcAboutVersion.text = getString(R.string.ittc_about_version, BuildConfig.VERSION_NAME)
            // (Google Play updates the app; the store page shows when there is a new version)
            ittcAboutUpdate.setOnClickListener { open(PLAY) }
            ittcAboutTracker.setOnClickListener {
                if (!IttcBridge.openTracker(this@IttcAboutActivity)) toast(R.string.ittc_tracker_missing)
            }
            ittcAboutPolicy.setOnClickListener { open("https://lead.ittc.edu.bd/privacy-ittc-phone") }
            ittcAboutSource.setOnClickListener { open(REPO) }
            ittcAboutLicence.setOnClickListener { open("https://www.gnu.org/licenses/gpl-3.0.html") }
            ittcAboutLibraries.setOnClickListener {
                AlertDialog.Builder(this@IttcAboutActivity)
                    .setTitle(R.string.ittc_about_libraries)
                    .setMessage(R.string.ittc_about_libraries_text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
        updateTextColors(binding.ittcAboutScrollview)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.ittcAboutAppbar, NavigationIcon.Arrow)
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Throwable) {
            toast(R.string.ittc_about_no_browser)
        }
    }

    private companion object {
        const val REPO = "https://github.com/aamunim/ittc-phone"
        val PLAY get() = "https://play.google.com/store/apps/details?id=${BuildConfig.APPLICATION_ID.removeSuffix(".debug")}"
    }
}
