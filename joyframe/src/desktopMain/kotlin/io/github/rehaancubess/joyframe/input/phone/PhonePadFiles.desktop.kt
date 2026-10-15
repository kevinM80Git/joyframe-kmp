// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

/** Desktop: classpath resources under [folder] (the app puts the built controller there). */
actual fun bundledPhonePadFiles(folder: String): PhonePadFiles? {
    val loader = PhonePads::class.java.classLoader ?: return null
    if (loader.getResource("$folder/index.html") == null) return null
    return PhonePadFiles { name -> loader.getResourceAsStream("$folder/$name")?.use { it.readBytes() } }
}
