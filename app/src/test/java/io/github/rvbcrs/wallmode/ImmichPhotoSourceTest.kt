package io.github.rvbcrs.wallmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ImmichPhotoSourceTest {
    @Test
    fun acceptsOnlyLongPublicShareUrlsAndSameOriginRedirects() {
        val key = "Abc_def-123".repeat(6)
        assertEquals(
            ImmichShareRef("http://192.168.0.248:2283", key),
            parseImmichShareUrl(" http://192.168.0.248:2283/share/$key/ ")
        )
        assertNull(parseImmichShareUrl("https://photos.example/s/wallmode"))
        assertNull(parseImmichShareUrl("https://user:secret@photos.example/share/$key"))
        assertNull(parseImmichShareUrl("https://photos.example/share/short"))
        assertNull(parseImmichShareUrl("https://photos.example/share/$key?download=true"))
        assertNull(parseImmichShareUrl("http://photos.example/share/$key"))
        assertTrue(isPrivateImmichHost("192.168.0.247"))
        assertTrue(isPrivateImmichHost("immich.local"))
        assertFalse(isPrivateImmichHost("fc-public.example"))

        val origin = URL("https://photos.example")
        assertTrue(isSameImmichOrigin(origin, URL("https://PHOTOS.example:443/api/shared-links/me")))
        assertFalse(isSameImmichOrigin(origin, URL("http://photos.example/api/shared-links/me")))
        assertFalse(isSameImmichOrigin(origin, URL("https://evil.example/api/shared-links/me")))
        assertFalse(isSameImmichOrigin(origin, URL("https://secret@photos.example/api/shared-links/me")))
    }
}
