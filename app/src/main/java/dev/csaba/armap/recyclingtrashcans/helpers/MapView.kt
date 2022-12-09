/*
 * Copyright 2022 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.csaba.armap.recyclingtrashcans.helpers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.ColorInt
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.InfoWindowAdapter
import com.google.android.gms.maps.GoogleMap.OnMarkerClickListener
import com.google.android.gms.maps.model.*
import com.google.android.material.button.MaterialButton
import dev.csaba.armap.recyclingtrashcans.R
import dev.csaba.armap.recyclingtrashcans.TrashcanGeoActivity


class MapView(val activity: TrashcanGeoActivity, private val googleMap: GoogleMap) {
  companion object {
    const val TAG = "MapView"
  }

  val redMarkerColor: Int = Color.argb(255, 255, 0, 0)
  val greenMarkerColor: Int = Color.argb(255, 39, 213, 7)

  private var setInitialCameraPosition = false
  private val cameraMarker = createMarker(redMarkerColor, title = "You")
  private var cameraIdle = true

  var earthMarkers: MutableList<Marker?> = emptyList<Marker?>().toMutableList()

  private val infoWindow: ViewGroup
  private val infoTitle: TextView
  // private val closeButton: MaterialButton
  private val navigateButton: MaterialButton
  private val webPageButton: MaterialButton
  private var selectedMarker: Marker? = null

  private val threeSixty: String
  private val urlTitle: String

  init {
    googleMap.uiSettings.apply {
      isMapToolbarEnabled = false
      isIndoorLevelPickerEnabled = false
      isZoomControlsEnabled = false
      isTiltGesturesEnabled = false
      isScrollGesturesEnabled = true
    }

    googleMap.setOnMarkerClickListener { false }
//    googleMap.setOnInfoWindowClickListener(this)
//    googleMap.setOnInfoWindowCloseListener(this)
    // googleMap.setOnMarkerClickListener(this)

    // Add listeners to keep track of when the GoogleMap camera is moving.
    googleMap.setOnCameraMoveListener { cameraIdle = false }
    googleMap.setOnCameraIdleListener { cameraIdle = true }

    val mapWrapper = activity.findViewById(R.id.map_wrapper) as MapWrapper
    // 39 - default marker height
    // 20 - offset between the default InfoWindow bottom edge and it's content bottom edge
    mapWrapper.init(googleMap, getPixelsFromDp(activity, (39 + 20).toFloat()))

    infoWindow = View.inflate(activity, R.layout.info_window, null) as ViewGroup
    infoTitle = infoWindow.findViewById(R.id.infoTitle) as TextView
    // closeButton = infoWindow.findViewById(R.id.info_close_button) as MaterialButton
    navigateButton = infoWindow.findViewById(R.id.navigate_button) as MaterialButton
    navigateButton.setOnClickListener {
      if (selectedMarker != null && selectedMarker?.title != "You") {
        onMapsNavigationClickHandler(selectedMarker!!)
      }
    }
    webPageButton = infoWindow.findViewById(R.id.web_page_button) as MaterialButton
    webPageButton.setOnClickListener {
      if (selectedMarker != null) {
        onNavigateToWebPageClickHandler(selectedMarker!!)
      }
    }

    threeSixty = activity.resources.getString(R.string.three_sixty)
    urlTitle = activity.resources.getString(R.string.three_sixty)

    googleMap.setInfoWindowAdapter(object : InfoWindowAdapter {
      override fun getInfoWindow(marker: Marker): View? {
        return null
      }

      override fun getInfoContents(marker: Marker): View {
        // Setting up the infoWindow with current's marker info
        infoTitle.text = marker.title

        // We must call this to set the current marker and infoWindow references
        // to the MapWrapperLayout
        val isYou = marker.title == "You"
        navigateButton.isEnabled = !isYou
        val pageUrl = if (marker.tag != null) marker.tag as String else ""
        webPageButton.text = if (pageUrl.contains("vrview")) threeSixty else urlTitle
        webPageButton.isEnabled = !isYou && pageUrl.isNotEmpty()
        mapWrapper.setMarkerWithInfoWindow(marker, infoWindow)
        selectedMarker = marker
        return infoWindow
      }
    })
  }

  fun updateMapPosition(latitude: Double, longitude: Double, heading: Double) {
    val position = LatLng(latitude, longitude)
    activity.runOnUiThread {
      // If the map is already in the process of a camera update, then don't move it.
      if (!cameraIdle) {
        return@runOnUiThread
      }

      cameraMarker?.isVisible = true
      cameraMarker?.position = position
      cameraMarker?.rotation = heading.toFloat()

      val cameraPositionBuilder: CameraPosition.Builder = if (!setInitialCameraPosition) {
        // Set the camera position with an initial default zoom level.
        setInitialCameraPosition = true
        CameraPosition.Builder().zoom(19f).target(position)
      } else {
        // Set the camera position and keep the same zoom level.
        CameraPosition.Builder()
          .zoom(googleMap.cameraPosition.zoom)
          .target(position)
      }

      googleMap.moveCamera(
        CameraUpdateFactory.newCameraPosition(cameraPositionBuilder.build()))
    }
  }

  /** Creates and adds a 2D anchor marker on the 2D map view.  */
  fun createMarker(
    color: Int,
    lat: Double = 0.0,
    lon: Double = 0.0,
    title: String = "",
    url: String = "",
    visible: Boolean = false,
    iconId: Int = R.drawable.ic_arrow_white_48dp,
  ): Marker? {
    val markerOptions = MarkerOptions()
      .position(LatLng(lat, lon))
      .draggable(false)
      .anchor(0.5f, 0.5f)
      .flat(true)
      .visible(visible)
      .icon(BitmapDescriptorFactory.fromBitmap(createColoredMarkerBitmap(color, iconId)))

    if (title.isNotEmpty()) {
      markerOptions.title(title)
    }

    val marker = googleMap.addMarker(markerOptions)
    marker?.tag = url
    return marker
  }

  private fun createColoredMarkerBitmap(@ColorInt color: Int, iconId: Int): Bitmap {
    val opt = BitmapFactory.Options()
    opt.inMutable = true
    val navigationIcon = BitmapFactory.decodeResource(activity.resources, iconId, opt)
    val p = Paint()
    p.colorFilter = LightingColorFilter(color, 1)
    val canvas = Canvas(navigationIcon)
    canvas.drawBitmap(navigationIcon, 0f, 0f, p)
    return navigationIcon
  }

  private fun onNavigateToWebPageClickHandler(marker: Marker) {
    val url: String = marker.tag as String? ?: return
    if (url.isEmpty()) {
      return
    }

    try {
      val webPageIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        // The URL should either launch directly in a non-browser app
        // (if it’s the default), or in the disambiguation dialog
        addCategory(Intent.CATEGORY_BROWSABLE)
        flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
          Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT else Intent.FLAG_ACTIVITY_NEW_TASK
      }
      activity.startActivity(webPageIntent)
    } catch (e: ActivityNotFoundException) {
      Log.e(TAG, "Could not open URL", e)
    }
  }

