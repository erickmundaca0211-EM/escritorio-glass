package cu.axel.smartdock.spikes

import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cu.axel.smartdock.utils.DeviceUtils
import cu.axel.smartdock.utils.Utils
import java.util.function.Consumer

private const val TAG = "EG"
private const val SPIKE_TINT = 0x66121824

/**
 * Phase 0 feasibility tests (docs/PLAN.md, spikes a, c, d and f). Debug builds only: this file lives
 * in src/debug and never ships in release. Everything is user-triggered from the buttons; it reads no
 * intent extras. Delete the whole src/debug/.../spikes package once docs/SPIKES.md is written.
 */
class SpikeActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val overlayWindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val blurListener = Consumer<Boolean> { log("a) isCrossWindowBlurEnabled cambió a $it") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The action bar overlapped the first buttons in landscape (camera cutout); not needed here
        supportActionBar?.hide()
        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(button("a) Estado del desenfoque") { blurStatus() })
        root.addView(button("a) Overlay con FLAG_BLUR_BEHIND") { showBlurBehindOverlay() })
        root.addView(button("a) Overlay redondeado (BackgroundBlurDrawable)") { showRoundedBlurOverlay() })
        root.addView(button("c) Densidad 440 durante 10 s y restaurar") { densityTest(440) })
        root.addView(button("g) Abrir pantalla fija en vertical") {
            startActivity(android.content.Intent(this, PortraitSpikeActivity::class.java))
        })
        root.addView(button("g) Ignorar orientación pedida por las apps: SÍ") { ignoreOrientationRequest(true) })
        root.addView(button("g) Ignorar orientación pedida por las apps: NO") { ignoreOrientationRequest(false) })

        val table = EditText(this).apply { hint = "tabla: system | secure | global" }
        val key = EditText(this).apply { hint = "clave" }
        val value = EditText(this).apply { hint = "valor" }
        root.addView(table)
        root.addView(key)
        root.addView(value)
        root.addView(button("d/f) Leer ajuste") {
            readSetting(table.text.toString().trim(), key.text.toString().trim())
        })
        root.addView(button("d/f) Escribir ajuste") {
            writeSetting(table.text.toString().trim(), key.text.toString().trim(), value.text.toString().trim())
        })

