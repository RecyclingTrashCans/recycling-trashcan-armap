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

import android.view.View
import android.widget.TextView
import com.google.android.gms.maps.SupportMapFragment
import com.google.ar.core.Earth
import com.google.ar.core.GeospatialPose
import dev.csaba.armap.recyclingtrashcans.TrashcanGeoActivity
import dev.csaba.armap.recyclingtrashcans.R
import dev.csaba.armap.common.helpers.SnackbarHelper

/** Contains UI elements for Trashcan Geo. */
class TrashcanGeoView(val activity: TrashcanGeoActivity) {
  val root: View = View.inflate(activity, R.layout.activity_main, null)

  val snackbarHelper = SnackbarHelper()

  var mapView: MapView? = null
  val mapFragment =
    (activity.supportFragmentManager.findFragmentById(R.id.map)!! as SupportMapFragment).also {
      it.getMapAsync { googleMap -> mapView = MapView(activity, googleMap) }
    }

  private val statusText: TextView = root.findViewById(R.id.statusText)
  fun updateStatusText(earth: Earth, cameraGeospatialPose: GeospatialPose?) {
    activity.runOnUiThread {
      val poseText = if (cameraGeospatialPose == null) "" else
      activity.getString(R.string.geospatial_pose,
                         cameraGeospatialPose.latitude,
                         cameraGeospatialPose.longitude,
                         cameraGeospatialPose.horizontalAccuracy,
                         cameraGeospatialPose.altitude,
                         cameraGeospatialPose.verticalAccuracy,
                         cameraGeospatialPose.heading,
                         cameraGeospatialPose.headingAccuracy
      )
      val statusMessage = activity.resources.getString(
        R.string.earth_state,
        earth.earthState.toString(),
        earth.trackingState.toString(),
        poseText
      )
      statusText.text = statusMessage
    }
  }

  fun updateStatusTextString(statusMessage: String) {
    activity.runOnUiThread {
      if (!statusText.text.equals(statusMessage)) {
        statusText.text = statusMessage
      }
    }
  }
}
