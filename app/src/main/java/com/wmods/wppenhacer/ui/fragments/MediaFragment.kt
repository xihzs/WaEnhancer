package com.wmods.wppenhacer.ui.fragments

import android.content.Intent
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.wmods.wppenhacer.R
import com.wmods.wppenhacer.activities.CallRecordingSettingsActivity
import com.wmods.wppenhacer.preference.LimitedEditTextPreference
import com.wmods.wppenhacer.ui.fragments.base.BasePreferenceFragment
import java.util.Locale

class MediaFragment : BasePreferenceFragment() {
    override fun onResume() {
        super.onResume()
        setDisplayHomeAsUpEnabled(false)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        setPreferencesFromResource(R.xml.fragment_media, rootKey)

        findPreference<Preference>("call_recording_settings")?.setOnPreferenceClickListener {
            startActivity(Intent(requireContext(), CallRecordingSettingsActivity::class.java))
            true
        }
        findPreference<Preference>("video_call_screen_rec")?.isEnabled = false

        setupCountryPicker()
    }

    private fun setupCountryPicker() {
        val countryListPref = findPreference<ListPreference>("music_region_country") ?: return
        val customCountryPref = findPreference<LimitedEditTextPreference>("music_region_custom_country")

        // Top priority major music markets
        val priorityCodes = linkedSetOf(
            "US", "GB", "CA", "AU", "DE", "FR", "JP", "KR", "BR", "ID",
            "IN", "MX", "ES", "IT", "NL", "SE", "CH", "TR", "SA", "AE",
            "SG", "ZA", "AR", "CO", "PH", "MY", "TH", "VN", "EG", "NG",
            "PL", "BE", "AT", "NO", "DK", "FI", "IE", "NZ", "PT", "CL"
        )

        val allIsoCodes = Locale.getISOCountries()
        val allCountries = allIsoCodes.mapNotNull { code ->
            val locale = Locale("", code)
            val name = locale.getDisplayCountry(Locale.ENGLISH)
            if (name.isNullOrBlank()) null else code.uppercase() to name
        }

        val priorityItems = priorityCodes.mapNotNull { code ->
            allCountries.find { it.first.equals(code, ignoreCase = true) }
        }

        val otherItems = allCountries
            .filter { (code, _) -> !priorityCodes.contains(code) }
            .sortedBy { it.second }

        val combinedList = priorityItems + otherItems

        val entries = combinedList.map { (code, name) ->
            val flag = getFlagEmoji(code)
            if (flag.isNotEmpty()) "$flag $name ($code)" else "$name ($code)"
        }.toTypedArray()

        val entryValues = combinedList.map { it.first }.toTypedArray()

        countryListPref.entries = entries
        countryListPref.entryValues = entryValues

        // Keep custom country code and dropdown in sync
        customCountryPref?.setOnPreferenceChangeListener { _, newValue ->
            val code = (newValue as? String)?.trim()?.uppercase().orEmpty()
            if (code.length == 2 && code.all { it.isLetter() }) {
                if (entryValues.contains(code)) {
                    countryListPref.value = code
                }
                customCountryPref.text = code
                true
            } else {
                false
            }
        }

        countryListPref.setOnPreferenceChangeListener { _, newValue ->
            val code = (newValue as? String)?.trim()?.uppercase().orEmpty()
            if (code.isNotEmpty()) {
                customCountryPref?.text = code
            }
            true
        }

        // Initialize custom text with current value
        val currentVal = countryListPref.value ?: "US"
        customCountryPref?.text = currentVal
    }

    private fun getFlagEmoji(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val firstChar = Character.codePointAt(countryCode.uppercase(), 0) - 0x41 + 0x1F1E6
        val secondChar = Character.codePointAt(countryCode.uppercase(), 1) - 0x41 + 0x1F1E6
        return String(Character.toChars(firstChar)) + String(Character.toChars(secondChar))
    }
}
