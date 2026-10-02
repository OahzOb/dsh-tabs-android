package dev.dshtabs

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Process start-up: give the book somewhere to live, and create the one
 * notification channel a foreground session needs.
 *
 * Both have to happen before any Activity, which is why they are here rather than
 * in `MainActivity.onCreate`.
 */
class App : Application() {
	override fun onCreate() {
		super.onCreate()
		DeviceBook.init(filesDir)
		Keys.init(this)
		installBouncyCastle()
		recordCrashes()
		createNotificationChannel()
	}

	/**
	 * Write an uncaught exception to a file the operator can read afterwards.
	 *
	 * A crash during a connection is the worst place to lose information: the process
	 * is gone, so the transcript panel that would have explained it never rendered,
	 * and logcat rotates. This keeps the last one on disk where `adb shell run-as
	 * dev.dshtabs cat files/last-crash.txt` can reach it without a working UI.
	 *
	 * Deliberately minimal: the previous handler is chained rather than replaced, so
	 * the platform's own reporting still happens, and nothing here does I/O that could
	 * itself throw on the way down.
	 */
	private fun recordCrashes() {
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try {
				val report = java.io.StringWriter().also { buffer ->
					java.io.PrintWriter(buffer).use { writer -> error.printStackTrace(writer) }
				}
				java.io.File(filesDir, "last-crash.txt").writeText(
					"thread=${thread.name}\ntime=${java.util.Date()}\n\n$report"
				)
			} catch (_: Throwable) {
				/* never let the crash handler be the reason a crash is unexplained */
			}
			previous?.uncaughtException(thread, error)
		}
	}

	/**
	 * Make BouncyCastle available to JSch, which is the only way it gets Ed25519.
	 *
	 * JSch looks the provider up by name when it needs `ssh-ed25519`, and a provider
	 * that is merely on the classpath is not registered. Without this line the handshake
	 * against a modern OpenSSH server still fails with `Algorithm negotiation fail`
	 * even though the library is present — the failure moves from "not compiled in" to
	 * "not registered", which is a worse bug because it looks like the fix did nothing.
	 *
	 * Added at the end of the provider list on purpose: Android's own providers stay
	 * ahead of it, so nothing that already works starts going through a second
	 * implementation.
	 */
	private fun installBouncyCastle() {
		if (java.security.Security.getProvider(PROVIDER) != null) return
		try {
			java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
		} catch (error: Throwable) {
			// Not fatal: everything except Ed25519 still works without it, and a
			// connect that needs Ed25519 will say so in its transcript.
			android.util.Log.w("dsh-tabs", "BouncyCastle could not be installed: ${error.message}")
		}
	}

	private fun createNotificationChannel() {
		val manager = getSystemService(NotificationManager::class.java)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
			manager.createNotificationChannel(
				NotificationChannel(
					CHANNEL,
					getString(R.string.notif_channel),
					// LOW, because a persistent "you are connected" notice is
					// information, not an interruption — and because a foreground
					// service's notification must be at least LOW to be valid.
					NotificationManager.IMPORTANCE_LOW
				).apply {
					description = getString(R.string.notif_channel_desc)
					setShowBadge(false)
				}
			)
		}
	}

	companion object {
		const val CHANNEL = "sessions"

		/** The provider name JSch looks for when it needs Ed25519. */
		const val PROVIDER = "BC"
	}
}
