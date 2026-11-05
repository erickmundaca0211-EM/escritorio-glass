package cu.axel.smartdock.glass

import android.content.SharedPreferences
import android.graphics.Color

enum class GlassQuality { OFF, STANDARD, MAX }

/** Kinds of glass surface and their corner radius (docs/DISENO.md). */
enum class GlassSurface(val radiusDp: Int) {
    BAR(18),
    BAR_FLAT(0),
    MENU(22),
    MENU_FULLSCREEN(0),
    PANEL(20),
    POPUP(14)
}

/** User settings for the glass look, read from the default SharedPreferences. */
data class GlassSettings(
    val quality: GlassQuality,
    /** Tint opacity, 0.35–0.85. */
    val tintOpacity: Float,
    /** Blur radius in dp, 0–40. */
    val blurRadiusDp: Int,
    val accent: Int
) {
    companion object {
        const val KEY_QUALITY = "glass_quality"
        const val KEY_TINT_OPACITY = "glass_tint_opacity"
        const val KEY_BLUR_RADIUS = "glass_blur_radius"
        const val KEY_ACCENT = "glass_accent"
        const val DEFAULT_ACCENT = "#6CC4FF"

        fun read(sp: SharedPreferences): GlassSettings {
            val quality = when (sp.getString(KEY_QUALITY, "standard")) {
                "off" -> GlassQuality.OFF
                "max" -> GlassQuality.MAX
                else -> GlassQuality.STANDARD
            }
            val accent = try {
                Color.parseColor(sp.getString(KEY_ACCENT, DEFAULT_ACCENT))
            } catch (_: IllegalArgumentException) {
                Color.parseColor(DEFAULT_ACCENT)
            }
            return GlassSettings(
                quality = quality,
                tintOpacity = sp.getInt(KEY_TINT_OPACITY, 62).coerceIn(35, 85) / 100f,
                blurRadiusDp = sp.getInt(KEY_BLUR_RADIUS, 24).coerceIn(0, 40),
                accent = accent
            )
        }
    }
}
