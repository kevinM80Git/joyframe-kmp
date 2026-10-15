// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import io.github.rehaancubess.joyframe.input.GamepadButton
import java.io.DataInputStream
import java.net.Socket
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The server through a real socket: the page, the handshake, input in, info and vibration out, leaving. */
class PhonePadsTest {
    private val pads = PhonePads(port = 47960)
    @AfterTest fun stop() = pads.close()

    /** A minimal browser-side WebSocket: masked text frames out, server frames in. */
    private class Client(val socket: Socket) {
        constructor(port: Int) : this(Socket("127.0.0.1", port))
        val input = DataInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        init {
            socket.soTimeout = 3000
            out.write("GET /ws HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n".encodeToByteArray())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
            check(head.startsWith("HTTP/1.1 101")) { head }
            // RFC 6455's own example key and answer.
            check("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" in head) { head }
        }
        fun send(text: String) {
            val p = text.encodeToByteArray(); val mask = Random.nextBytes(4)
            out.write(0x81); out.write(0x80 or p.size); out.write(mask)
            out.write(ByteArray(p.size) { (p[it].toInt() xor mask[it and 3].toInt()).toByte() }); out.flush()
        }
        fun receive(): String = WebSocketFrames.read(input)!!.payload.decodeToString()
        fun close() = socket.close()
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        repeat(100) { if (check()) return; Thread.sleep(20) }
        error("timed out: $what")
    }

    @Test fun pagePadInfoVibrationAndLeaving() {
        assertTrue(pads.start())
        val port = pads.url?.substringAfterLast(':')?.trimEnd('/')?.toInt() ?: 47960
        // The page.
        Socket("127.0.0.1", port).use { s ->
            s.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".encodeToByteArray())
            val text = s.getInputStream().readBytes().decodeToString()
            assertTrue(text.startsWith("HTTP/1.1 200"), text.take(80))
            assertTrue("<title>Controller</title>" in text)
        }
        assertTrue(pads.pollAll().isEmpty())

        val phone = Client(port)
        phone.send("hello abc123")
        phone.send("in 0.5 1 0 ${PhonePadProtocol.A}")
        waitFor("the phone's pad") { pads.pollAll().singleOrNull()?.state?.leftStickX == 0.5f }
        val pad = pads.pollAll().single()
        assertEquals("phone-abc123", pad.id)
        assertEquals(1f, pad.state.rightTrigger)
        assertTrue(GamepadButton.South in pad.state.buttons)
        assertTrue(pads.pollAll() === pads.pollAll(), "no new list while nothing changes")

        // A tap released before the game polls: still seen down once, then up.
        phone.send("in 0 0 0 ${PhonePadProtocol.B}"); phone.send("in 0 0 0 0")
        Thread.sleep(150)
        assertTrue(GamepadButton.East in pads.pollAll().single().state.buttons, "a quick tap is seen")
        assertTrue(GamepadButton.East !in pads.pollAll().single().state.buttons, "and released on the next poll")

        pads.show(pad.id, PhonePadInfo("P2", 0xFFFF3DA8.toInt(), PhoneLayout.DRIVE, "3RD · LAP 2/3"))
        assertEquals("info DRIVE ff3da8 P2 3RD · LAP 2/3", phone.receive())
        pads.rumble(pad.id, 100, 1f)
        assertEquals("vib 100", phone.receive())
        phone.send("ping 12.5")
        assertEquals("pong 12.5", phone.receive())
        phone.send("rtt 23")
        waitFor("the round trip") { pads.roundTripMs(pad.id) == 23 }

        // The same phone again (a reconnect): the same pad, and it is told what to show again.
        phone.close()
        waitFor("the phone gone") { pads.pollAll().isEmpty() }
        val again = Client(port)
        again.send("hello abc123")
        assertEquals("info DRIVE ff3da8 P2 3RD · LAP 2/3", again.receive())
        again.send("in 0 0 1 0")
        waitFor("back") { pads.pollAll().singleOrNull()?.state?.leftTrigger == 1f }
        assertEquals("phone-abc123", pads.pollAll().single().id)
        again.close()
    }

