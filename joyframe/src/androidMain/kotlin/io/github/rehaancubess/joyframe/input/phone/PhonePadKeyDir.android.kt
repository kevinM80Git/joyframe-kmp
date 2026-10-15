// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import io.github.rehaancubess.joyframe.audio.PlatformAudio
import java.io.File

/** The app's private files directory (needs `JoyframeAndroid.initialize(context)`). */
internal actual fun phonePadKeyDir(): File? = PlatformAudio.appContext?.filesDir
