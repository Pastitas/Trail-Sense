package com.kylecorry.trail_sense.navigation.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.findNavController
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.kylecorry.trail_sense.MainActivity
import com.kylecorry.trail_sense.R
import com.kylecorry.trail_sense.astronomy.domain.AstronomyService
import com.kylecorry.trail_sense.astronomy.ui.MoonPhaseImageMapper
import com.kylecorry.trail_sense.databinding.ActivityNavigatorBinding
import com.kylecorry.trail_sense.navigation.domain.NavigationService
import com.kylecorry.trail_sense.navigation.infrastructure.persistence.BeaconRepo
import com.kylecorry.trail_sense.navigation.infrastructure.share.LocationCopy
import com.kylecorry.trail_sense.navigation.infrastructure.share.LocationGeoSender
import com.kylecorry.trail_sense.navigation.infrastructure.share.LocationSharesheet
import com.kylecorry.trail_sense.shared.*
import com.kylecorry.trail_sense.shared.sensors.*
import com.kylecorry.trail_sense.shared.sensors.overrides.CachedGPS
import com.kylecorry.trail_sense.shared.sensors.overrides.OverrideGPS
import com.kylecorry.trail_sense.shared.views.QuickActionNone
import com.kylecorry.trail_sense.shared.views.UserError
import com.kylecorry.trail_sense.tools.backtrack.ui.QuickActionBacktrack
import com.kylecorry.trail_sense.tools.flashlight.ui.QuickActionFlashlight
import com.kylecorry.trail_sense.tools.maps.ui.QuickActionOfflineMaps
import com.kylecorry.trail_sense.tools.ruler.ui.QuickActionRuler
import com.kylecorry.trail_sense.tools.whistle.ui.QuickActionWhistle
import com.kylecorry.trail_sense.weather.ui.QuickActionClouds
import com.kylecorry.trailsensecore.domain.geo.Bearing
import com.kylecorry.trailsensecore.domain.geo.Coordinate
import com.kylecorry.trailsensecore.domain.geo.GeoService
import com.kylecorry.trailsensecore.domain.navigation.Beacon
import com.kylecorry.trailsensecore.domain.navigation.Position
import com.kylecorry.trailsensecore.domain.units.Distance
import com.kylecorry.trailsensecore.domain.units.Quality
import com.kylecorry.trailsensecore.infrastructure.persistence.Cache
import com.kylecorry.trailsensecore.infrastructure.persistence.Clipboard
import com.kylecorry.trailsensecore.infrastructure.sensors.SensorChecker
import com.kylecorry.trailsensecore.infrastructure.sensors.asLiveData
import com.kylecorry.trailsensecore.infrastructure.sensors.orientation.DeviceOrientation
import com.kylecorry.trailsensecore.infrastructure.system.UiUtils
import com.kylecorry.trailsensecore.infrastructure.time.Throttle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.*
import java.util.*


class NavigatorFragment : Fragment() {

    private var shownAccuracyToast: Boolean = false
    private val compass by lazy { sensorService.getCompass() }
    private val gps by lazy { sensorService.getGPS() }
    private val orientation by lazy { sensorService.getDeviceOrientation() }
    private val altimeter by lazy { sensorService.getAltimeter() }
    private val speedometer by lazy { sensorService.getSpeedometer() }

    private val userPrefs by lazy { UserPreferences(requireContext()) }

    private var _binding: ActivityNavigatorBinding? = null
    private val binding get() = _binding!!
    private lateinit var navController: NavController

    private lateinit var destinationPanel: DestinationPanel

    private val beaconRepo by lazy { BeaconRepo.getInstance(requireContext()) }

    private val sensorService by lazy { SensorService(requireContext()) }
    private val sensorChecker by lazy { SensorChecker(requireContext()) }
    private val cache by lazy { Cache(requireContext()) }
    private val throttle = Throttle(20)

    private val navigationService = NavigationService()
    private val astronomyService = AstronomyService()
    private val geoService = GeoService()
    private val formatService by lazy { FormatService(requireContext()) }

    private var beacons: Collection<Beacon> = listOf()
    private var nearbyBeacons: Collection<Beacon> = listOf()

    private var destination: Beacon? = null
    private var destinationBearing: Bearing? = null
    private var useTrueNorth = false

    private var leftQuickAction: QuickActionButton? = null
    private var rightQuickAction: QuickActionButton? = null

