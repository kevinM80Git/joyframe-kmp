// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import java.io.File

/** `~/.joyframe`, or the `joyframe.keyDir` system property when set. */
internal actual fun phonePadKeyDir(): File? =
    System.getProperty("joyframe.keyDir")?.let(::File) ?: System.getProperty("user.home")?.let { File(it, ".joyframe") }
