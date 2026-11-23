package dev.dshtabs

import java.security.MessageDigest
import java.util.Base64

/**
 * The `dsh-auth-*` cookies the Harness mints, and which of them a live session can
 * still use.
 *
 * **Why the name has to be read at all.** `dsh web` authenticates a browser with a
 * cookie named for the authority it was reached on — `dsh-auth-` plus
 * base64url(SHA-256(`host:port`)) — and every connect forwards a *new* loopback port.
 * Browsers key cookies by host and ignore the port, so every cookie from every past
 * connection rides on every request to `127.0.0.1`, and past Node's 16 KiB header
 * limit the server answers **431** before it reads anything. `CookieManager` has no
 * API to enumerate the jar and, below the multi-profile API, no per-WebView
 * partition, so the largest cleanup that can be made **local to one tab** is to
 * delete the cookies whose authority no longer exists — which needs the name the
 * server derived, computed the same way here.
 *
 * That is a dependency on a server implementation detail, deliberately and narrowly:
 * it is `cookieName` in `dsh-client-connection`. If it ever changes, this stops
 * matching and the app falls back to the whole-jar clear it always had; nothing
 * breaks in a way that is worse than the old behaviour. [AuthCookiesTest] pins one
 * vector computed from that package, so the change fails a test on the build machine
 * rather than in an operator's cookie jar.
 */
internal object AuthCookies {

	/** The prefix `dsh-client-connection` gives its browser-session cookie. */
	const val PREFIX = "dsh-auth-"

	/** Every forward this app opens binds loopback, which is the cookie's host. */
	const val HOST = "127.0.0.1"

	/**
	 * The cookie name the Harness mints for one authority.
	 *
	 * The authority is the request's `host` header — `127.0.0.1:<forwarded port>` —
	 * which is also what the client asks the jar for when it checks the name back.
	 */
	fun nameFor(authority: String): String {
		val digest = MessageDigest.getInstance("SHA-256")
			.digest(authority.toByteArray(Charsets.UTF_8))
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
	}

	/**
	 * The names in one `Cookie` header value, in the order the jar returned them.
	 *
	 * Only the name is read. Everything after the first `=` is the signed value, which
	 * this never needs and never touches; a value is base64url and can hold `.` and
	 * `-`, so it must not be split on anything else.
	 */
	fun names(header: String?): List<String> {
		if (header.isNullOrBlank()) return emptyList()
		return header
			.split(';')
			.map { it.trim() }
			.filter { it.isNotEmpty() }
			.map { it.substringBefore('=').trim() }
			.filter { it.isNotEmpty() }
	}

	/**
	 * The names in [header] whose authority no live session owns.
	 *
	 * Only `dsh-auth-` names are candidates: a cookie without that prefix is not this
	 * client's to delete, even when it shares the host.
	 */
	fun deadNames(header: String?, liveAuthorities: Collection<String>): List<String> {
		val live = liveAuthorities.mapTo(mutableSetOf()) { nameFor(it) }
		return names(header).filter { it.startsWith(PREFIX) && it !in live }
	}
}
