package ai.voitta.virgil

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts the blurb as a notification.
 *
 * Redundant while the trigger is a button -- the user is already looking at the
 * app. It is built now so the delivery path is exercised before v0.2 moves the
 * trigger to passive, where the notification becomes the primary surface.
 */
object Notifier {

    private const val CHANNEL_ID = "virgil-blurbs"
    private const val NOTIFICATION_ID = 1

    fun post(context: Context, blurb: String, place: Place) {
        if (!allowed(context)) {
            return
        }
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = place.street ?: place.neighbourhood ?: place.city ?: "Where you are"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(blurb.take(80))
            .setStyle(NotificationCompat.BigTextStyle().bigText(blurb))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Android 13 added a runtime permission for notifications. Without it,
     * notify() is silently dropped rather than throwing.
     */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return true
        }
        val retval = context.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        return retval
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Blurbs",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = "What Virgil has to say about where you are"
        val manager = context.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
}
