package com.kylecorry.trail_sense.settings

import android.os.Bundle
import androidx.annotation.ArrayRes
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.shared.FormatServiceV2
import com.kylecorry.trail_sense.shared.UserPreferences
import com.kylecorry.trail_sense.weather.infrastructure.WeatherUpdateScheduler
import com.kylecorry.trailsensecore.domain.units.PressureUnits
import com.kylecorry.trailsensecore.infrastructure.system.UiUtils

class WeatherSettingsFragment : CustomPreferenceFragment() {

    private var prefMonitorWeather: SwitchPreferenceCompat? = null
    private var prefWeatherUpdateFrequency: ListPreference? = null
    private var prefShowWeatherNotification: SwitchPreferenceCompat? = null
    private var prefShowDailyWeatherNotification: SwitchPreferenceCompat? = null
    private var prefShowPressureInNotification: SwitchPreferenceCompat? = null
    private var prefDailyWeatherTime: Preference? = null
    private var prefStormAlerts: SwitchPreferenceCompat? = null
    private val formatService by lazy { FormatServiceV2(requireContext()) }

    private lateinit var prefs: UserPreferences

    private fun bindPreferences() {
        prefMonitorWeather = switch(R.string.pref_monitor_weather)
        prefWeatherUpdateFrequency = list(R.string.pref_weather_update_frequency)
        prefShowWeatherNotification = switch(R.string.pref_show_weather_notification)
        prefShowDailyWeatherNotification = switch(R.string.pref_daily_weather_notification)
        prefShowPressureInNotification = switch(R.string.pref_show_pressure_in_notification)
        prefStormAlerts = switch(R.string.pref_send_storm_alert)
        prefDailyWeatherTime = preference(R.string.pref_daily_weather_time_holder)
    }


    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.weather_preferences, rootKey)
        val userPrefs = UserPreferences(requireContext())
        prefs = userPrefs
        bindPreferences()

        prefMonitorWeather?.isEnabled = !(prefs.isLowPowerModeOn && prefs.lowPowerModeDisablesWeather)
        prefMonitorWeather?.setOnPreferenceClickListener {
            if (prefs.weather.shouldMonitorWeather) {
                WeatherUpdateScheduler.start(requireContext())
            } else {
                WeatherUpdateScheduler.stop(requireContext())
            }
            true
        }
        prefShowWeatherNotification?.setOnPreferenceClickListener {
            restartWeatherMonitor()
            true
        }
        prefShowDailyWeatherNotification?.setOnPreferenceClickListener {
            restartWeatherMonitor()
            true
        }
        prefWeatherUpdateFrequency?.setOnPreferenceChangeListener { _, _ ->
            restartWeatherMonitor()
            true
        }
        prefShowPressureInNotification?.setOnPreferenceClickListener {
            restartWeatherMonitor()
            true
        }

        prefDailyWeatherTime?.summary = formatService.formatTime(prefs.weather.dailyForecastTime, false)
        prefDailyWeatherTime?.setOnPreferenceClickListener {
            UiUtils.pickTime(requireContext(), prefs.use24HourTime, prefs.weather.dailyForecastTime){ time ->
                if (time != null){
                    prefs.weather.dailyForecastTime = time
                    it.summary = formatService.formatTime(time, false)
                    restartWeatherMonitor()
                }
            }
            true
        }

        val forecastSensitivity =
            preferenceScreen.findPreference<ListPreference>(getString(R.string.pref_forecast_sensitivity))
        forecastSensitivity?.setEntries(getForecastSensitivityArray(userPrefs.pressureUnits))

        val stormSensitivity =
            preferenceScreen.findPreference<ListPreference>(getString(R.string.pref_storm_alert_sensitivity))
        stormSensitivity?.setEntries(getStormSensitivityArray(userPrefs.pressureUnits))
    }

    private fun restartWeatherMonitor() {
        WeatherUpdateScheduler.stop(requireContext())
        WeatherUpdateScheduler.start(requireContext())
    }

    @ArrayRes
    private fun getForecastSensitivityArray(units: PressureUnits): Int {
        return when (units) {
            PressureUnits.Hpa -> R.array.forecast_sensitivity_entries_hpa
            PressureUnits.Inhg -> R.array.forecast_sensitivity_entries_in
            PressureUnits.Psi -> R.array.forecast_sensitivity_entries_psi
            else -> R.array.forecast_sensitivity_entries_mbar
        }
    }

    @ArrayRes
    private fun getStormSensitivityArray(units: PressureUnits): Int {
        return when (units) {
            PressureUnits.Hpa -> R.array.storm_sensitivity_entries_hpa
            PressureUnits.Inhg -> R.array.storm_sensitivity_entries_in
            PressureUnits.Psi -> R.array.storm_sensitivity_entries_psi
            else -> R.array.storm_sensitivity_entries_mbar
        }
    }

}