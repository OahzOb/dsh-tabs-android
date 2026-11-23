package dev.dshtabs

import android.webkit.CookieManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one platform fact the cookie cleanup rests on, checked on a device.
 *
 * `dropDeadAuthCookies` removes a cookie by writing an empty one with `Max-Age=0`
 * under the same name, path and host, because `CookieManager` has no "remove one
 * cookie" call. Whether that replacement really removes an `HttpOnly` cookie — and
 * whether the jar then stops reporting it — is WebView's behaviour, not this client's,
 * so [AuthCookiesTest] cannot answer it and this runs on a device.
 *
 * **On an emulator, not on the tablet.** This is an instrumented test, so Gradle
 * installs a debuggable build, and `android:debuggable` is what makes the diagnostic
 * probe answer on this app's exported launcher activity. See the README's signing
 * section: the device this client is used against never gets a debug install.
 */
@RunWith(AndroidJUnit4::class)
class CookieJarDeviceTest {

	@Test
	fun `a dead dsh-auth cookie is removed by name`() {
		InstrumentationRegistry.getInstrumentation().runOnMainSync {
			// The jar belongs to the WebView provider, and asking for it before the
			// provider is loaded can answer null — creating one is what brings it up.
			WebView(InstrumentationRegistry.getInstrumentation().targetContext).destroy()

			val jar = CookieManager.getInstance()
			jar.setAcceptCookie(true)
			// One loopback URL for both cookies: cookies have no port dimension, which
			// is the reason this cleanup exists at all.
			val url = "http://127.0.0.1:49999/"
			val dead = AuthCookies.nameFor("127.0.0.1:49998")
			val live = AuthCookies.nameFor("127.0.0.1:49999")
			jar.setCookie(url, "$dead=dead; Path=/; HttpOnly")
			jar.setCookie(url, "$live=live; Path=/; HttpOnly")
			jar.flush()

			val before = jar.getCookie(url).orEmpty()
			assertTrue("both cookies were not stored, so the check would pass vacuously: $before", before.contains(dead) && before.contains(live))

			jar.setCookie(url, "$dead=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
			jar.flush()

			val after = jar.getCookie(url).orEmpty()
			assertFalse(
				"the dead cookie survived a Max-Age=0 replacement, so `dropDeadAuthCookies` " +
					"deletes nothing and the jar keeps growing: $after",
				after.contains(dead)
			)
			assertTrue(
				"deleting the dead cookie took the live one with it: $after",
				after.contains(live)
			)
		}
	}
}
