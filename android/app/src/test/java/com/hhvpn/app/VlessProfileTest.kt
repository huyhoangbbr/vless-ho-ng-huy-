package com.hhvpn.app

import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test

class VlessProfileTest {
    private val id = "6d10ce91-3a59-44ce-8dce-f4cc06633278"

    @Test
    fun parsesCommonLinks() {
        val profile = VlessProfile.parse("vless://$id@example.com:443?security=tls&type=ws&path=%2Fedge#Test%20Server")
        assertEquals("Test Server", profile.name)
        assertEquals("ws", profile.transport)
    }

    @Test
    fun rejectsUnsafeAndUnsupportedLinks() {
        assertThrows(IllegalArgumentException::class.java) {
            VlessProfile.parse("vless://$id@example.com:443?security=tls&allowInsecure=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VlessProfile.parse("vless://$id@example.com:443?type=quic")
        }
        assertThrows(IllegalArgumentException::class.java) {
            VlessProfile.parse("vless://$id@example.com:443?type=tcp&type=ws")
        }
    }
}
