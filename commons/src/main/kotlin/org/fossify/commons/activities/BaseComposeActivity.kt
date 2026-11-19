package org.fossify.commons.activities

import android.content.Context
import androidx.activity.ComponentActivity
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.AppTextScale
import org.fossify.commons.helpers.MyContextWrapper
import org.fossify.commons.helpers.REQUEST_APP_UNLOCK
import org.fossify.commons.helpers.isTiramisuPlus

abstract class BaseComposeActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        maybeLaunchAppUnlockActivity(REQUEST_APP_UNLOCK)
    }

    override fun attachBaseContext(newBase: Context) {
        // 🎓 ITTC 3.12: the app's own text size on top of the phone's font size
        val base = AppTextScale.wrap(newBase)
        if (base.baseConfig.useEnglish && !isTiramisuPlus()) {
            super.attachBaseContext(MyContextWrapper(base).wrap(base, "en"))
        } else {
            super.attachBaseContext(base)
        }
    }
}
