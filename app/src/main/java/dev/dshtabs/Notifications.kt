package dev.dshtabs

import android.app.PendingIntent
import android.app.Notification
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.Intent

/**
 * Builders for the one notification this app posts.
 *
 * Kept apart from the service so the wording and the channel id are in one place;
 * a notification that says the wrong thing about what is running is worse than no
 * notification, because it is the only thing the operator sees while the app is in
 * the background.
 */
object Notifications {
	fun session(context: Context, label: String): Notification {
		val open = PendingIntent.getActivity(
			context,
			0,
			Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		return NotificationCompat.Builder(context, App.CHANNEL)
			.setSmallIcon(android.R.drawable.stat_sys_upload_done)
			.setContentTitle(context.getString(R.string.notif_title, label))
			.setContentText(context.getString(R.string.notif_text, label))
			.setContentIntent(open)
			.setOngoing(true)
			.setSilent(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build()
	}
}
