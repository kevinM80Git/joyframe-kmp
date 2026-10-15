// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** Where the phone pad's certificate is kept between runs: app-private storage. Null: keep it in memory only. */
internal expect fun phonePadKeyDir(): File?

/**
 * The phone pad's HTTPS identity: a self-signed certificate (ECDSA P-256) for the device's LAN
 * addresses. Browsers only give secure pages motion sensors, so tilt steering needs this; the price is
 * one "not private" warning per phone, which the browser then remembers for this certificate. So it is
 * kept ([phonePadKeyDir]) and only replaced when it expires or the device's address is not on it.
 *
 * Built by hand in DER (no BouncyCastle on Android or the JDK's internal classes): what iOS and Chrome
 * ask of a server certificate, an IP subjectAltName, serverAuth key usage, a validity under 825 days.
 */
internal object PhonePadTls {
    class Identity(val key: PrivateKey, val certificate: X509Certificate) {
        fun covers(addresses: List<String>, now: Long): Boolean {
            if (certificate.notAfter.time < now + RENEW_BEFORE_MS || certificate.notBefore.time > now) return false
            val ips = certificate.subjectAlternativeNames.orEmpty().filter { it[0] == 7 }.map { it[1].toString() }
            return addresses.all { it in ips }
        }

        fun context(): SSLContext = SSLContext.getInstance("TLS").apply { init(arrayOf(KeyManager(this@Identity)), null, null) }
    }

    /** The kept identity when it still fits [addresses], else a new one (kept for next time). */
    fun load(dir: File?, addresses: List<String>, now: Long = System.currentTimeMillis()): Identity {
        val file = dir?.let { File(it, FILE) }
        if (file != null && file.isFile) {
            runCatching { read(file.readBytes()) }.getOrNull()?.takeIf { it.covers(addresses, now) }?.let { return it }
        }
        val fresh = create(addresses, now)
        if (file != null) runCatching { file.parentFile?.mkdirs(); file.writeBytes(write(fresh)) }
        return fresh
    }

    fun create(addresses: List<String>, now: Long): Identity {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, "Joyframe phone pad".encodeToByteArray()))))
        val serial = ByteArray(16).also { SecureRandom().nextBytes(it); it[0] = (it[0].toInt() and 0x7F or 0x40).toByte() }
        val ips = addresses.mapNotNull { a -> runCatching { InetAddress.getByName(a).address }.getOrNull()?.let { tlv(0x87, it) } }
        val extensions = tlv(0xA3, seq(
            seq(oid("2.5.29.17"), tlv(0x04, seq(*ips.toTypedArray()))),            // subjectAltName: IP addresses
            seq(oid("2.5.29.37"), tlv(0x04, seq(oid("1.3.6.1.5.5.7.3.1")))),        // extKeyUsage: serverAuth
            seq(oid("2.5.29.19"), tlv(0x04, seq())),                                 // basicConstraints: not a CA
        ))
        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))),                                     // version 3
            tlv(0x02, serial),
            seq(oid(ECDSA_SHA256)),
            name,
            seq(time(now - DAY_MS), time(now + VALID_DAYS * DAY_MS)),
            name,
            pair.public.encoded,                                                      // SubjectPublicKeyInfo, already DER
            extensions,
        )
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(tbs); sign() }
        val der = seq(tbs, seq(oid(ECDSA_SHA256)), tlv(0x03, byteArrayOf(0) + signature))
        return Identity(pair.private, parse(der))
    }

    private fun parse(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private fun write(id: Identity): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).run {
            writeInt(MAGIC)
            id.key.encoded.let { writeInt(it.size); write(it) }
            id.certificate.encoded.let { writeInt(it.size); write(it) }
        }
    }.toByteArray()

    private fun read(bytes: ByteArray): Identity = DataInputStream(ByteArrayInputStream(bytes)).run {
        require(readInt() == MAGIC)
        val key = ByteArray(readInt()).also { readFully(it) }
        val cert = ByteArray(readInt()).also { readFully(it) }
        Identity(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(key)), parse(cert))
    }

    /** Hands TLS our one key and certificate, whatever it asks for. */
    private class KeyManager(private val id: Identity) : X509ExtendedKeyManager() {
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = arrayOf(id.certificate)
        override fun getPrivateKey(alias: String?) = id.key
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }

    // ---- DER ------------------------------------------------------------------------------------

    private fun tlv(tag: Int, body: ByteArray): ByteArray {
        val n = body.size
        val length = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + body
    }

    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })

    private fun oid(dotted: String): ByteArray {
        val arcs = dotted.split('.').map { it.toLong() }
        val out = ByteArrayOutputStream()
        out.write((arcs[0] * 40 + arcs[1]).toInt())
        for (arc in arcs.drop(2)) {
            // Base 128, high groups first, each but the last with its top bit set.
            var shift = (63 - java.lang.Long.numberOfLeadingZeros(arc.coerceAtLeast(1))) / 7 * 7
            while (shift > 0) { out.write(((arc ushr shift) and 0x7F or 0x80).toInt()); shift -= 7 }
            out.write((arc and 0x7F).toInt())
        }
        return tlv(0x06, out.toByteArray())
    }

    /** UTCTime (years before 2050, as X.509 requires for those). */
    private fun time(millis: Long): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(Date(millis)).encodeToByteArray())
    }

    private const val FILE = "joyframe-phonepad-tls.bin"
    private const val ALIAS = "phonepad"
    private const val MAGIC = 0x4A465031 // "JFP1"
    private const val ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val DAY_MS = 86_400_000L
    /** Under Apple's 825-day limit for server certificates. */
    private const val VALID_DAYS = 800L
    private const val RENEW_BEFORE_MS = 30 * DAY_MS
}