//  override fun onInfoWindowClick(marker: Marker) {
//    onNavigateToWebPageClickHandler(marker)
//  }

  private fun onMapsNavigationClickHandler(marker: Marker) {
    try {
      val mapsIntentUri = Uri.parse("google.navigation:q=${marker.position.latitude},${marker.position.longitude}&mode=w")
      val mapIntent = Intent(Intent.ACTION_VIEW, mapsIntentUri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
          Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT else Intent.FLAG_ACTIVITY_NEW_TASK
      }
      activity.startActivity(mapIntent)
    } catch (e: ActivityNotFoundException) {
      Log.e(TAG, "Could not navigate to location", e)
    }
  }

  private fun moveMapSoMarkerOnTheBottom(marker: Marker) {
    // https://stackoverflow.com/questions/16764002/how-to-center-the-camera-so-that-marker-is-at-the-bottom-of-screen-google-map
    // https://stackoverflow.com/a/16764140/292502
    val projection = googleMap.projection
    val markerPosition = marker.position
    val markerPoint = projection.toScreenLocation(markerPosition)
    val targetPoint = Point(markerPoint.x, markerPoint.y - activity.view.root.height / 2)
    val targetPosition = projection.fromScreenLocation(targetPoint)
    googleMap.animateCamera(CameraUpdateFactory.newLatLng(targetPosition), 500, null)
  }

//  override fun onMarkerClick(marker: Marker): Boolean {
//    if (selectedMarker == marker) {
//      // TODO: close custom info window
//    } else {
//      selectedMarker = marker
//      moveMapSoMarkerOnTheBottom(marker)
//    }
//
//    return true
//  }

  private fun getPixelsFromDp(context: Context, dp: Float): Int {
    val scale: Float = context.resources.displayMetrics.density
    return (dp * scale + 0.5f).toInt()
  }
}
