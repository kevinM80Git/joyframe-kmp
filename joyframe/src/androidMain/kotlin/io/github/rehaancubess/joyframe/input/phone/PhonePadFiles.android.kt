// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import io.github.rehaancubess.joyframe.audio.PlatformAudio

/** Android: the app's assets under [folder]. Needs `JoyframeAndroid.initialize(context)` first. */
actual fun bundledPhonePadFiles(folder: String): PhonePadFiles? {
    val assets = PlatformAudio.appContext?.assets ?: return null
    if (runCatching { assets.open("$folder/index.html").close() }.isFailure) return null
    return PhonePadFiles { name -> runCatching { assets.open("$folder/$name").use { it.readBytes() } }.getOrNull() }
}