        output = TextView(this).apply { setTextIsSelectable(true) }
        root.addView(output)
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        })

        overlayWindowManager.addCrossWindowBlurEnabledListener(mainExecutor, blurListener)
        log("Pruebas listas. SDK ${Build.VERSION.SDK_INT}, densidad ${resources.configuration.densityDpi}, " +
                "superposición=${Settings.canDrawOverlays(this)}")
    }

    override fun onDestroy() {
        overlayWindowManager.removeCrossWindowBlurEnabledListener(blurListener)
        super.onDestroy()
    }

    // ---- a) Blur -------------------------------------------------------------------------------

    private fun blurStatus() {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        log("a) isCrossWindowBlurEnabled=${overlayWindowManager.isCrossWindowBlurEnabled}" +
                " supports_background_blur=${DeviceUtils.getSystemProp("ro.surface_flinger.supports_background_blur")}" +
                " disable_window_blurs=${Settings.Global.getString(contentResolver, "disable_window_blurs")}" +
                " ahorro_bateria=${power.isPowerSaveMode}")
    }

    private fun showBlurBehindOverlay() {
        if (!checkOverlay()) return
        val params = overlayParams()
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        params.blurBehindRadius = dp(30)
        val view = label("FLAG_BLUR_BEHIND\nradio 30 dp, fondo con esquinas de 16 dp").apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(SPIKE_TINT)
            }
        }
        addTemporaryOverlay(view, params, "a) blur-behind")
    }

    private fun showRoundedBlurOverlay() {
        if (!checkOverlay()) return
        val view = label("BackgroundBlurDrawable\nradio 30 dp, esquinas de 16 dp")
        addTemporaryOverlay(view, overlayParams(), "a) background-blur")
        view.post {
            try {
                val viewRoot = View::class.java.getMethod("getViewRootImpl").invoke(view)!!
                val blur = viewRoot.javaClass.getMethod("createBackgroundBlurDrawable")
                    .invoke(viewRoot) as Drawable
                blur.javaClass.getMethod("setBlurRadius", Int::class.javaPrimitiveType)
                    .invoke(blur, dp(30))
                blur.javaClass.getMethod("setCornerRadius", Float::class.javaPrimitiveType)
                    .invoke(blur, dp(16).toFloat())
                blur.javaClass.getMethod("setColor", Int::class.javaPrimitiveType)
                    .invoke(blur, SPIKE_TINT)
                view.background = blur
                log("a) BackgroundBlurDrawable aplicado (${blur.javaClass.name})")
            } catch (e: Throwable) {
                log("a) BackgroundBlurDrawable falló: ${e.cause ?: e}")
            }
        }
    }

    private fun overlayParams() = WindowManager.LayoutParams(
        dp(300), dp(160),
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.CENTER }

    private fun addTemporaryOverlay(view: View, params: WindowManager.LayoutParams, name: String) {
        overlayWindowManager.addView(view, params)
        log("$name: mostrado (tócalo para cerrarlo; se cierra solo en 8 s)")
        val remove = Runnable {
            if (view.isAttachedToWindow) {
                overlayWindowManager.removeView(view)
                log("$name: cerrado")
            }
        }
        view.setOnClickListener { remove.run() }
        handler.postDelayed(remove, 8000)
    }

    private fun checkOverlay(): Boolean {
        val ok = Settings.canDrawOverlays(this)
        if (!ok) log("Falta el permiso de superposición")
        return ok
    }

    // ---- c) Density ----------------------------------------------------------------------------

    private fun densityTest(target: Int) {
        val original = Settings.Secure.getString(contentResolver, "display_density_forced")
        log("c) display_density_forced antes=${original ?: "(vacío)"}, densidad actual=" +
                "${resources.configuration.densityDpi}. Aplico $target y restauro en 10 s.")
        try {
            val t0 = SystemClock.uptimeMillis()
            DensitySpike.setForced(target)
            log("c) setForcedDisplayDensityForUser($target) volvió en ${SystemClock.uptimeMillis() - t0} ms")
            DensitySpike.scheduleRestore(original, 10_000)
        } catch (e: Throwable) {
            log("c) falló: ${e.cause ?: e}")
        }
    }

    // ---- g) Orientation -----------------------------------------------------------------------

    /** Same as `adb shell wm set-ignore-orientation-request`, but called from the app itself. */
    private fun ignoreOrientationRequest(ignore: Boolean) {
        try {
            val iwm = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null)!!
            iwm.javaClass.getMethod(
                "setIgnoreOrientationRequest", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType
            ).invoke(iwm, Display.DEFAULT_DISPLAY, ignore)
            log("g) setIgnoreOrientationRequest($ignore) OK desde la app")
        } catch (e: Throwable) {
            log("g) setIgnoreOrientationRequest($ignore) falló desde la app: ${e.cause ?: e}")
        }
    }

    // ---- d/f) Settings -------------------------------------------------------------------------

    private fun readSetting(table: String, key: String) {
        if (!validKey(key)) return
        try {
            val value = when (table) {
                "system" -> Settings.System.getString(contentResolver, key)
                "secure" -> Settings.Secure.getString(contentResolver, key)
                "global" -> Settings.Global.getString(contentResolver, key)
                else -> return log("Tabla inválida: $table")
            }
            log("leer $table/$key = $value")
        } catch (e: Throwable) {
            log("leer $table/$key falló: $e")
        }
    }

    private fun writeSetting(table: String, key: String, value: String) {
        if (!validKey(key)) return
        try {
            val ok = when (table) {
                "system" -> Settings.System.putString(contentResolver, key, value)
                "secure" -> Settings.Secure.putString(contentResolver, key, value)
                "global" -> Settings.Global.putString(contentResolver, key, value)
                else -> return log("Tabla inválida: $table")
            }
            log("escribir $table/$key=$value -> $ok")
        } catch (e: Throwable) {
            log("escribir $table/$key=$value falló: $e")
        }
    }

    private fun validKey(key: String): Boolean {
        val ok = Regex("^[A-Za-z0-9_.]{1,100}$").matches(key)
        if (!ok) log("Clave inválida: $key")
        return ok
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 16f
    }

    private fun dp(value: Int) = Utils.dpToPx(this, value)

    private fun log(message: String) {
        Log.d(TAG, "spike $message")
        if (::output.isInitialized) output.append("$message\n\n")
    }
}

/** Density changes survive the activity being recreated by the configuration change. */
object DensitySpike {
    private val handler = Handler(Looper.getMainLooper())

    private fun windowManagerService(): Any =
        Class.forName("android.view.WindowManagerGlobal")
            .getMethod("getWindowManagerService").invoke(null)!!

    private fun userId() = Process.myUserHandle().hashCode()

    fun setForced(density: Int) {
        val iwm = windowManagerService()
        iwm.javaClass.getMethod(
            "setForcedDisplayDensityForUser",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(iwm, Display.DEFAULT_DISPLAY, density, userId())
    }

    private fun clearForced() {
        val iwm = windowManagerService()
        iwm.javaClass.getMethod(
            "clearForcedDisplayDensityForUser",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        ).invoke(iwm, Display.DEFAULT_DISPLAY, userId())
    }

    fun scheduleRestore(original: String?, delayMs: Long) {
        handler.postDelayed({
            try {
                val value = original?.toIntOrNull()
                if (value == null) clearForced() else setForced(value)
                Log.d(TAG, "spike c) densidad restaurada (original=${original ?: "ninguna"})")
            } catch (e: Throwable) {
                Log.e(TAG, "spike c) NO se pudo restaurar: ${e.cause ?: e}. Usa: adb shell wm density reset")
            }
        }, delayMs)
    }
}
