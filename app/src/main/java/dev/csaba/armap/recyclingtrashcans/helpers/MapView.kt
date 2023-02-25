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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.ColorInt
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.GoogleMap.OnInfoWindowClickListener
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import dev.csaba.armap.recyclingtrashcans.TrashcanGeoActivity
import dev.csaba.armap.recyclingtrashcans.R

class MapView(private val activity: TrashcanGeoActivity, private val googleMap: GoogleMap): OnInfoWindowClickListener {
  companion object {
    const val TAG = "MapView"
  }

  private val redMarkerColor: Int = Color.argb(255, 255, 0, 0)
  val greenMarkerColor: Int = Color.argb(255, 39, 213, 7)

  private var setInitialCameraPosition = false
  private val cameraMarker = createMarker(redMarkerColor, title = "You")
  private var cameraIdle = true

  var earthMarkers: MutableList<Marker?> = emptyList<Marker?>().toMutableList()

  init {
    googleMap.uiSettings.apply {
      isMapToolbarEnabled = false
      isIndoorLevelPickerEnabled = false
      isZoomControlsEnabled = false
      isTiltGesturesEnabled = false
      isScrollGesturesEnabled = true
    }

    googleMap.setOnMarkerClickListener { false }
    googleMap.setOnInfoWindowClickListener(this)

    // Add listeners to keep track of when the GoogleMap camera is moving.
    googleMap.setOnCameraMoveListener { cameraIdle = false }
    googleMap.setOnCameraIdleListener { cameraIdle = true }
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
    snippet: String = "",
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

    if (snippet.isNotEmpty() && url.isNotEmpty()) {
      markerOptions.snippet(snippet)
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

  override fun onInfoWindowClick(marker: Marker) {
    val url: String = marker.tag as String? ?: return
    if (url.isEmpty()) {
      return
    }

    try {
      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        // The URL should either launch directly in a non-browser app
        // (if it’s the default), or in the disambiguation dialog
        addCategory(Intent.CATEGORY_BROWSABLE)
        flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
          Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_DEFAULT else Intent.FLAG_ACTIVITY_NEW_TASK
      }
      activity.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
      Log.e(TAG, "Could not open URL", e)
    }
  }
}
