package cu.axel.smartdock.fragments

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cu.axel.smartdock.R

class HelpAboutPreferences : PreferenceFragmentCompat() {
    override fun onCreatePreferences(arg0: Bundle?, arg1: String?) {
        setPreferencesFromResource(R.xml.preferences_help_about, arg1)
        findPreference<Preference>("show_help")!!.setOnPreferenceClickListener {
            // The help is all here: the app sends nobody to a web page of whoever built it
            val dialog = MaterialAlertDialogBuilder(requireContext())
            dialog.setTitle(R.string.help)
            dialog.setView(R.layout.dialog_help)
            dialog.setPositiveButton(R.string.ok, null)
            dialog.show()
            false
        }
    }
}
