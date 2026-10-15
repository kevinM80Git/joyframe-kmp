// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import javax.net.ssl.SSLContext

/**
 * HTTPS for another server on this device that phones open, such as an app's own controller page:
 * the same self-signed identity [PhonePads] uses with `secure = true` (one kept certificate for the
 * device's LAN [addresses]), so a phone that has accepted it once for one accepts it for the other.
 */
object LocalTls {
    fun context(addresses: List<String>): SSLContext = PhonePadTls.load(phonePadKeyDir(), addresses).context()
}
