package org.fossify.phone.direct

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

/** 🔁 Android runs this when there is internet again: what waits in the queue is sent */
class IttcOutboxJob : JobService() {
    private val worker = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        val app = applicationContext
        worker.execute {
            val left = try {
                IttcDirect.sendAll(app)
            } catch (_: Throwable) {
                1
            }
            try {
                IttcDirect.refresh(app)
            } catch (_: Throwable) {
            }
            jobFinished(params, left > 0)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
