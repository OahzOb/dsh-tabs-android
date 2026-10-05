package dev.dshtabs

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume

/**
 * The window: a tab bar that is always visible, and one Harness UI under it.
 *
 * This is the same shape as the desktop application's window, deliberately. What
 * differs is what a tab *is*: there is no local tab, because a phone cannot run a
 * Harness and this client is not trying to. Every tab is a machine reached over SSH.
 *
 * Two things follow from that and are worth naming:
 *
 * - **A connection is owned here, not by the WebView.** The SSH session, the remote
 *   server and the port forward all outlive any particular page load, so switching
 *   tabs does not restart anything and a reload is just a reload.
 * - **One WebView is reused for every tab.** Each session gets its own loopback
 *   origin (`127.0.0.1:<a fresh port>`), so the cookies the Harness issues are
 *   already partitioned per machine by the port number; a second WebView would buy
 *   nothing and cost a second renderer process.
 */
class MainActivity : AppCompatActivity() {

	/** How a tab is doing. */
	private enum class State { IDLE, STARTING, RUNNING, FAILED }

	/**
	 * The identity of one connect attempt.
	 *
	 * **The job alone is not enough, and that is what this exists for.** A job that is
	 * cancelled while `RemoteSession.open` is in flight never delivers its result, so
	 * the session it opened has no owner — and nothing stopped the far side's
	 * `dsh web`, which goes on holding the machine's port with no client anywhere that
	 * knows it exists. That happens on three ordinary paths: a CONNECT on a second tab,
	 * `stopEverything()` on back, and the activity's `lifecycleScope` at destroy. A
	 * token carried by the tab separates "this attempt was cancelled" from "this tab is
	 * no longer here at all", and either answer has to end in `session.stop()`.
	 */
	private class Token

	private class Tab(
		var device: Device,
		var state: State = State.IDLE,
		var session: RemoteSession? = null,
		var error: String? = null,
		var lines: List<String> = emptyList(),
		/** The attempt that owns this tab's connect, or a token nobody holds once one ends. */
		var attempt: Token = Token(),
		/** The connect coroutine, so × and `remove` can end an attempt that is in flight. */
		var connectJob: Job? = null
	) {
		/**
		 * End this tab's attempt and its session.
		 *
		 * Order matters in the same way it does inside [RemoteSession.stop]: the attempt
		 * is cancelled first so that a result arriving afterwards is recognised as stale
		 * and stopped rather than adopted.
		 */
		fun stop() {
			connectJob?.cancel()
			connectJob = null
			session?.stop()
			session = null
		}
	}

	private val tabs = mutableListOf<Tab>()
	private var activeIndex = -1

	/**
	 * True once [onDestroy] has ended every session.
	 *
	 * A cancelled attempt's `finally` still runs afterwards — it is a plain coroutine,
	 * not a lifecycle-aware one, and cancelling it cannot stop a block that has already
	 * begun — so it must not paint a window that is gone. It has nothing left to do for
	 * the UI either way: the destroy already put every tab back to IDLE and stopped the
	 * service.
	 */
	private var destroyed = false

	/** Passwords for this run only, keyed by device id. Never persisted. */
	private val passwords = mutableMapOf<String, String>()

	/**
	 * How many automatic reconnect attempts a tab gets before it gives up and asks.
	 *
	 * A number rather than "until it works": a server that dies on every boot would
	 * otherwise be restarted in a loop, on someone else's machine, from a phone in a
	 * pocket. The budget is cleared by any successful connect, so a machine that
	 * restarts once an hour is never mistaken for one that keeps dying.
	 */
	private val reconnectAttempts = mutableMapOf<String, Int>()

	private lateinit var tabsRow: LinearLayout
	private lateinit var webHost: android.widget.FrameLayout

	/**
	 * The live pages, one per running session, keyed by device id.
	 *
	 * The entry remembers the URL it was built for, so a session that was torn down
	 * and started again — a reconnect, which announces a new port and token — is
	 * recognised as a different page and given a new view rather than being served
	 * the old one.
	 */
	private val views = mutableMapOf<String, WebEntry>()