    @Test fun protocol() {
        val menu = assertNotNull(PhonePadProtocol.parseInput("in 0 0 0 ${PhonePadProtocol.UP or PhonePadProtocol.B}"))
        assertEquals(1f, menu.leftStickY)
        assertTrue(GamepadButton.DpadUp in menu.buttons && GamepadButton.East in menu.buttons)
        assertEquals(null, PhonePadProtocol.parseInput("in x"))
        assertEquals("abc", PhonePadProtocol.phoneId("hello a-b c!"))
    }

    /** A browser that has accepted the self-signed certificate: trusts anything. */
    private val trustAll: javax.net.ssl.SSLSocketFactory = javax.net.ssl.SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        }), null)
    }.socketFactory

    @Test fun secureServesThePadOverTlsAndPlainHttpOnTheNextPort() {
        val dir = kotlin.io.path.createTempDirectory("jfkeys").toFile()
        System.setProperty("joyframe.keyDir", dir.path)
        val secure = PhonePads(port = 47970, secure = true)
        try {
            assertTrue(secure.start())
            val port = secure.url?.substringAfterLast(':')?.trimEnd('/')?.toInt() ?: 47970
            secure.url?.let { assertTrue(it.startsWith("https://"), it) }
            // The page over TLS, with the certificate a phone will be shown.
            val tls = trustAll.createSocket("127.0.0.1", port) as javax.net.ssl.SSLSocket
            tls.use { s ->
                s.outputStream.write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".encodeToByteArray()); s.outputStream.flush()
                assertTrue(s.inputStream.readBytes().decodeToString().startsWith("HTTP/1.1 200"))
                val cert = s.session.peerCertificates.single() as java.security.cert.X509Certificate
                cert.checkValidity()
                cert.verify(cert.publicKey)
                assertEquals(listOf("1.3.6.1.5.5.7.3.1"), cert.extendedKeyUsage, "serverAuth, as iOS requires")
                assertTrue(cert.notAfter.time - cert.notBefore.time < 825L * 86_400_000L, "under 825 days")
                assertEquals(-1, cert.basicConstraints, "not a CA")
            }
            // A phone over wss.
            val phone = Client(trustAll.createSocket("127.0.0.1", port))
            phone.send("hello tls1")
            phone.send("in -0.25 1 0 0")
            waitFor("the phone over TLS") { secure.pollAll().singleOrNull()?.state?.leftStickX == -0.25f }
            phone.send("ping 1")
            assertEquals("pong 1", phone.receive())
            phone.close()
            // The plain twin for browsers that refuse a WebSocket to a self-signed certificate.
            Socket("127.0.0.1", port + 1).use { s ->
                s.getOutputStream().write("GET /lite HTTP/1.1\r\nHost: x\r\n\r\n".encodeToByteArray())
                val text = s.getInputStream().readBytes().decodeToString()
                assertTrue(text.startsWith("HTTP/1.1 200") && "wss://" in text, text.take(80))
            }
        } finally { secure.close(); System.clearProperty("joyframe.keyDir") }
    }

    @Test fun theCertificateIsKeptAndRenewedWhenTheAddressChanges() {
        val dir = kotlin.io.path.createTempDirectory("jfkeys").toFile()
        val now = System.currentTimeMillis()
        val first = PhonePadTls.load(dir, listOf("192.168.0.20"), now)
        val again = PhonePadTls.load(dir, listOf("192.168.0.20"), now)
        assertEquals(first.certificate, again.certificate, "the same certificate: phones keep their exception")
        assertEquals(listOf(listOf<Any>(7, "192.168.0.20")), first.certificate.subjectAlternativeNames.map { it.toList() })
        val moved = PhonePadTls.load(dir, listOf("192.168.1.5"), now)
        assertTrue(moved.certificate != first.certificate, "a new address needs a new certificate")
        val expiring = PhonePadTls.load(dir, listOf("192.168.1.5"), now + 790L * 86_400_000L)
        assertTrue(expiring.certificate != moved.certificate, "renewed before it expires")
    }
}
