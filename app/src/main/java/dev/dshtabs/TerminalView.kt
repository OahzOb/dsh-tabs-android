package dev.dshtabs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * The terminal as the window draws it: a WebView with xterm.js in it.
 *
 * **xterm.js rather than a native terminal view.** The desktop client already renders
 * its terminals with xterm.js, and the two applications share no source but do share
 * a protocol; using the same renderer here means escape sequences, line wrapping and
 * selection behave the same way in both, and the only Android-specific part is the
 * bridge to the SSH channel. The alternative — a native terminal emulator — is a
 * second implementation of the VT parser with its own backlog of sequences, and it is
 * the part of a terminal an operator notices least when it is right.
 *
 * The WebView is given three functions: write output, report the size the fit addon
 * computed, and send input. Everything else is xterm's.
 *
 * Output is **coalesced**: a shell that prints a build log writes thousands of small
 * reads, and one `evaluateJavascript` per read would cross the bridge faster than the
 * renderer can draw. Batches of at most one animation frame's worth of text are pumped
 * instead, and the queue is capped by the same discipline as the desktop client's
 * pending buffer: unread output is bytes, and a tab nobody looks at must not grow
 * without bound.
 */
class TerminalView(
	context: Context,
	private val onInput: (String) -> Unit,
	private val onResize: (Int, Int) -> Unit,
	private val onGone: () -> Unit
) {

	/** The view a tab's container gets. */
	val view = WebView(context)

	private val handler = Handler(Looper.getMainLooper())
	private val pending = StringBuilder()
	private var flushScheduled = false
	private var pageReady = false
	private var destroyed = false

	private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

	init {
		view.settings.javaScriptEnabled = true
		view.settings.domStorageEnabled = true
		// The page is a local asset, not a network origin. File access stays on for
		// exactly that reason; there is no remote content in this view.
		view.settings.allowFileAccess = true
		view.settings.allowContentAccess = false
		// The terminal is dark in both themes, like every terminal an operator is
		// likely to have used; the shell around it follows the system setting.
		view.setBackgroundColor(TERMINAL_BG)
		view.webViewClient = object : WebViewClient() {
			override fun onPageFinished(view: WebView, url: String) {
				pageReady = true
				flush()
				view.evaluateJavascript("window.dshTerm && window.dshTerm.fit()", null)
			}

			override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
				// The renderer is gone and this view can never paint again. The window
				// hears about it and rebuilds; the SSH channel is untouched, so the
				// terminal comes back with its scrollback lost and its shell alive.
				destroy()
				onGone()
				return true
			}
		}
		view.addJavascriptInterface(Bridge(), "DshTerminal")
		view.loadUrl("file:///android_asset/terminal/index.html")
	}

	/** Queue output for the terminal. Safe from any thread. */
	fun write(text: String) {
		if (text.isEmpty() || destroyed) return
		synchronized(pending) {
			if (pending.length < MAX_PENDING) {
				pending.append(text)
				// Keep the *newest* output when a tab nobody is looking at overruns: a
				// shell that waited for input is at the end of what it printed.
				if (pending.length > MAX_PENDING) pending.delete(0, pending.length - MAX_PENDING)
			}
		}
		if (Looper.myLooper() === Looper.getMainLooper()) scheduleFlush()
		else handler.post { scheduleFlush() }
	}

	private fun scheduleFlush() {
		if (flushScheduled || destroyed) return
		flushScheduled = true
		handler.postDelayed({ flush() }, FLUSH_INTERVAL_MS)
	}

	private fun flush() {
		flushScheduled = false
		if (destroyed || !pageReady) return
		val text = synchronized(pending) {
			if (pending.isEmpty()) return
			val value = pending.toString()
			pending.setLength(0)
			value
		}
		view.evaluateJavascript("window.dshTerm && window.dshTerm.write(${JSONObject.quote(text)})", null)
	}

	/** Put the cursor in the terminal and raise the keyboard. */
	fun focus() {
		if (destroyed) return
		view.requestFocus()
		view.evaluateJavascript("window.dshTerm && window.dshTerm.focus()", null)
	}

	/** Size the terminal to its container and report the resulting columns/rows. */
	fun fit() {
		if (destroyed) return
		view.evaluateJavascript("window.dshTerm && window.dshTerm.fit()", null)
	}

	/** Read the current selection, if any, into the phone's clipboard. */
	fun copySelection() {
		if (destroyed) return
		view.evaluateJavascript("window.dshTerm ? window.dshTerm.selection() : ''") { value ->
			val text = decodeJavascriptString(value)
			if (text.isNullOrEmpty()) return@evaluateJavascript
			clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", text))
		}
	}

	/** Send the clipboard to the shell, as if it had been typed. */
	fun pasteClipboard() {
		val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(view.context)
		if (text.isNullOrEmpty()) return
		onInput(text.toString())
	}

	fun destroy() {
		if (destroyed) return
		destroyed = true
		handler.removeCallbacksAndMessages(null)
		try {
			view.removeJavascriptInterface("DshTerminal")
			view.destroy()
		} catch (_: Exception) {
			// Already detached by the system.
		}
	}

	/**
	 * The functions xterm.js can call.
	 *
	 * Every one of them arrives on the WebView's JavaScript thread, so nothing here
	 * touches a view directly; the callbacks the window gave this class decide what
	 * thread they need.
	 */
	private inner class Bridge {

		@JavascriptInterface
		fun send(data: String) {
			if (!destroyed) onInput(data)
		}

		@JavascriptInterface
		fun resize(cols: Int, rows: Int) {
			if (!destroyed) onResize(cols, rows)
		}

		@JavascriptInterface
		fun ready() {
			handler.post { flush() }
		}

		@JavascriptInterface
		fun copy(text: String) {
			if (!destroyed && text.isNotEmpty()) {
				handler.post { clipboard?.setPrimaryClip(ClipData.newPlainText("terminal", text)) }
			}
		}
	}

	companion object {
		/** The renderer's background, and the first frame's before it paints. */
		const val TERMINAL_BG = 0xFF15161B.toInt()

		/** One frame at 60 Hz, the rate output is allowed to reach the renderer. */
		const val FLUSH_INTERVAL_MS = 16L

		/** How much undrawn output is held; the tail is what survives. */
		const val MAX_PENDING = 256 * 1024

		/**
		 * A JSON string literal, as `evaluateJavascript` returned it, back to text.
		 *
		 * `evaluateJavascript` hands back JSON, quotes included, and a selection can
		 * contain quotes and newlines of its own — so it is parsed rather than trimmed.
		 */
		fun decodeJavascriptString(value: String?): String? {
			if (value.isNullOrEmpty() || value == "null") return null
			return try {
				JSONObject("{\"v\":$value}").getString("v")
			} catch (_: Exception) {
				null
			}
		}
	}
}
