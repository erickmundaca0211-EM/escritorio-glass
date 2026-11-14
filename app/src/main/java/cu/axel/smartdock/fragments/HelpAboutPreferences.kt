package cu.axel.smartdock.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cu.axel.smartdock.R

class HelpAboutPreferences : PreferenceFragmentCompat() {
    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_help_about, arg1)
        findPreference<Preference>("show_help")!!.setOnPreferenceClickListener {
            val dialog = MaterialAlertDialogBuilder(requireContext())
            dialog.setTitle(R.string.help)
            dialog.setView(R.layout.dialog_help)
            dialog.setPositiveButton(R.string.ok, null)
            // The fork's own guide (README), not Smart Dock's: it covers this app's permissions
            dialog.setNegativeButton(R.string.more_help) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, getString(R.string.project_url).toUri()))
                } catch (_: ActivityNotFoundException) {
                }
            }
            dialog.show()
            false
        }
    }
}