    private var gpsErrorShown = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityNavigatorBinding.inflate(layoutInflater, container, false)
        rightQuickAction = getQuickActionButton(
            userPrefs.navigation.rightQuickAction,
            binding.navigationRightQuickAction
        )
        leftQuickAction = getQuickActionButton(
            userPrefs.navigation.leftQuickAction,
            binding.navigationLeftQuickAction
        )
        return binding.root
    }

    override fun onDestroy() {
        super.onDestroy()
        rightQuickAction?.onDestroy()
        leftQuickAction?.onDestroy()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val beaconId = arguments?.getLong("destination") ?: 0L

        if (beaconId != 0L) {
            showCalibrationDialog()
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    destination = beaconRepo.getBeacon(beaconId)?.toBeacon()
                    cache.putLong(LAST_BEACON_ID, beaconId)
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        beaconRepo.getBeacons().observe(viewLifecycleOwner) {
            beacons = it.map { it.toBeacon() }
            nearbyBeacons = getNearbyBeacons()
            updateUI()
        }

        rightQuickAction?.onCreate()
        leftQuickAction?.onCreate()
        navController = findNavController()

        destinationPanel = DestinationPanel(binding.navigationSheet)

        compass.asLiveData().observe(viewLifecycleOwner, { updateUI() })
        orientation.asLiveData().observe(viewLifecycleOwner, { onOrientationUpdate() })
        altimeter.asLiveData().observe(viewLifecycleOwner, { updateUI() })
        gps.asLiveData().observe(viewLifecycleOwner, { onLocationUpdate() })
        speedometer.asLiveData().observe(viewLifecycleOwner, { updateUI() })

        binding.location.setOnLongClickListener {
            CustomUiUtils.openMenu(it, R.menu.location_share_menu){ menuItem ->
                val sender = when (menuItem){
                    R.id.action_send -> LocationSharesheet(requireContext())
                    R.id.action_maps -> LocationGeoSender(requireContext())
                    else -> LocationCopy(requireContext(), Clipboard(requireContext()))
                }
                sender.send(gps.location)
                true
            }
            true
        }

        binding.beaconBtn.setOnClickListener {
            if (destination == null) {
                navController.navigate(R.id.action_navigatorFragment_to_beaconListFragment)
            } else {
                destination = null
                cache.remove(LAST_BEACON_ID)
                updateNavigator()
            }
        }

        binding.accuracyView.setOnClickListener { displayAccuracyTips() }

        binding.roundCompass.setOnClickListener {
            toggleDestinationBearing()
        }
        binding.radarCompass.setOnClickListener {
            toggleDestinationBearing()
        }
        binding.linearCompass.setOnClickListener {
            toggleDestinationBearing()
        }
    }

    private fun toggleDestinationBearing() {
        if (destinationBearing == null) {
            destinationBearing = compass.bearing
            cache.putFloat(LAST_DEST_BEARING, compass.bearing.value)
            UiUtils.shortToast(
                requireContext(),
                getString(R.string.toast_destination_bearing_set)
            )
        } else {
            destinationBearing = null
            cache.remove(LAST_DEST_BEARING)
        }
    }

    private fun displayAccuracyTips() {
        context ?: return

        val gpsHorizontalAccuracy = gps.horizontalAccuracy
        val gpsVerticalAccuracy = gps.verticalAccuracy

        val gpsHAccuracyStr =
            if (gpsHorizontalAccuracy == null) getString(R.string.accuracy_distance_unknown) else getString(
                R.string.accuracy_distance_format,
                formatService.formatSmallDistance(gpsHorizontalAccuracy)
            )
        val gpsVAccuracyStr =
            if (gpsVerticalAccuracy == null) getString(R.string.accuracy_distance_unknown) else getString(
                R.string.accuracy_distance_format,
                formatService.formatSmallDistance(gpsVerticalAccuracy)
            )

        UiUtils.alert(
            requireContext(),
            getString(R.string.accuracy_info_title),
            getString(
                R.string.accuracy_info,
                gpsHAccuracyStr,
                gpsVAccuracyStr,
                gps.satellites.toString()
            ),
            R.string.dialog_ok
        )
    }

    private fun getIndicators(): List<BearingIndicator> {
        val indicators = mutableListOf<BearingIndicator>()
        if (userPrefs.astronomy.showOnCompass) {
            val isMoonUp = astronomyService.isMoonUp(gps.location)
            val isSunUp = astronomyService.isSunUp(gps.location)
            val showWhenDown = userPrefs.astronomy.showOnCompassWhenDown

            val sunBearing = getSunBearing()
            val moonBearing = getMoonBearing()

            if (isSunUp) {
                indicators.add(BearingIndicator(sunBearing, R.drawable.ic_sun))
            } else if (!isSunUp && showWhenDown) {
                indicators.add(BearingIndicator(sunBearing, R.drawable.ic_sun, opacity = 0.5f))
            }

            if (isMoonUp) {
                indicators.add(BearingIndicator(moonBearing, getMoonImage()))
            } else if (!isMoonUp && showWhenDown) {
                indicators.add(BearingIndicator(moonBearing, getMoonImage(), opacity = 0.5f))
            }
        }

        if (destination != null) {
            indicators.add(
                BearingIndicator(
                    transformTrueNorthBearing(
                        gps.location.bearingTo(
                            destination!!.coordinate
                        )
                    ), R.drawable.ic_arrow_target,
                    distance = Distance.meters(gps.location.distanceTo(destination!!.coordinate))
                )
            )
            return indicators
        }

        if (destinationBearing != null) {
            indicators.add(
                BearingIndicator(
                    destinationBearing!!,
                    R.drawable.ic_arrow_target,
                    UiUtils.color(requireContext(), R.color.colorAccent)
                )
            )
        }

        val nearby = nearbyBeacons
        for (beacon in nearby) {
            indicators.add(
                BearingIndicator(
                    transformTrueNorthBearing(gps.location.bearingTo(beacon.coordinate)),
                    R.drawable.ic_arrow_target,
                    distance = Distance.meters(gps.location.distanceTo(beacon.coordinate))
                )
            )
        }

        return indicators
    }

    override fun onResume() {
        super.onResume()
        rightQuickAction?.onResume()
        leftQuickAction?.onResume()
        useTrueNorth = userPrefs.navigation.useTrueNorth

        // Resume navigation
        val lastBeaconId = cache.getLong(LAST_BEACON_ID)
        if (lastBeaconId != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    destination = beaconRepo.getBeacon(lastBeaconId)?.toBeacon()
                }
            }
        }

        val lastDestBearing = cache.getFloat(LAST_DEST_BEARING)
        if (lastDestBearing != null) {
            destinationBearing = Bearing(lastDestBearing)
        }

        compass.declination = getDeclination()

        binding.beaconBtn.show()
        binding.roundCompass.visibility = if (userPrefs.navigation.useRadarCompass) View.INVISIBLE else View.VISIBLE
        binding.radarCompass.visibility = if (userPrefs.navigation.useRadarCompass) View.VISIBLE else View.INVISIBLE

        // Update the UI
        updateNavigator()
    }

    override fun onPause() {
        super.onPause()
        rightQuickAction?.onPause()
        leftQuickAction?.onPause()
        (requireActivity() as MainActivity).errorBanner.dismiss(USER_ERROR_COMPASS_POOR)
        shownAccuracyToast = false
        gpsErrorShown = false
    }

    private fun getNearbyBeacons(): Collection<Beacon> {

        if (!userPrefs.navigation.showMultipleBeacons) {
            return listOf()
        }

        return navigationService.getNearbyBeacons(
            gps.location,
            beacons,
            userPrefs.navigation.numberOfVisibleBeacons,
            8f,
            userPrefs.navigation.maxBeaconDistance
        )
    }

    private fun getSunBearing(): Bearing {
        return transformTrueNorthBearing(astronomyService.getSunAzimuth(gps.location))
    }

    private fun getMoonBearing(): Bearing {
        return transformTrueNorthBearing(astronomyService.getMoonAzimuth(gps.location))
    }

    private fun getDestinationBearing(): Bearing? {
        val destLocation = destination?.coordinate
        return when {
            destLocation != null -> {
                transformTrueNorthBearing(gps.location.bearingTo(destLocation))
            }
            destinationBearing != null -> {
                destinationBearing
            }
            else -> {
                null
            }
        }
    }

    private fun getSelectedBeacon(nearby: Collection<Beacon>): Beacon? {
        return destination ?: getFacingBeacon(nearby)
    }

    private fun transformTrueNorthBearing(bearing: Bearing): Bearing {
        return if (useTrueNorth) {
            bearing
        } else {
            bearing.withDeclination(-getDeclination())
        }
    }

    private fun getDeclination(): Float {
        return if (!userPrefs.useAutoDeclination) {
            userPrefs.declinationOverride
        } else {
            geoService.getDeclination(gps.location, gps.altitude)
        }
    }

    private fun getFacingBeacon(nearby: Collection<Beacon>): Beacon? {
        return navigationService.getFacingBeacon(
            getPosition(),
            nearby,
            getDeclination(),
            useTrueNorth
        )
    }

    private fun updateUI() {

        if (throttle.isThrottled() || context == null) {
            return
        }

        val selectedBeacon = getSelectedBeacon(nearbyBeacons)

        if (selectedBeacon != null) {
            destinationPanel.show(
                getPosition(),
                selectedBeacon,
                getDeclination(),
                userPrefs.navigation.useTrueNorth
            )
        } else {
            destinationPanel.hide()
        }

        detectAndShowGPSError()
        binding.gpsStatus.setStatusText(getGPSStatus())
        binding.gpsStatus.setBackgroundTint(getGPSColor())
        binding.compassStatus.setStatusText(formatService.formatQuality(compass.quality))
        binding.compassStatus.setBackgroundTint(getCompassColor())

        if ((compass.quality == Quality.Poor || compass.quality == Quality.Moderate) && !shownAccuracyToast) {
            val banner = (requireActivity() as MainActivity).errorBanner
            banner.report(
                UserError(
                    USER_ERROR_COMPASS_POOR,
                    getString(
                        R.string.compass_calibrate_toast, formatService.formatQuality(
                            compass.quality
                        ).toLowerCase(Locale.getDefault())
                    ),
                    R.drawable.ic_compass_icon,
                    getString(R.string.how)
                ) {
                    displayAccuracyTips()
                    banner.hide()
                })
            shownAccuracyToast = true
        } else if (compass.quality == Quality.Good) {
            (requireActivity() as MainActivity).errorBanner.dismiss(USER_ERROR_COMPASS_POOR)
        }

        // Speed
        binding.speed.text = formatService.formatSpeed(speedometer.speed.speed)

        // Azimuth
        binding.compassAzimuth.text = formatService.formatDegrees(compass.bearing.value)
        binding.compassDirection.text = formatService.formatDirection(compass.bearing.direction)

        // Compass
        val indicators = getIndicators()
        val destBearing = getDestinationBearing()
        val destColor = if (destination != null) UiUtils.color(
            requireContext(),
            R.color.colorPrimary
        ) else UiUtils.color(requireContext(), R.color.colorAccent)
        binding.roundCompass.setIndicators(indicators)
        binding.roundCompass.setAzimuth(compass.bearing)
        binding.roundCompass.setDestination(destBearing, destColor)
        binding.radarCompass.setIndicators(indicators)
        binding.radarCompass.setAzimuth(compass.bearing)
        binding.radarCompass.setDestination(destBearing, destColor)
        binding.linearCompass.setIndicators(indicators)
        binding.linearCompass.setAzimuth(compass.bearing)
        binding.linearCompass.setDestination(destBearing, destColor)

        // Altitude
        binding.altitude.text = formatService.formatSmallDistance(altimeter.altitude)

        // Location
        binding.location.text = formatService.formatLocation(gps.location)

        updateNavigationButton()
    }

    private fun shouldShowLinearCompass(): Boolean {
        return userPrefs.navigation.showLinearCompass && orientation.orientation == DeviceOrientation.Orientation.Portrait
    }


    private fun getPosition(): Position {
        return Position(gps.location, altimeter.altitude, compass.bearing, speedometer.speed.speed)
    }

    private fun showCalibrationDialog() {
        if (userPrefs.navigation.showCalibrationOnNavigateDialog) {
            UiUtils.alert(
                requireContext(), getString(R.string.calibrate_compass_dialog_title), getString(
                    R.string.calibrate_compass_on_navigate_dialog_content,
                    getString(R.string.dialog_ok)
                ), R.string.dialog_ok
            )
        }
    }

    private fun onOrientationUpdate(): Boolean {
        if (shouldShowLinearCompass()) {
            binding.linearCompass.visibility = View.VISIBLE
            binding.roundCompass.visibility = View.INVISIBLE
            binding.radarCompass.visibility = View.INVISIBLE
        } else {
            binding.linearCompass.visibility = View.INVISIBLE
            binding.roundCompass.visibility = if (userPrefs.navigation.useRadarCompass) View.INVISIBLE else View.VISIBLE
            binding.radarCompass.visibility = if (userPrefs.navigation.useRadarCompass) View.VISIBLE else View.INVISIBLE
        }
        updateUI()
        return true
    }

    private fun onLocationUpdate() {
        nearbyBeacons = getNearbyBeacons()
        compass.declination = getDeclination()

        updateUI()
    }

    private fun updateNavigationButton() {
        if (destination != null) {
            binding.beaconBtn.setImageResource(R.drawable.ic_cancel)
        } else {
            binding.beaconBtn.setImageResource(R.drawable.ic_beacon)
        }
    }

    private fun updateNavigator() {
        onLocationUpdate()
        updateNavigationButton()
    }

    @ColorInt
    private fun getCompassColor(): Int {
        return CustomUiUtils.getQualityColor(requireContext(), compass.quality)
    }

    @ColorInt
    private fun getGPSColor(): Int {
        if (gps is OverrideGPS) {
            return UiUtils.color(requireContext(), R.color.green)
        }

        if (gps is CachedGPS || !sensorChecker.hasGPS()) {
            return UiUtils.color(requireContext(), R.color.red)
        }

        if (Duration.between(gps.time, Instant.now()) > Duration.ofMinutes(2)) {
            return UiUtils.color(requireContext(), R.color.yellow)
        }

        if (!gps.hasValidReading || (userPrefs.requiresSatellites && gps.satellites < 4)) {
            return UiUtils.color(requireContext(), R.color.yellow)
        }

        return CustomUiUtils.getQualityColor(requireContext(), gps.quality)
    }

    private fun getGPSStatus(): String {
        if (gps is OverrideGPS) {
            return getString(R.string.gps_user)
        }

        if (gps is CachedGPS || !sensorChecker.hasGPS()) {
            return getString(R.string.gps_unavailable)
        }

        if (Duration.between(gps.time, Instant.now()) > Duration.ofMinutes(2)) {
            return getString(R.string.gps_stale)
        }

        if (!gps.hasValidReading || (userPrefs.requiresSatellites && gps.satellites < 4)) {
            return getString(R.string.gps_searching)
        }

        return formatService.formatQuality(gps.quality)
    }

    private fun detectAndShowGPSError() {
        if (gpsErrorShown) {
            return
        }

        if (gps is OverrideGPS && gps.location == Coordinate.zero) {
            val activity = requireActivity() as MainActivity
            val navController = findNavController()
            val error = UserError(
                USER_ERROR_GPS_NOT_SET,
                getString(R.string.location_not_set),
                R.drawable.satellite,
                getString(R.string.set)
            ) {
                activity.errorBanner.dismiss(USER_ERROR_GPS_NOT_SET)
                navController.navigate(R.id.calibrateGPSFragment)
            }
            activity.errorBanner.report(error)
            gpsErrorShown = true
        } else if (gps is CachedGPS && gps.location == Coordinate.zero) {
            val error = UserError(
                USER_ERROR_NO_GPS,
                getString(R.string.location_disabled),
                R.drawable.satellite
            )
            (requireActivity() as MainActivity).errorBanner.report(error)
            gpsErrorShown = true
        }
    }

    private fun getQuickActionButton(
        type: QuickActionType,
        button: FloatingActionButton
    ): QuickActionButton {
        return when (type) {
            QuickActionType.None -> QuickActionNone(button, this)
            QuickActionType.Backtrack -> QuickActionBacktrack(button, this)
            QuickActionType.Flashlight -> QuickActionFlashlight(button, this)
            QuickActionType.Clouds -> QuickActionClouds(button, this)
            QuickActionType.Temperature -> QuickActionNone(button, this)
            QuickActionType.Ruler -> QuickActionRuler(button, this, binding.ruler)
            QuickActionType.Maps -> QuickActionOfflineMaps(button, this)
            QuickActionType.Whistle -> QuickActionWhistle(button, this)
        }
    }


    @DrawableRes
    private fun getMoonImage(): Int {
        return MoonPhaseImageMapper(requireContext()).getPhaseImage(astronomyService.getCurrentMoonPhase().phase)
    }

    companion object {
        const val LAST_BEACON_ID = "last_beacon_id_long"
        const val LAST_DEST_BEARING = "last_dest_bearing"
    }

}
