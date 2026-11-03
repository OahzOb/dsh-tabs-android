package dev.dshtabs

/**
 * The sessions that outlive the window.
 *
 * A session is an SSH connection, a loopback forward and a `dsh web` on the far side,
 * and it used to live only in `MainActivity`. That made every destroy of the activity
 * a disconnect: a configuration change the activity does not declare recreates it, and
 * an OEM's split-screen implementation may relaunch it for its own reasons, without a
 * configuration change at all. `onDestroy` then stopped the connection and the next
 * `onCreate` rebuilt every tab as IDLE from the book, which is the reported failure
 * this file exists to remove.
 *
 * So ownership moves here. The activity still decides **when** a session starts and
 * when the operator ends it; what changes is that a destroy which is not `isFinishing`
 * hands the session to this registry instead of stopping it, and the next `onCreate`
 * adopts it back. The page is rebuilt (a WebView cannot outlive its activity), but the
 * SSH connection, the forward and the far side's program are untouched, and no second
 * `dsh web` is ever started.
 *
 * **It is a registry, not a manager.** It never stops anything on its own: every
 * `remove` and `clear` returns what it gave up, and the caller stops it. That is
 * deliberate — a registry that stops things as a side effect cannot be reasoned about
 * with the activity's own bookkeeping, and an object stopped twice is worse than one
 * stopped late.
 *
 * A process death still ends everything here, and the next launch starts from the book
 * as before. No in-process structure can survive that; the README's Known gaps says so.
 */
class SessionRegistry<T : Any> {

	private val lock = Any()
	private val running = LinkedHashMap<String, T>()

	/**
	 * Called after every change, for a window that is already built.
	 *
	 * A configuration relaunch destroys the old activity before it creates the new one,
	 * so `onCreate` normally finds the registry already populated. An OEM relaunch is
	 * not obliged to be that orderly, and this is what makes the difference invisible:
	 * a session that arrives after `onCreate` still turns its tab RUNNING instead of
	 * leaving a CONNECT button over a live connection. The callback runs outside the
	 * lock and the window posts its work, so a listener that renders cannot re-enter
	 * this class mid-update.
	 *
	 * **A window removes its own observer, by identity.** Two activities can briefly
	 * overlap during an OEM relaunch, and a plain "clear the observer" from the
	 * outgoing one would clear the incoming window's callback — leaving a live session
	 * the new window never learns about. [unobserve] only clears the observer it was
	 * given.
	 */
	private var listener: (() -> Unit)? = null

	fun observe(block: () -> Unit) {
		synchronized(lock) { listener = block }
	}

	fun unobserve(block: () -> Unit) {
		synchronized(lock) { if (listener === block) listener = null }
	}

	private fun changed() {
		val notify = synchronized(lock) { listener }
		notify?.invoke()
	}

	/**
	 * Take ownership of [value] for [id], returning whatever it displaced.
	 *
	 * A displaced value is the caller's to stop. It happens when a tab reconnects while
	 * an adopted session for the same machine is still registered, which is the one
	 * path where two live sessions for one device could otherwise be leaked.
	 */
	fun adopt(id: String, value: T): T? {
		val displaced = synchronized(lock) { running.put(id, value) }
		changed()
		return displaced
	}

	fun get(id: String): T? = synchronized(lock) { running[id] }

	/** Give up [id] without stopping it; the caller owns the returned value now. */
	fun remove(id: String): T? {
		val removed = synchronized(lock) { running.remove(id) }
		if (removed != null) changed()
		return removed
	}

	/** A copy, so a caller can iterate while another thread adopts or removes. */
	fun snapshot(): Map<String, T> = synchronized(lock) { LinkedHashMap(running) }

	fun isEmpty(): Boolean = synchronized(lock) { running.isEmpty() }

	/** Give up every entry, for a caller that is going to stop them all. */
	fun clear(): List<T> {
		val held = synchronized(lock) {
			val values = running.values.toList()
			running.clear()
			values
		}
		if (held.isNotEmpty()) changed()
		return held
	}
}

object Sessions {

	/**
	 * The connections that are up in this process, keyed by device id.
	 *
	 * `MainActivity.onCreate` adopts from it and `onDestroy` — when it is not
	 * finishing — hands back to it. Nothing else writes here on a destroy path, which
	 * is what keeps the two activities an OEM relaunch creates from racing each other:
	 * the old one only ever *puts back* what it already held.
	 */
	val running = SessionRegistry<RemoteSession>()
}