	private class WebEntry(val view: WebView, val url: String)
	private lateinit var panel: View
	private lateinit var panelTitle: TextView
	private lateinit var panelReason: TextView
	private lateinit var panelAction: android.widget.Button
	private lateinit var panelTranscript: TextView
	private lateinit var panelTranscriptLabel: TextView

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)

		// A diagnostic launch — driven only from an adb shell, and inert without its
		// extras. It shares the launcher activity because that is the only component a
		// shell may start without the app exporting a way in. See CryptoProbe.
		if (CryptoProbe.requested(intent)) {
			lifecycleScope.launch {
				try {
					when {
						intent.getBooleanExtra("probeKeygen", false) -> CryptoProbe.keygen()
						intent.getBooleanExtra("probeWeb", false) -> probeWebView(intent)
						else -> CryptoProbe.run(intent)
					}
				} catch (error: Throwable) {
					CryptoProbe.say("EXCEPTION ${error.javaClass.name}: ${error.message}")
				}
				finish()
			}
			return
		}

		setContentView(R.layout.activity_main)

		tabsRow = findViewById(R.id.tabs)
		webHost = findViewById(R.id.webHost)
		panel = findViewById(R.id.panel)
		panelTitle = findViewById(R.id.panelTitle)
		panelReason = findViewById(R.id.panelReason)
		panelAction = findViewById(R.id.panelAction)
		panelTranscript = findViewById(R.id.panelTranscript)
		panelTranscriptLabel = findViewById(R.id.panelTranscriptLabel)

		// No WebView is configured here: there is no page yet, and the views are
		// created by `renderContent` when a session starts. The one global setting
		// that has to be made before any of them exists lives in `onCreate` below.
		WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
		findViewById<ImageButton>(R.id.add).setOnClickListener { showDeviceDialog(null) }
		applyWindowInsets()
		// Called at startup as well as on a mode change, so the two paths cannot
		// disagree about what this window looks like. See `applyChromeColors`.
		applyChromeColors()

		loadBook()
		render()
		askForNotifications()
	}

	/**
	 * Load the remote Harness into a real WebView, and report what came back.
	 *
	 * This is the last link the probe cannot otherwise reach. Everything up to the
	 * forwarded port is proved by [CryptoProbe]; what is left is whether **WebView**
	 * will load `http://127.0.0.1:<port>/?token=…` at all — which depends on the
	 * platform's cleartext policy, on `network_security_config.xml` naming loopback,
	 * and on the token exchange completing as a top-level navigation.
	 *
	 * A bare WebView rather than the app's own, so this cannot be confused with the
	 * real UI: the point is to observe the load, not to draw it. It reports the URL
	 * after the redirect, whether the document is a Harness page, and any console or
	 * load error — the three things that would otherwise be a blank screen with no
	 * explanation.
	 */
	private suspend fun probeWebView(intent: android.content.Intent) {
		val result = CryptoProbe.openSession(intent)
		if (result is SessionStart.Failed) {
			CryptoProbe.say("WEB_FAILED no session: ${result.error}")
			return
		}
		val session = (result as SessionStart.Ok).session
		CryptoProbe.say("WEB_SESSION ready ${session.url}")

		// Prove the token exchange before handing the URL to WebView, so a failure in
		// the client's own half of the protocol is distinguishable from a failure to
		// render it. The request above already warmed the far side's session.
		CryptoProbe.say("WEB_AUTHENTICATED ${CryptoProbe.exerciseHttp(session)}")

		val loaded = CompletableFuture<Boolean>()
		val view = WebView(this)
		view.settings.javaScriptEnabled = true
		view.settings.domStorageEnabled = true
		view.webViewClient = object : WebViewClient() {
			override fun onPageFinished(view: WebView, url: String) {
				CryptoProbe.say("WEB_PAGE_FINISHED url=$url")
			}

			override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
				// Only the main document matters: a subresource failing is noise.
				if (request.isForMainFrame) {
					CryptoProbe.say("WEB_MAIN_FRAME_ERROR code=${error.errorCode} desc=${error.description} url=${request.url}")
				}
			}
		}
		view.webChromeClient = object : android.webkit.WebChromeClient() {
			override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
				CryptoProbe.say("WEB_CONSOLE ${message.messageLevel()} ${message.message()}")
				return true
			}
		}

		view.loadUrl(session.url)

		// Watch until the document looks like the Harness, or give up. Reading the DOM
		// needs the main thread, and this whole method runs on it.
		val deadline = System.currentTimeMillis() + 45_000
		while (System.currentTimeMillis() < deadline) {
			kotlinx.coroutines.delay(1_000)
			val title = view.title
			if (title != null && title.isNotEmpty()) {
				CryptoProbe.say("WEB_TITLE $title")
			}
			val ready = evaluate(view, "document.readyState")
			val boot = evaluate(view, "typeof window.__DSH_BOOT__ !== 'undefined'")
			val body = evaluate(view, "document.body ? document.body.innerText.slice(0,120) : ''")
			CryptoProbe.say("WEB_STATE ready=$ready boot=$boot url=${view.url}")
			if (body != null && body.isNotBlank()) CryptoProbe.say("WEB_TEXT <<${body.replace("\n", " ")}>>")
			if (ready == "complete" && (boot == "true" || (body?.isNotBlank() == true))) {
				loaded.complete(true)
				break
			}
		}
		CryptoProbe.say("WEB_RESULT loaded=${loaded.getNow(false)} finalUrl=${view.url}")
		session.stop()
		CryptoProbe.say("WEB_STOPPED")
	}

	/**
	 * Read one expression out of a page.
	 *
	 * `evaluateJavascript` takes a callback rather than returning, so this suspends
	 * until it fires. It deliberately does **not** spin the looper to wait: this is a
	 * coroutine on the main thread, and pumping the main looper from inside it is the
	 * kind of thing that works until it deadlocks.
	 */
	private suspend fun evaluate(view: WebView, script: String): String? =
		suspendCancellableCoroutine { continuation ->
			try {
				view.evaluateJavascript(script) { value -> continuation.resume(value?.trim('"')) }
			} catch (error: Throwable) {
				continuation.resume(null)
			}
		}

	private fun loadBook() {
		val stored = DeviceBook.load()
		// One tab per configured machine, none of them started — the same rule as the
		// desktop app, and for the same reason: a tab is a configured thing, so it is
		// present before anything is opened.
		tabs.clear()
		stored.forEach { tabs += Tab(it) }
		activeIndex = if (tabs.isEmpty()) -1 else 0
	}

	override fun onDestroy() {
		super.onDestroy()
		// **Any destroy ends the sessions, not only a finishing one.** The sessions live
		// in this activity, so a destroy that is not `isFinishing` throws away the only
		// list that can reach them: the foreground service and the far side's `dsh web`
		// both go on running, `onCreate` rebuilds every tab as IDLE from the book, and
		// the shade says "Connected to X" while the panel offers CONNECT — which starts
		// a *second* `dsh web` on that machine.
		//
		// The `configChanges` list above (`orientation|screenSize|screenLayout|
		// keyboardHidden|uiMode`) is what makes this look impossible, and it is the
		// reason this went unnoticed: those changes arrive at `onConfigurationChanged`
		// and never destroy anything. Every configuration **not** in that list does —
		// `fontScale` is the one that is simply a setting, and it is *not* covered here
		// by any of the five above; `locale` and `layoutDirection` are changed by
		// `LocaleManager`, which is an ordinary system setting on 13+; `density`,
		// `navigation`, `smallestScreenSize` and `colorMode` arrive the same way from a
		// display or dock change. All of the resources that select a palette are
		// re-resolved on the way through, which is why a mode change and a destroy can
		// be the same event.
		//
		// **The cost of this version, stated because it is a decision:** sessions now
		// belong to the window rather than to the process, so a configuration change
		// that destroys the activity drops every connection and the operator connects
		// again. The larger fix is to own them outside the activity — a process-level
		// registry that `SessionService` re-attaches in `onCreate` — and that is
		// deliberately out of scope here. This is the small version: it makes the two
		// screens agree, at the price of a reconnect on a rare config change.
		destroyed = true
		stopEverything()
	}

	private fun stopEverything() {
		tabs.forEach { it.stop() }
		SessionService.stop(this)
	}

	// ---------------------------------------------------------------- webview

	/**
	 * Keep the tab bar out from under the status bar, and the content out from under
	 * the navigation bar.
	 *
	 * The app is **edge to edge**, which is not a choice: from Android 15 the platform
	 * lays every app out that way regardless of target SDK, so a window that ignores
	 * insets has its first row drawn underneath the clock and its last row underneath
	 * the gesture bar. Measured on the target device: without this, the first tab
	 * collided with the status bar's clock.
	 *
	 * The padding goes on the root: the bar gets the top inset because it is the first
	 * thing in the column, and everything below it gets the bottom inset because the
	 * content is last. The keyboard inset is deliberately **not** consumed here —
	 * `windowSoftInputMode="adjustResize"` already shrinks the window for it, and
	 * consuming it as well would leave a gap the size of the keyboard.
	 */
	private fun applyWindowInsets() {
		val root = findViewById<View>(R.id.root)
		ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
			view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
			insets
		}
		ViewCompat.requestApplyInsets(root)
	}

	/**
	 * Paint the window from the palette the current system mode selects.
	 *
	 * The palette is split across `values/` and `values-night/`, so every `@color/…`
	 * a layout names resolves differently in the two modes — but only when something
	 * resolves it again. A view inflated from XML keeps the colour it was born with,
	 * and this activity declares `uiMode` in its `configChanges` (which is what keeps
	 * a mode switch from throwing the WebViews away), so the platform hands over
	 * `onConfigurationChanged` instead of rebuilding the window. The tab row is the
	 * one thing that looks after itself, because `renderTabs` inflates it per render.
	 *
	 * **Recreating the activity is not the alternative.** It re-inflates everything
	 * for free and takes the WebViews with it, and the remote Harness is a
	 * single-page application: a reload is a lost conversation, which is the failure
	 * this window is built around avoiding.
	 *
	 * Called from `onCreate` too, so a freshly launched window and one that has just
	 * changed mode are painted by the same statements rather than by two mechanisms
	 * that happen to agree today.
	 */
	private fun applyChromeColors() {
		val bg = getColor(R.color.bg)
		val bar = getColor(R.color.bar)
		val fg = getColor(R.color.fg)
		val dim = getColor(R.color.dim)

		findViewById<View>(R.id.root).setBackgroundColor(bg)
		findViewById<View>(R.id.bar).setBackgroundColor(bar)
		findViewById<ImageButton>(R.id.add).setColorFilter(dim)

		panelTitle.setTextColor(fg)
		panelReason.setTextColor(dim)
		panelTranscriptLabel.setTextColor(dim)
		panelTranscript.setTextColor(dim)
		panelTranscript.setBackgroundColor(bar)

		// The action button is a `MaterialButton`: it reads `colorPrimary` and
		// `colorOnPrimary` in its constructor, so it is the one view that would keep
		// the *other* mode's colours — including the accent, which is deliberately not
		// the same blue in both palettes. Setting the tint list replaces the colour
		// and keeps the shape and its ripple.
		ViewCompat.setBackgroundTintList(panelAction, ColorStateList.valueOf(getColor(R.color.accent)))
		panelAction.setTextColor(getColor(R.color.on_accent))

		// A live page paints its own background; this one shows before the document
		// paints, and around a page shorter than the window.
		for (entry in views.values) entry.view.setBackgroundColor(bg)

		applySystemBars()
	}

	/**
	 * The two system bars, which invert with the shell.
	 *
	 * The **icons** are the part that matters — dark icons on a light bar and the
	 * reverse. The bar colours themselves come from the theme and are a no-op on
	 * API 35+, where every app is drawn edge to edge and what shows behind the clock
	 * is this window's own background: `applyWindowInsets` is what keeps the first row
	 * out from under it.
	 */
	private fun applySystemBars() {
		val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
			Configuration.UI_MODE_NIGHT_YES
		@Suppress("DEPRECATION")
		window.statusBarColor = getColor(R.color.bar)
		@Suppress("DEPRECATION")
		window.navigationBarColor = getColor(R.color.bar)
		WindowCompat.getInsetsController(window, window.decorView).apply {
			isAppearanceLightStatusBars = !night
			isAppearanceLightNavigationBars = !night
		}
	}

	/**
	 * Where a light/dark switch arrives, because `uiMode` is in this activity's
	 * `configChanges`. The other configurations that land here — rotation, screen
	 * size, the keyboard — repaint the same way, which is harmless: both calls are
	 * idempotent and neither touches a session.
	 */
	override fun onConfigurationChanged(newConfig: Configuration) {
		super.onConfigurationChanged(newConfig)
		applyChromeColors()
		render()
	}

	@SuppressLint("SetJavaScriptEnabled")
	private fun configureWebView(view: WebView) {
		with(view.settings) {
			// The page is the Harness UI, which is a real web application; without
			// JavaScript there is nothing to show.
			javaScriptEnabled = true
			// Off by default on Android, and the Harness UI stores its state in it.
			domStorageEnabled = true
			// The narrowest set that works. There is no `file://` content in this app,
			// so file access is off, and a remote page must not be able to reach local
			// files even if one were introduced by accident.
			allowFileAccess = false
			allowContentAccess = false
			@Suppress("DEPRECATION")
			allowFileAccessFromFileURLs = false
			@Suppress("DEPRECATION")
			allowUniversalAccessFromFileURLs = false
			// The page is served over plain http on loopback, which the web platform
			// treats as a trustworthy origin, so nothing legitimate is mixed. Leaving
			// this at NEVER_ALLOW means a page that tried to pull in an https
			// subresource would fail visibly rather than silently — which is the
			// behaviour to want while the client is new.
			mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
			// A desktop-width application, so ask for the wide viewport. A page that
			// declares `width=device-width` keeps its own declaration: this only decides
			// what happens when there is nothing to go on.
			useWideViewPort = true
			loadWithOverviewMode = true
		}
		view.webViewClient = object : WebViewClient() {
			/**
			 * Keep the WebView on the forward and nowhere else.
			 *
			 * The remote UI contains links, and a tap must not turn this window into a
			 * browser with no tab bar and no way back. Anything that is not the loopback
			 * origin this tab's own forward serves is handed to the system browser —
			 * which is also what the desktop app does with `shell.openExternal`.
			 */
			override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
				val host = request.url.host ?: return true
				val local = host == "127.0.0.1" || host == "localhost" || host == "::1"
				if (local) return false
				return try {
					startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url))
					true
				} catch (_: Exception) {
					true
				}
			}

			/**
			 * The renderer died — and the platform's default is to **kill the app**.
			 *
			 * `WebViewClient.onRenderProcessGone` returning `false`, which is what the
			 * base class does, makes the framework treat the crash as unrecoverable and
			 * take the whole process down with it. Every tab shares one renderer, so an
			 * out-of-memory in one remote page would end every session at once, and the
			 * operator would never learn which page did it. Returning `true` says this
			 * callback has dealt with it, and the process survives.
			 *
			 * **The SSH session is untouched by the renderer's death** — a renderer is a
			 * separate process that draws a page, while the session, the forward and the
			 * far side's `dsh web` live in this one — but the tab cannot go on claiming to
			 * be running, so its attempt and session are stopped with it. `FAILED` means
			 * "nothing is running" everywhere else in this file, and `stopServiceIfIdle`
			 * reads exactly that: leaving a live session behind a tab that says otherwise
			 * would make the service bookkeeping wrong. Nothing is wasted by it either —
			 * **Try again** goes through `maybeConnect` → `startSession`, which stops
			 * whatever was there and reconnects from scratch, so keeping the session would
			 * not have made the button cheaper.
			 *
			 * That rather than an automatic rebuild, because the page that killed a
			 * renderer once will do it again, and a loop with no way out is worse than a
			 * button.
			 */
			override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
				val id = views.entries.firstOrNull { it.value.view === view }?.key
				// Destroyed rather than hidden: a view whose renderer is gone can never
				// paint again, and leaving it in the tree would keep `renderContent` from
				// ever building its replacement.
				webHost.removeView(view)
				view.destroy()
				if (id != null) {
					views.remove(id)
					tabs.firstOrNull { it.device.id == id }?.let { owner ->
						owner.stop()
						owner.state = State.FAILED
						// `didCrash` is the platform's own answer to "the page died" versus
						// "the system killed it for memory", and the two read differently to
						// an operator: one is the page's fault, the other is the phone's.
						owner.error = getString(
							if (detail.didCrash()) R.string.renderer_crashed else R.string.renderer_killed,
							owner.device.display
						)
					}
				}
				// The session this tab was holding is gone, so this is one of the paths that
				// can leave the service with nothing to protect.
				stopServiceIfIdle()
				onMain { render() }
				return true
			}
		}
	}

	// ----------------------------------------------------------------- render

	/**
	 * The main thread, for callbacks that do not start on it.
	 *
	 * This exists because of a real crash, so it is worth being precise about the
	 * shape of the mistake rather than treating it as a lapse. `RemoteSession.open`
	 * runs on `Dispatchers.IO` — that is the point of it — and it reports progress
	 * through callbacks. A callback that touches a view therefore runs on a worker
	 * thread and the process dies with `CalledFromWrongThreadException`, which is
	 * what happened on the first real connect.
	 *
	 * Kotlin's dispatchers do **not** save you here: they only switch threads when the
	 * *dispatcher* changes, so a plain lambda invoked from inside `withContext(IO)`
	 * stays on the worker no matter what context the enclosing coroutine declared.
	 *
	 * Every callback that reaches a view goes through this, and `render` is marked so
	 * a reader can see the rule instead of inferring it.
	 */
	private fun onMain(body: () -> Unit) {
		if (android.os.Looper.myLooper() === android.os.Looper.getMainLooper()) body()
		else runOnUiThread(body)
	}

	@MainThread
	private fun render() {
		renderTabs()
		renderContent()
	}

	@MainThread
	private fun renderTabs() {
		tabsRow.removeAllViews()
		val inflater = LayoutInflater.from(this)
		tabs.forEachIndexed { index, tab ->
			val item = inflater.inflate(R.layout.tab, tabsRow, false)
			val dot = item.findViewById<View>(R.id.dot)
			val label = item.findViewById<TextView>(R.id.label)
			val close = item.findViewById<TextView>(R.id.close)

			label.text = tab.device.display
			label.setTypeface(null, if (index == activeIndex) Typeface.BOLD else Typeface.NORMAL)
			label.setTextColor(getColor(if (index == activeIndex) R.color.fg else R.color.dim))
			item.setBackgroundColor(getColor(if (index == activeIndex) R.color.bg else R.color.bar))

			// The dot's drawable is a selector over three of the view's own states, so a
			// new connection state cannot be added without deciding what it looks like.
			dot.isSelected = tab.state == State.RUNNING
			dot.isEnabled = tab.state != State.STARTING
			dot.isActivated = tab.state == State.FAILED

			// × stops the connection; the tab stays, because a tab is a configured
			// machine rather than a transient view. Removing the machine is the
			// long-press's job.
			close.visibility = if (tab.state == State.IDLE) View.INVISIBLE else View.VISIBLE
			close.setOnClickListener {
				stopTab(index)
				render()
			}

			// **Selecting a tab selects it, and does nothing else.** This used to end
			// in `maybeConnect(index)`, so tapping an idle tab opened a connection to
			// that machine — silently when the app holds a key, through a password
			// dialog when it does not. The operator asked to look at a tab, not to
			// dial out, and a phone on someone else's network doing that on a stray
			// tap is exactly the surprise the CONNECT button exists to avoid.
			// Reported from use.
			item.setOnClickListener {
				activeIndex = index
				render()
			}
			item.setOnLongClickListener {
				showDeviceDialog(tab.device)
				true
			}
			tabsRow.addView(item)
		}
	}

	/**
	 * One WebView per running session, and exactly one of them visible.
	 *
	 * **This replaced a single WebView whose URL was swapped.** That version looked
	 * equivalent and was not: switching between two running tabs always meant a
	 * different URL, so `loadUrl` ran and the remote Harness — a single-page
	 * application — restarted from zero every time the operator looked at another
	 * tab. It also meant a tab's page was destroyed the moment you left it, so
	 * returning to a tab showed whatever the server had for a fresh load rather
	 * than the conversation that was there. Reported from use, not from a test.
	 *
	 * The reconciliation is the desktop client's, which has kept one guest per tab
	 * since it was written: a view is created once per (tab, url) pair and then left
	 * alone, and switching tabs changes visibility and nothing else. A view whose
	 * URL no longer matches is the one case that rebuilds — which happens when a
	 * session is torn down and started again, and *that* is a new page by definition.
	 */
	private fun renderContent() {
		val tab = tabs.getOrNull(activeIndex)
		// Latched once, so the non-null check below is the compiler's too — a
		// `takeIf` in a local reads better but does not smart-cast at the use sites.
		val showing = tab?.takeIf { it.state == State.RUNNING }
		val session = showing?.session

		// Retire the views of tabs that are no longer running, and of a tab whose
		// session has been replaced. Destroying rather than hiding is deliberate: a
		// hidden WebView keeps its renderer process, its sockets and its cookies
		// alive, and a torn-down session must not leave that behind.
		for ((id, entry) in views.toList()) {
			val current = tabs.firstOrNull { it.device.id == id }
			if (current != null && current.state == State.RUNNING && current.session?.url == entry.url) continue
			webHost.removeView(entry.view)
			entry.view.destroy()
			views.remove(id)
		}

		if (showing == null || session == null) {
			webHost.visibility = View.GONE
			panel.visibility = View.VISIBLE
			renderPanel(tab)
			return
		}

		val existing = views[showing.device.id]
		val view = existing?.takeIf { it.url == session.url }?.view ?: run {
			val created = WebView(this)
			created.setBackgroundColor(getColor(R.color.bg))
			configureWebView(created)
			webHost.addView(
				created,
				android.widget.FrameLayout.LayoutParams(
					android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
					android.widget.FrameLayout.LayoutParams.MATCH_PARENT
				)
			)
			views[showing.device.id] = WebEntry(created, session.url)
			// Only ever navigated once, when it is made. Every later render finds this
			// entry and leaves it alone — that is the whole point.
			created.loadUrl(session.url)
			created
		}

		webHost.visibility = View.VISIBLE
		panel.visibility = View.GONE
		for (entry in views.values) entry.view.visibility = if (entry.view === view) View.VISIBLE else View.GONE
	}

	/** Draw the panel for a tab that is not showing a page. */
	private fun renderPanel(tab: Tab?) {
		if (tab == null) {
			panelTitle.text = getString(R.string.app_name)
			panelReason.text = getString(R.string.no_devices)
			panelAction.visibility = View.VISIBLE
			panelAction.text = getString(R.string.add_device)
			panelAction.setOnClickListener { showDeviceDialog(null) }
			showTranscript(emptyList())
			return
		}

		when (tab.state) {
			State.FAILED -> {
				panelTitle.text = getString(R.string.app_name) + " — " + tab.device.display
				panelReason.text = tab.error ?: "no reason was recorded"
				panelAction.visibility = View.VISIBLE
				panelAction.text = getString(R.string.retry)
				panelAction.setOnClickListener { maybeConnect(activeIndex) }
			}
			State.STARTING -> {
				panelTitle.text = tab.device.display
				// An automatic reconnect has something to say — the server stopped, and
				// this is attempt N. Showing the generic "Connecting…" over it would hide
				// the one fact the operator needs, which is that their session ended.
				panelReason.text = tab.error ?: getString(R.string.connecting)
				panelAction.visibility = View.GONE
			}
			else -> {
				panelTitle.text = tab.device.display
				panelReason.text = "${tab.device.user}@${tab.device.host}:${tab.device.sshPort}"
				panelAction.visibility = View.VISIBLE
				panelAction.text = getString(R.string.connect)
				panelAction.setOnClickListener { maybeConnect(activeIndex) }
			}
		}
		showTranscript(tab.lines)
	}

	/**
	 * Show the transcript, or hide it and its heading together.
	 *
	 * Both, because a heading with nothing under it reads as a section that failed to
	 * load — which is exactly the wrong impression on the panel an operator sees when
	 * something has already gone wrong.
	 */
	private fun showTranscript(lines: List<String>) {
		val visible = lines.isNotEmpty()
		panelTranscriptLabel.visibility = if (visible) View.VISIBLE else View.GONE
		panelTranscript.visibility = if (visible) View.VISIBLE else View.GONE
		panelTranscript.text = lines.joinToString("\n")
	}

	// -------------------------------------------------------------- connecting

	/**
	 * Connect, because the operator pressed the button that says so.
	 *
	 * There used to be a `force` flag, because the tab row called this too and had to
	 * be told not to disturb a tab that was already starting or running. The tab row
	 * no longer connects anything, so the button is the only caller and the flag went
	 * with it: a parameter that is always the same value is a second way to spell the
	 * only thing this does.
	 */
	private fun maybeConnect(index: Int) {
		val tab = tabs.getOrNull(index) ?: return

		// A key, when the app has one, replaces the prompt entirely — that is the
		// whole point of having generated it. A password is asked for only when
		// there is no key, and then held for the run rather than stored.
		if (Keys.exists()) {
			startSession(index, null)
			return
		}

		val password = passwords[tab.device.id]
		if (password == null) {
			askPassword(tab.device) { entered ->
				passwords[tab.device.id] = entered
				startSession(index, entered)
			}
			return
		}
		startSession(index, password)
	}

	/**
	 * Try again by itself when the remote server goes away.
	 *
	 * Measured, and the reason this exists: the far side's profile is configured
	 * `patchReload: live`, so an agent that edits a plugin — which is an ordinary thing
	 * to ask it to do — makes the Harness restart itself mid-turn. The old server exits,
	 * the port it announced dies with it, and the interface sits in
	 * `connection lost, retry #50` with nothing to act on.
	 *
	 * On a phone this should repair itself, so it does: a short backoff, a bounded
	 * number of attempts, and **a line that says what is happening**. Silent recovery
	 * would be worse here than the failure, because the conversation the operator was
	 * watching has stopped and they need to know that rather than wonder why the answer
	 * never came.
	 *
	 * The transcript of the attempt stays on screen, so a server that keeps dying says
	 * so in its own words rather than only in the app's.
	 */
	private fun reconnect(index: Int, detail: String) {
		val tab = tabs.getOrNull(index) ?: return
		val attempt = (reconnectAttempts[tab.device.id] ?: 0) + 1
		reconnectAttempts[tab.device.id] = attempt

		if (attempt > RECONNECT_ATTEMPTS) {
			reconnectAttempts.remove(tab.device.id)
			tab.state = State.FAILED
			tab.error = getString(R.string.reconnect_exhausted, tab.device.display, RECONNECT_ATTEMPTS, detail)
			// The budget was the only reason the service was still up: `stopTab` below
			// keeps it running while an attempt is pending, and this is the path where
			// there is no longer one.
			stopServiceIfIdle()
			render()
			return
		}

		// The attempt's identity is minted by `startSession` once the wait below is over,
		// not here. A token taken now would only be replaced a moment later, and there is
		// nothing for it to guard in between: the tab has no session at this point.
		tab.state = State.STARTING
		tab.error = getString(R.string.reconnecting, tab.device.display, detail, attempt, RECONNECT_ATTEMPTS)
		tab.lines = tab.lines + "[--:--:--] ${tab.error?.replace('\n', ' ')}"
		render()

		tab.connectJob = lifecycleScope.launch {
			// A short wait, then the same connect a tap would do. Reconnecting into a
			// server that is still shutting down just fails again, and each failure costs
			// the operator another few seconds of not knowing.
			delay(RECONNECT_DELAY_MS)
			startSession(index, passwords[tab.device.id], isReconnect = true)
		}
	}

	/**
	 * Bring one tab up.
	 *
	 * **Every suspension point below re-checks that this attempt still owns this tab**,
	 * and the `finally` is what makes that a guarantee rather than a hope. `open` can
	 * return an open session — a live SSH connection, a bound forward, a `dsh web` on
	 * the far side — to a coroutine that has already been cancelled, and a cancelled
	 * coroutine discards that value and never reaches the `withContext(Dispatchers.Main)`
	 * that would have adopted it. Nothing else in the app would ever hold a reference to
	 * it, so nothing would ever stop it. Cancelling a job is therefore not enough by
	 * itself: whatever the attempt opened has to be stopped by the attempt.
	 *
	 * @param isReconnect true when this attempt follows the server going away, which
	 *   keeps the tab in a state that says so instead of clearing the explanation.
	 */
	private fun startSession(index: Int, secret: String?, isReconnect: Boolean = false) {
		val tab = tabs.getOrNull(index) ?: return
		tab.stop()
		// A fresh identity, so a result from an attempt that has just been superseded —
		// or from one whose tab was removed — cannot be mistaken for this one's.
		val attempt = Token()
		tab.attempt = attempt
		tab.state = State.STARTING
		if (!isReconnect) {
			tab.error = null
			reconnectAttempts.remove(tab.device.id)
		}
		tab.lines = emptyList()
		render()

		tab.connectJob = lifecycleScope.launch {
			var opened: RemoteSession? = null
			try {
				val result = RemoteSession.open(
					device = tab.device,
					// The keystore and the key file are read here, on `Dispatchers.IO` —
					// `RemoteSession.open` switches to IO itself, and this argument is
					// evaluated before it does. Measured cost of getting that wrong: on the
					// main thread this is `EncryptedSharedPreferences` → `MasterKey` →
					// AndroidKeyStore decryption plus a private-key file read, on every
					// connect, on the thread that is drawing the tab bar. `Keys` is where
					// that work is defined; the README's threading section is why it is
					// spelled out rather than left to the reader.
					credential = withContext(Dispatchers.IO) { Keys.credentialFor(secret) },
					// `onLine` does **not** run on the main thread: the connect runs on
					// Dispatchers.IO, so every transcript line arrives from a worker, and
					// touching a view from there throws CalledFromWrongThreadException and
					// kills the process. Measured, on the first real connect: the crash was
					// `Expected: main  Calling: DefaultDispatcher-worker-2` at
					// `showTranscript`. Every UI touch in this callback is deferred.
					onLine = { line ->
						// Dropped once this attempt is stale — the tab is gone, or × or a
						// newer attempt has taken it over. `index` may no longer name this
						// tab, so appending by index would write into a different machine's
						// transcript.
						if (isCurrentAttempt(tab, attempt)) {
							tab.lines = tab.lines + line
							if (index == activeIndex && tab.state == State.STARTING) {
								val lines = tab.lines
								onMain { showTranscript(lines) }
							}
						}
					},
					// Same reason: this fires from the transport thread, and both of its
					// effects end in a view.
					onHostKey = { key ->
						val id = tab.device.id
						// A pin written for a tab that is gone is not wrong, exactly, but
						// `rememberHostKey` writes the book and it is the same question, so
						// it gets the same answer as everything else here.
						if (isCurrentAttempt(tab, attempt)) onMain { rememberHostKey(id, key) }
					}
				)

				// A session that came back after this attempt was cancelled belongs to
				// nobody: the tab it was opened for has moved on or gone, and the UI this
				// would have reported to is no longer showing it. It is stopped below,
				// before this coroutine returns.
				if (result is SessionStart.Ok) opened = result.session

				// Back on the main thread for everything that touches the UI. The connect
				// above suspends, so this continuation resumes on the scope's dispatcher,
				// but saying so explicitly is what keeps a later edit from reintroducing the
				// same bug one line at a time.
				withContext(Dispatchers.Main) {
					// The last suspension point, and the only one that matters most: a
					// cancellation between `open` returning and here is the leak this whole
					// shape exists to close.
					if (!isCurrentAttempt(tab, attempt)) return@withContext
					when (result) {
						is SessionStart.Ok -> {
							tab.session = result.session
							tab.state = State.RUNNING
							tab.lines = result.session.transcript
							// The probe result is cached in the book the same way the desktop app
							// caches it: one round trip per machine, not one per connect.
							cachePlatform(tab.device.id, result.session.platform)
							SessionService.start(this@MainActivity, tab.device.display)
							// The remote program can end while the SSH session stays up — a plugin
							// that restarts the server, or a crash — and then the forward points at
							// a dead port and the interface retries for ever with nothing to say
							// why. The exec channel closing is the only signal that exists, so it is
							// watched, and the tab is turned into something the operator can act on
							// rather than a spinner that never resolves.
							result.session.watchUntilEnded { code ->
								if (tab.session !== result.session || tab.state != State.RUNNING) return@watchUntilEnded
								// Stop first, then write the reason: `stopTab` deliberately
								// resets a stopped tab to idle with no error, which is right for
								// the × button and wrong here. Measured: the other order made the
								// panel say `no reason was recorded`, which is exactly the
								// unhelpful outcome this watchdog exists to remove.
								//
								// `keepService` because a reconnect follows in the next two
								// statements: deactivating a foreground service and asking for it
								// again a few milliseconds later leaves the process — and the
								// tunnel inside it — unprotected through the whole retry, which is
								// the window this service exists to cover.
								stopTab(tabs.indexOf(tab), keepService = true)
								val detail = if (code >= 0) " (exit code $code)" else ""
								// A successful connect clears the retry budget, so a machine that
								// restarts once an hour is never treated as a machine that keeps
								// dying.
								reconnectAttempts.remove(tab.device.id)
								reconnect(tabs.indexOf(tab), detail)
							}
						}
						is SessionStart.Failed -> {
							tab.state = State.FAILED
							tab.error = result.error
							tab.lines = result.transcript
							// A failed password is very likely wrong rather than stale, so
							// forget it and ask again next time instead of retrying the same
							// string.
							passwords.remove(tab.device.id)
						}
					}
					render()
				}
			} finally {
				// Reached however this attempt ended — adopted, failed, cancelled at any
				// suspension point, or thrown out of the credential read. The tab never
				// took this session, so this is the only reference to it left anywhere.
				if (opened != null && tab.session !== opened) {
					opened.stop()
				}
				// A cancelled attempt also leaves the tab saying STARTING, which offers no
				// CONNECT button — `renderPanel` hides it — so × would be the only way out.
				// Nothing is running any more, so the tab goes back to the state that has a
				// button. Skipped once the window is gone, which is the other way this block
				// is reached: the destroy has already reset every tab itself.
				if (!destroyed && isCurrentAttempt(tab, attempt) && tab.state == State.STARTING) {
					tab.state = State.IDLE
					render()
				}
				stopServiceIfIdle()
			}
		}
	}

	/**
	 * True when `attempt` still owns `tab`, and `tab` is still in the bar.
	 *
	 * Both halves are needed and neither implies the other: a tab removed from `tabs`
	 * keeps its token, and a tab taken over by a newer attempt keeps its identity. Only
	 * the second question is about the attempt; the first is about the tab.
	 *
	 * Identity (===) rather than equality throughout: `Tab` and `Token` are compared by
	 * reference on purpose, because two attempts at the same machine are different
	 * attempts and must not be confused for one another. A caller that also holds an
	 * index asks the same question through `tabs.getOrNull(index) === tab`, which
	 * answers both halves in one read.
	 */
	private fun isCurrentAttempt(tab: Tab, attempt: Token): Boolean =
		tab.attempt === attempt && tabs.any { it === tab }

	/**
	 * Take the foreground service down when nothing needs it.
	 *
	 * Called after every path that can end a session, because the service is what keeps
	 * the process — and therefore the tunnel inside it — out of the cached state. It
	 * stays up while **either** a session is running or starting, **or** a tab still has
	 * reconnect budget: a reconnect that has not started yet is exactly the window the
	 * service exists to protect, and using the budget rather than a "pending" flag means
	 * there is no flag to forget to clear.
	 */
	private fun stopServiceIfIdle() {
		val busy = tabs.any {
			it.state == State.RUNNING || it.state == State.STARTING ||
				(reconnectAttempts[it.device.id] ?: 0) > 0
		}
		if (!busy) SessionService.stop(this)
	}

	/**
	 * Stop one tab's connection, and give it back the state that offers CONNECT.
	 *
	 * @param keepService true when a reconnect follows immediately, which is the
	 *   watchdog's case. The service must not be deactivated and asked for again inside
	 *   a retry: the process — and the tunnel inside it — is unprotected for the whole
	 *   window between the two, which is the exact window [SessionService] exists to
	 *   cover. It is stopped later, when the retry budget is exhausted or when the tab
	 *   really is idle; `stopServiceIfIdle` decides that.
	 */
	private fun stopTab(index: Int, keepService: Boolean = false) {
		val tab = tabs.getOrNull(index) ?: return
		tab.stop()
		tab.state = State.IDLE
		tab.error = null
		// × ends the retry as well as the attempt. The budget is what `stopServiceIfIdle`
		// reads as "a reconnect is pending", and dropping it here is what keeps the
		// difference between a retry that is coming and one the operator just cancelled.
		reconnectAttempts.remove(tab.device.id)
		// Nothing to navigate here any more: `renderContent` retires this tab's view
		// because it is no longer running, and destroys it. The `about:blank` that
		// used to be loaded first was a step towards the same end, on a screen the
		// operator never sees.
		if (!keepService) stopServiceIfIdle()
	}

	/** Persist the host key learned on a first connect, so a change is later caught. */
	private fun rememberHostKey(deviceId: String, key: String) {
		DeviceBook.mutate { stored ->
			stored.map { if (it.id == deviceId) it.copy(hostKey = key) else it }
		}
		tabs.firstOrNull { it.device.id == deviceId }?.let { tab ->
			tab.device = tab.device.copy(hostKey = key)
		}
	}

	private fun cachePlatform(deviceId: String, platform: Remote.Platform) {
		if (tabs.firstOrNull { it.device.id == deviceId }?.device?.platform == platform) return
		DeviceBook.mutate { stored ->
			stored.map { if (it.id == deviceId) it.copy(platform = platform) else it }
		}
		tabs.firstOrNull { it.device.id == deviceId }?.let { tab ->
			tab.device = tab.device.copy(platform = platform)
		}
	}

	// ---------------------------------------------------------------- dialogs

	private fun askPassword(device: Device, onEntered: (String) -> Unit) {
		val field = EditText(this).apply {
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
			hint = getString(R.string.field_password)
		}
		AlertDialog.Builder(this)
			.setTitle(device.user + "@" + device.host)
			.setMessage(R.string.need_password)
			.setView(field)
			.setPositiveButton(R.string.connect) { _, _ -> onEntered(field.text.toString()) }
			.setNegativeButton(R.string.cancel, null)
			.show()
	}

	private fun showDeviceDialog(existing: Device?) {
		val view = LayoutInflater.from(this).inflate(R.layout.dialog_device, FrameLayout(this), false)
		val label = view.findViewById<EditText>(R.id.label)
		val host = view.findViewById<EditText>(R.id.host)
		val user = view.findViewById<EditText>(R.id.user)
		val port = view.findViewById<EditText>(R.id.port)
		val directory = view.findViewById<EditText>(R.id.directory)
		val password = view.findViewById<EditText>(R.id.password)

		// The key section is the same screen whether a machine is being added or
		// edited, because the key belongs to the app rather than to a machine: one key
		// is pasted into as many `authorized_keys` files as the operator likes.
		view.findViewById<TextView>(R.id.keyState).text =
			getString(if (Keys.exists()) R.string.key_ready else R.string.key_none)
		view.findViewById<android.widget.Button>(R.id.keyButton).setOnClickListener { showKeyDialog() }

		existing?.let {
			label.setText(it.label)
			host.setText(it.host)
			user.setText(it.user)
			port.setText(it.sshPort.toString())
			directory.setText(it.directory ?: "")
		}

		val builder = AlertDialog.Builder(this)
			.setTitle(if (existing == null) R.string.add_title else R.string.edit_title)
			.setView(view)
			.setPositiveButton(R.string.save) { _, _ ->
				val enteredPort = port.text.toString().trim().toIntOrNull() ?: Remote.DEFAULT_SSH_PORT
				val saved = existing?.copy(
					label = label.text.toString().trim().ifBlank { host.text.toString().trim() },
					host = host.text.toString().trim(),
					user = user.text.toString().trim(),
					sshPort = enteredPort,
					directory = directory.text.toString().trim().takeIf { it.isNotEmpty() }
				) ?: Device.create(
					label = label.text.toString().trim(),
					host = host.text.toString().trim(),
					user = user.text.toString().trim(),
					sshPort = enteredPort,
					directory = directory.text.toString().trim().takeIf { it.isNotEmpty() }
				)
				if (saved.host.isEmpty() || saved.user.isEmpty()) {
					toast("host and user are required")
					return@setPositiveButton
				}
				// Refused here as well as at connect time, so a directory no shell can
				// be given is reported while the operator is looking at the field they
				// typed it into. `RemoteSession` checks again because the book is a
				// JSON file that can also be edited by hand or copied from the desktop.
				Remote.directoryProblem(saved.directory)?.let { problem ->
					toast(problem)
					return@setPositiveButton
				}
				password.text.toString().takeIf { it.isNotEmpty() }?.let { passwords[saved.id] = it }
				upsert(saved)
			}
			.setNegativeButton(R.string.cancel, null)

		if (existing != null) {
			builder.setNeutralButton(R.string.remove) { _, _ -> confirmRemove(existing) }
		}
		builder.show()
	}

	// -------------------------------------------------------------------- key

	/**
	 * The key screen: generate one, copy the half that goes on the machine.
	 *
	 * The public key is shown in a **selectable, monospaced** field as well as behind a
	 * copy button, on purpose. The copy button is the easy path, but this is the one
	 * string in the app that has to survive leaving it — through a chat message, a
	 * terminal, a password manager — and a field that cannot be selected would make
	 * the operator transcribe it by eye.
	 */
	private fun showKeyDialog() {
		val pad = (resources.displayMetrics.density * 22).toInt()
		val column = android.widget.LinearLayout(this).apply {
			orientation = android.widget.LinearLayout.VERTICAL
			setPadding(pad, pad / 2, pad, 0)
		}

		val state = TextView(this).apply {
			setTextColor(getColor(R.color.dim))
			textSize = 12f
		}
		column.addView(state)

		val keyView = TextView(this).apply {
			setTextColor(getColor(R.color.fg))
			typeface = android.graphics.Typeface.MONOSPACE
			textSize = 10.5f
			setTextIsSelectable(true)
			setBackgroundColor(getColor(R.color.bar))
			setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
			visibility = View.GONE
		}
		column.addView(keyView)

		val howto = TextView(this).apply {
			setTextColor(getColor(R.color.dim))
			textSize = 11f
			setPadding(0, pad / 2, 0, 0)
			visibility = View.GONE
		}
		column.addView(howto)

		val scroll = android.widget.ScrollView(this).apply { addView(column) }

		lateinit var dialog: AlertDialog

		// One refresh, and it is a suspend one. `Keys.publicKey()` is not a cheap read:
		// it decrypts the passphrase through `EncryptedSharedPreferences` and the Android
		// Keystore, reads the private key file, and parses the key. On the main dispatcher
		// that is a stall on the frame that opens this dialog, and this screen used to pay
		// for it **twice** for one value that had not changed between the two: once in the
		// `onShow` below and once immediately after `show()` returned.
		suspend fun refresh() {
			val public = withContext(Dispatchers.IO) { Keys.publicKey() }
			if (public == null) {
				state.text = getString(R.string.key_none)
				keyView.visibility = View.GONE
				howto.visibility = View.GONE
			} else {
				state.text = getString(R.string.key_ready)
				keyView.text = public
				keyView.visibility = View.VISIBLE
				howto.text = getString(R.string.key_howto)
				howto.visibility = View.VISIBLE
			}
		}

		// Suspending because `refresh` is, so both callers run it from a coroutine on the
		// main dispatcher: that is what puts the *read* on IO while every `getString` and
		// view assignment stays where it has to be. `Keys.generate` and `Keys.forget`
		// stay on the main thread deliberately — they are a button press, not a frame
		// that has to be drawn, and moving them would change what the try/catch below
		// covers.
		val generate: suspend (android.content.DialogInterface, Int) -> Unit = { _, _ ->
			try {
				Keys.generate()
				toast(getString(R.string.key_generated))
			} catch (error: Throwable) {
				toast(getString(R.string.key_cannot_generate, error.message ?: error.javaClass.simpleName))
			}
			refresh()
		}

		dialog = AlertDialog.Builder(this)
			.setTitle(R.string.key_title)
			.setView(scroll)
			.setPositiveButton(if (Keys.exists()) R.string.key_copy else R.string.key_generate, null)
			.setNeutralButton(if (Keys.exists()) R.string.key_replace else R.string.close, null)
			.setNegativeButton(R.string.close, null)
			.create()

		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				lifecycleScope.launch {
					if (Keys.exists()) {
						val public = withContext(Dispatchers.IO) { Keys.publicKey() }
						if (public != null) {
							val clipboard = getSystemService(android.content.ClipboardManager::class.java)
							clipboard.setPrimaryClip(android.content.ClipData.newPlainText("SSH public key", public))
							toast(getString(R.string.key_copied))
						}
					} else {
						generate(dialog, 0)
					}
				}
			}
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
				if (!Keys.exists()) {
					dialog.dismiss()
					return@setOnClickListener
				}
				AlertDialog.Builder(this)
					.setTitle(R.string.key_title)
					.setMessage(R.string.key_replace_confirm)
					.setPositiveButton(R.string.key_replace) { _, _ ->
						// Generation is a button press, not a frame, so it stays here on the
						// main thread with the try/catch that reports why it failed. Of the
						// three steps, only the read-back inside `refresh` decrypts a key
						// again, and that is the one that moved to IO.
						Keys.forget()
						lifecycleScope.launch { generate(dialog, 0) }
					}
					.setNegativeButton(R.string.cancel, null)
					.show()
			}
			// The only refresh this dialog gets on the way up. `show()` delivers
			// `onShow` after the window exists, so there is nothing to refresh before it.
			lifecycleScope.launch { refresh() }
		}
		dialog.show()
	}

	private fun confirmRemove(device: Device) {		AlertDialog.Builder(this)
			.setTitle(device.display)
			.setMessage(R.string.remove_confirm)
			.setPositiveButton(R.string.remove) { _, _ -> remove(device) }
			.setNegativeButton(R.string.cancel, null)
			.show()
	}

	// ------------------------------------------------------------------- book

	private fun upsert(device: Device) {
		DeviceBook.mutate { stored ->
			val at = stored.indexOfFirst { it.id == device.id }
			if (at == -1) stored + device else stored.mapIndexed { i, d -> if (i == at) device else d }
		}
		val at = tabs.indexOfFirst { it.device.id == device.id }
		if (at == -1) {
			tabs += Tab(device)
			if (activeIndex == -1) activeIndex = 0
		} else {
			tabs[at].device = device
		}
		render()
	}

	private fun remove(device: Device) {
		val at = tabs.indexOfFirst { it.device.id == device.id }
		if (at != -1) {
			// The whole attempt goes with the tab, not only the session that is up. A
			// connect in flight used to survive its tab being removed: the tab left the
			// list, the job kept running, and a successful open still ran `tab.session =
			// …; SessionService.start(…)` for a tab no UI could reach. The watchdog then
			// called `stopTab(tabs.indexOf(tab))` with -1 and returned without doing
			// anything, so that `dsh web` stayed up on the far side for the life of the
			// process. Cancelling the attempt stops whatever it opened — see the `finally`
			// in `startSession`.
			val tab = tabs[at]
			tab.stop()
			tabs.removeAt(at)
		}
		DeviceBook.mutate { stored -> stored.filterNot { it.id == device.id } }
		passwords.remove(device.id)
		reconnectAttempts.remove(device.id)
		activeIndex = when {
			tabs.isEmpty() -> -1
			activeIndex >= tabs.size -> tabs.size - 1
			else -> activeIndex
		}
		stopServiceIfIdle()
		render()
	}

	// ------------------------------------------------------------------ misc

	private fun askForNotifications() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
		if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
		// A foreground service needs no such permission to *run* — without it the
		// notice simply does not appear in the drawer, which is worth asking about
		// once because a tunnel nobody can see is a tunnel nobody can stop.
		requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
	}

	/**
	 * A short message, from the main thread, and never from a window that has gone.
	 *
	 * The guard is here rather than at the call sites because the key dialog's buttons now
	 * run in coroutines — they suspend on the keystore read — and a dialog can be
	 * dismissed, or the activity destroyed, while one is waiting. Showing a toast from a
	 * destroyed activity is an exception on API 30+, and there is nothing left to show it
	 * to in any case.
	 */
	@MainThread
	private fun toast(text: String) {
		if (isFinishing || isDestroyed) return
		Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
	}

	private companion object {
		/** Attempts before the app stops trying and asks the operator. */
		const val RECONNECT_ATTEMPTS = 3

		/** How long to wait before an automatic reconnect; the old server needs to go. */
		const val RECONNECT_DELAY_MS = 2_500L
	}
}
