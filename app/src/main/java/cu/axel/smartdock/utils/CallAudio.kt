package cu.axel.smartdock.utils

import android.media.AudioManager
import android.os.Build

/** Tells whether a phone or internet call (WhatsApp and similar) is using the audio right now. */
object CallAudio {
    /** The stream the call is heard on; its volume is the "Volumen de llamada" slider. */
    const val STREAM = AudioManager.STREAM_VOICE_CALL

    fun active(audio: AudioManager): Boolean {
        val mode = audio.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_CALL_SCREENING
        ) return true
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                (mode == AudioManager.MODE_CALL_REDIRECT || mode == AudioManager.MODE_COMMUNICATION_REDIRECT)
    }
}
