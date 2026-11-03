package dev.dshtabs

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Holds a session that has to outlive the screen.
 *
 * This exists because the platform otherwise closes it, and the reason is worth
 * stating precisely rather than as "Android is aggressive": a cached app process is
 * **frozen** a few seconds after it leaves the foreground, all its threads are
 * suspended, and once every process of the app is frozen the system **terminates
 * the app's active TCP sockets**. A tunnel that lives only in a background thread is
 * therefore not throttled — it is closed, and the remote Harness is left holding its
 * port until the connection's EOF reaps it, which does arrive when the socket dies.
 * A foreground service keeps the process out of the cached state.
 *
 * **What this is not:** a promise to survive Doze indefinitely. Doze suspends
 * network access for the whole device, and the battery-optimisation exemption that
 * lifts it is a Play-policy matter that an SSH tunnel is not on the accepted list
 * for. A session may therefore stall after a long idle. Reopening it is a button.
 *
 * The service is a **holder, not an owner**: the activity starts a session and the
 * operator ends it, and between those two the sessions live in [Sessions], which
 * outlives any one window. This only provides the process priority and the visible
 * notice that say a connection is running, and it is what keeps the process — and the
 * tunnel inside it — out of the cached state while the window that started it is being
 * recreated.
 */
class SessionService : Service() {

	override fun onBind(intent: Intent?): IBinder? = null

	/**
	 * The task was swiped away, which is the operator saying "close".
	 *
	 * The activity's own destroy cannot be relied on for this: removing a task does not
	 * have to run a normal finishing destroy, and once sessions outlive their window a
	 * foreground service left behind would hold a `dsh web` on someone else's machine
	 * with no window that could reach it. Stopping here is the same end × and Back have,
	 * reached from the one lifecycle callback that is guaranteed when the task goes.
	 */
	override fun onTaskRemoved(rootIntent: Intent?) {
		super.onTaskRemoved(rootIntent)
		for (session in Sessions.running.clear()) session.stop()
		stopSelf()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val label = intent?.getStringExtra(EXTRA_LABEL) ?: "a machine"
		// Must happen promptly after startForegroundService(), or the system raises a
		// failure on API 26+.
		startForeground(NOTIFICATION_ID, Notifications.session(this, label))
		// START_NOT_STICKY on purpose: a restart delivers a null intent and there would
		// be no session to attach it to, because a session cannot be rebuilt from an
		// intent — it is an SSH connection and a remote program, and [Sessions] does not
		// survive the process either.
		return START_NOT_STICKY
	}

	companion object {
		const val EXTRA_LABEL = "label"
		private const val NOTIFICATION_ID = 41

		fun start(context: Context, label: String) {
			val intent = Intent(context, SessionService::class.java).putExtra(EXTRA_LABEL, label)
			// The service is always started from a visible Activity — a tap on a tab or a
			// connect button — so this is never a background start, which the platform
			// forbids anyway.
			context.startForegroundService(intent)
		}

		fun stop(context: Context) {
			context.stopService(Intent(context, SessionService::class.java))
		}
	}
}
