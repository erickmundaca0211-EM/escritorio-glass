package cu.axel.smartdock.spikes

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Phase 0 spike (g): a screen locked to portrait in the manifest, to check whether desktop mode can keep
 * portrait-only apps in landscape. Debug builds only.
 */
class PortraitSpikeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "EG Vertical\n(pantalla fija en vertical)"
            textSize = 24f
            gravity = Gravity.CENTER
        })
    }

    override fun onResume() {
        super.onResume()
        val config = resources.configuration
        Log.d("EG", "spike g) EG Vertical visible: orientación=${
            if (config.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) "vertical" else "horizontal"
        } ${config.screenWidthDp}x${config.screenHeightDp}dp")
    }
}
