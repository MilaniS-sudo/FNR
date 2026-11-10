package za.ac.richfield.fnr.util

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
import za.ac.richfield.fnr.R
import za.ac.richfield.fnr.ui.MainActivity

object Notifier {
    const val CHANNEL_RECORDING = "recording"
    const val CHANNEL_PROCESSING = "processing"
    private const val ID_PROCESSING = 2001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while a study session is being recorded" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROCESSING, "Flash note processing", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Tells you when your flash notes are ready" }
        )
    }

    fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun processingDone(context: Context, title: String, notes: Int) {
        val text = if (notes == 0) "No key points were found in \"$title\"." else "$notes flash notes created from \"$title\"."
        show(context, "Flash notes ready", text)
    }

    fun processingFailed(context: Context, title: String, reason: String) {
        show(context, "Processing failed: $title", "$reason Your recording is saved; tap Retry in Recordings.")
    }

    private fun show(context: Context, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(ID_PROCESSING, notification)
        } catch (e: SecurityException) {
            // Notifications switched off: the status is still visible in the app.
        }
    }
}
