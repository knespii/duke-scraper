package cz.duke390.hlidac.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import cz.duke390.hlidac.MainActivity
import cz.duke390.hlidac.R
import cz.duke390.hlidac.scrape.Pipeline
import cz.duke390.hlidac.ui.fmtCzk
import java.util.concurrent.TimeUnit

/** Stáhne inzeráty (ručně z aplikace nebo každé 3 h na pozadí) a upozorní na nové. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = runCatching {
        val res = Pipeline(applicationContext).run { msg -> setProgress(workDataOf(PROGRESS to msg)) }
        if (!res.firstRun && res.newCards.isNotEmpty()) notifyNew(applicationContext, res.newCards.map { it.title to it.priceCzk }, res.newCards.count { it.deal })
        Result.success()
    }.getOrElse { e ->
        if (runAttemptCount < 2 && tags.contains(TAG_PERIODIC)) Result.retry()
        else Result.failure(workDataOf(ERROR to (e.message ?: e::class.simpleName)))
    }

    companion object {
        const val PROGRESS = "progress"
        const val ERROR = "error"
        const val MANUAL = "refresh-manual"
        private const val PERIODIC = "refresh-periodic"
        private const val TAG_PERIODIC = "periodic"
        private const val CHANNEL = "new-listings"

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun refreshNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, req)
        }

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS)
                .setConstraints(net).addTag(TAG_PERIODIC).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        @android.annotation.SuppressLint("MissingPermission")
        private fun notifyNew(ctx: Context, items: List<Pair<String, Int>>, deals: Int) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Nové inzeráty", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val title = (if (deals > 0) "🔥 $deals zajímavých · " else "") + "${items.size} nových Duke 390"
            val lines = items.take(5).joinToString("\n") { (t, p) -> "${t.take(40)} – ${fmtCzk(p)}" }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(items.first().let { "${it.first.take(40)} – ${fmtCzk(it.second)}" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
                .setContentIntent(open).setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(ctx).notify(1, n)
        }
    }
}
