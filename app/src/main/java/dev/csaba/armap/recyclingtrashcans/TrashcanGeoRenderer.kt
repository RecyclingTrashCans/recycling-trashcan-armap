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
package dev.csaba.armap.recyclingtrashcans

import android.opengl.Matrix
import android.os.CountDownTimer
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.gms.maps.model.LatLng
import com.google.ar.core.Anchor
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import dev.csaba.armap.common.helpers.DisplayRotationHelper
import dev.csaba.armap.common.helpers.TrackingStateHelper
import dev.csaba.armap.common.samplerender.Framebuffer
import dev.csaba.armap.common.samplerender.Mesh
import dev.csaba.armap.common.samplerender.SampleRender
import dev.csaba.armap.common.samplerender.Shader
import dev.csaba.armap.common.samplerender.arcore.BackgroundRenderer
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.*
import kotlin.Comparator
import kotlin.math.*

enum class LocationKind {
  POI,
  TRASHCAN;

  companion object {
    fun getByName(name: String) = valueOf(name.uppercase(Locale.getDefault()))
  }
}

data class LocationData(
  val gpsLocation: LatLng,
  val title: String,
  val kind: LocationKind,
  val url: String,
  val modelMatrix: FloatArray,
  val modelViewMatrix: FloatArray,
  val modelViewProjectionMatrix: FloatArray // projection x view x model
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as LocationData

    if (!title.equals(other.title)) return false
    if (!url.equals(other.url)) return false
    if (gpsLocation != other.gpsLocation) return false
    if (!modelMatrix.contentEquals(other.modelMatrix)) return false
    if (!modelViewMatrix.contentEquals(other.modelViewMatrix)) return false
    if (!modelViewProjectionMatrix.contentEquals(other.modelViewProjectionMatrix)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = gpsLocation.hashCode()
    result = 31 * result + title.hashCode()
    result = 31 * result + url.hashCode()
    result = 31 * result + modelMatrix.contentHashCode()
    result = 31 * result + modelViewMatrix.contentHashCode()
    result = 31 * result + modelViewProjectionMatrix.contentHashCode()
    return result
  }
}

data class MapArea(
  val name: String,
  var center: LatLng,
  val locationData: MutableList<LocationData>
)

class TrashcanGeoRenderer(val activity: TrashcanGeoActivity) :
  SampleRender.Renderer, DefaultLifecycleObserver {
  //<editor-fold desc="ARCore initialization" defaultstate="collapsed">
  companion object {
    const val TAG = "TrashcanGeoRenderer"

    private const val Z_NEAR = 0.1f
    private const val Z_FAR = 1000f

    private const val MEAN_EARTH_RADIUS = 6371.008
    private const val D2R = Math.PI / 180.0

    private const val HOVER_ABOVE_TERRAIN = 0.5  // meters
    private const val AREA_PROXIMITY_THRESHOLD = 0.3  // kilometers
  }

  private lateinit var backgroundRenderer: BackgroundRenderer
  private lateinit var virtualSceneFramebuffer: Framebuffer
  private var hasSetTextureNames = false

  // Virtual object (ARCore pawn)
  private lateinit var downArrowMesh: Mesh
  private lateinit var greenObjectShader: Shader
  private lateinit var mapPinMesh: Mesh
  private lateinit var redObjectShader: Shader

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private val viewMatrix = FloatArray(16)
  private val projectionMatrix = FloatArray(16)
  private val mapAreas: MutableList<MapArea> = emptyList<MapArea>().toMutableList()
  private var areaIndex: Int = -1
  private var loaded = false
  private var populating = false
  private val timer = object: CountDownTimer(2000, 1000) {
    override fun onTick(millisUntilFinished: Long) {}
    override fun onFinish() { onMapClick() }
  }

  private val session
    get() = activity.arCoreSessionHelper.session

  private val displayRotationHelper = DisplayRotationHelper(activity)
  private val trackingStateHelper = TrackingStateHelper(activity)

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
    hasSetTextureNames = false
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  override fun onSurfaceCreated(render: SampleRender) {
    // Prepare the rendering objects.
    // This involves reading shaders and 3D model files, so may throw an IOException.
    try {
      backgroundRenderer = BackgroundRenderer(render)
      virtualSceneFramebuffer = Framebuffer(render, 1, 1)

      // Virtual object to render (Geospatial Marker)
      downArrowMesh = Mesh.createFromAsset(render, "models/down_arrow.obj")
      greenObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_green_object.frag",
          null
        )
      mapPinMesh = Mesh.createFromAsset(render, "models/map_pin.obj")
      redObjectShader =
        Shader.createFromAssets(
          render,
          "shaders/ar_unlit_object.vert",
          "shaders/ar_unlit_red_object.frag",
          null
        )

      backgroundRenderer.setUseDepthVisualization(render, false)
      backgroundRenderer.setUseOcclusion(render, false)

      processLocationArray("csu_fresno", activity.resources.getStringArray(R.array.csu_fresno))
      processLocationArray("park_ridge", activity.resources.getStringArray(R.array.park_ridge))
    } catch (e: IOException) {
      Log.e(TAG, "Failed to read a required asset file", e)
    }
  }

  override fun onSurfaceChanged(render: SampleRender, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
    virtualSceneFramebuffer.resize(width, height)
  }
  //</editor-fold>

  override fun onDrawFrame(render: SampleRender) {
    val session = session ?: return

    //<editor-fold desc="ARCore frame boilerplate" defaultstate="collapsed">
    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))
      hasSetTextureNames = true
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    val frame =
      try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.e(TAG, "Camera not available during onDrawFrame", e)
        return
      }

    val camera = frame.camera

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame)

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    // -- Draw background
    if (frame.timestamp != 0L) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render)
    }

    // If not tracking, don't draw 3D objects.
    if (camera.trackingState == TrackingState.PAUSED) {
      activity.view.updateStatusTextString(activity.resources.getString(R.string.calculating))
      return
    }

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0)

    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f)
    //</editor-fold>

    // Step 1.1.: Obtain Geospatial information and display it on the map.
    val earth = session.earth
    if (earth?.trackingState == TrackingState.TRACKING) {
      if (areaIndex < 0 && !populating && loaded) {
        populating = true
        timer.start()
      }

      val cameraGeospatialPose = earth.cameraGeospatialPose
      activity.view.mapView?.updateMapPosition(
        latitude = cameraGeospatialPose.latitude,
        longitude = cameraGeospatialPose.longitude,
        heading = cameraGeospatialPose.heading
      )

//      if (BuildConfig.BUILD_TYPE.equals("debug")) {
//        activity.view.updateStatusText(earth, cameraGeospatialPose)
//      } else {
        activity.view.updateStatusTextString("")
//      }
    } else if (!BuildConfig.BUILD_TYPE.equals("debug")) {
      activity.view.updateStatusTextString(activity.resources.getString(R.string.calculating))
    }

    // Draw the placed anchors, if they exist.
    for ((index, earthAnchor) in earthAnchors.withIndex()) {
      render.renderObjectAtAnchor(earthAnchor, index)
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR)
  }

  private fun processLocationArray(name: String, locations: Array<String>) {
    val mapArea = MapArea(
      name,
      LatLng(0.0, 0.0),
      emptyList<LocationData>().toMutableList()
    )

    var latSum = 0.0
    var lonSum = 0.0
    for (location in locations) {
      val locationParts = location.split("|")
      val lat = locationParts[0].trim().toDouble()
      val lon = locationParts[1].trim().toDouble()
      val title = if (locationParts.size > 2) locationParts[2].trim() else ""
      val kind = if (locationParts.size > 3) LocationKind.getByName(locationParts[3].trim()) else LocationKind.POI
      val url = if (locationParts.size > 4) locationParts[4].trim() else ""
      mapArea.locationData.add(
        LocationData(
          LatLng(lat, lon),
          title,
          kind,
          url,
          FloatArray(16),
          FloatArray(16),
          FloatArray(16)
        )
      )
      latSum += lat
      lonSum += lon
    }

    mapArea.center = LatLng(latSum / locations.size, lonSum / locations.size)
    Log.i(TAG, "$name center ${mapArea.center.latitude} ${mapArea.center.longitude}")

    var override = -1
    var found = false
    for ((i1, mapA) in mapAreas.withIndex()) {
      if (mapA.name == name) {
        found = true
        if (haversineInKm(mapA.center.latitude, mapA.center.longitude,
            mapArea.center.latitude, mapArea.center.longitude) > 1e-7)
        {
          override = i1
        } else {
          for ((i2, location) in mapArea.locationData.withIndex()) {
            if (haversineInKm(location.gpsLocation.latitude, location.gpsLocation.longitude,
                mapA.locationData[i2].gpsLocation.latitude, mapA.locationData[i2].gpsLocation.longitude) > 1e-7)
            {
              override = i1
              break
            }
          }
        }

        break
      }
    }

    if (!found) {
      mapAreas.add(mapArea)
    } else if (override >= 0) {
      mapAreas[override] = mapArea
    }
  }

  fun processLocations(locationsFile: File) {
    if (!locationsFile.exists()) {
      Log.w(TAG, "Locations file ${locationsFile.path} doesn't exist")
      loaded = true
      return
    }

    val reader = FileReader(locationsFile)
    val locationsXmlContent = reader.readText()
    reader.close()
    val areaMapParts = locationsXmlContent.split("<string-array name=\"")
    if (areaMapParts.size <= 1) {
      loaded = true
      return
    }

    for (areaMapPart in areaMapParts.subList(1, areaMapParts.size)) {
      if (areaMapPart.indexOf('"') < 0) {
        continue
      }

      val name = areaMapPart.split('"')[0]
      val areaLocations: MutableList<String> = emptyList<String>().toMutableList()
      val itemParts = areaMapPart.split("<item>")
      if (itemParts.size <= 1) {
        continue
      }

      for (itemPart in itemParts.subList(1, itemParts.size)) {
        val itemParts2 = itemPart.split("</item>")
        if (itemParts2.isNotEmpty() && itemParts2.indexOf(",") >= 0) {
          areaLocations.add(itemParts2[0])
        }
      }

      if (areaLocations.isNotEmpty()) {
        processLocationArray(name, areaLocations.toTypedArray())
      }
    }

    loaded = true
  }

  private var earthAnchors: MutableList<Anchor> = emptyList<Anchor>().toMutableList()

  fun onMapClick() {
    populating = true
    if (areaIndex >= 0) {
      populating = false
      return
    }

    // Step 1.2.: place an anchor at the given position.
    val earth = session?.earth ?: return
    if (earth.trackingState != TrackingState.TRACKING) {
      activity.view.updateStatusTextString(activity.resources.getString(R.string.calculating))
      populating = false
      return
    }

    val cameraPose = earth.cameraGeospatialPose
    for ((index, mapArea) in mapAreas.withIndex()) {
      val closestLocation = mapArea.locationData.minWithOrNull(Comparator.comparingDouble {
        haversineInKm(it.gpsLocation.latitude, it.gpsLocation.longitude, cameraPose.latitude, cameraPose.longitude)
      })
      if (closestLocation != null) {
        val closestDistance = haversineInKm(
          closestLocation.gpsLocation.latitude, closestLocation.gpsLocation.longitude,
          cameraPose.latitude, cameraPose.longitude
        )
        Log.i(TAG, "Distance from ${mapArea.name} is $closestDistance")
        if (closestDistance < AREA_PROXIMITY_THRESHOLD) {
          areaIndex = index
          break
        }
      }
    }

    if (areaIndex < 0) {
      activity.view.updateStatusTextString(activity.resources.getString(R.string.too_far))
      populating = false
      return
    }

    for (earthAnchor in earthAnchors) {
      earthAnchor.detach()
    }

    // The rotation quaternion of the anchor in EUS coordinates.
    val qx = 0f
    val qy = 0f
    val qz = 0f
    val qw = 1f

    val shouldAddAnchors = earthAnchors.isEmpty()
    val mapView = activity.view.mapView
    val shouldAddMarker = mapView != null && mapView.earthMarkers.isEmpty()
    for (location in mapAreas[areaIndex].locationData) {
      if (shouldAddAnchors) {
        earthAnchors.add(earth.resolveAnchorOnTerrain(
          location.gpsLocation.latitude, location.gpsLocation.longitude, HOVER_ABOVE_TERRAIN, qx, qy, qz, qw))
      }

      if (shouldAddMarker) {
        mapView?.earthMarkers?.add(mapView.createMarker(
          mapView.greenMarkerColor,
          location.gpsLocation.latitude,
          location.gpsLocation.longitude,
          location.title,
          location.url,
          true,
          R.drawable.ic_marker_white_48dp,
        ))
      }
    }

    populating = false
  }

  private fun SampleRender.renderObjectAtAnchor(anchor: Anchor, index: Int) {
    if (areaIndex < 0 || populating) {
      return
    }

    if (anchor.terrainAnchorState != Anchor.TerrainAnchorState.SUCCESS) {
      return
    }

    if (anchor.trackingState != TrackingState.TRACKING) {
      return
    }

    // Get the current pose of the Anchor in world space. The Anchor pose is updated
    // during calls to session.update() as ARCore refines its estimate of the world.
    val locData = mapAreas[areaIndex].locationData[index]
    anchor.pose.toMatrix(locData.modelMatrix, 0)

    // Calculate model/view/projection matrices
    Matrix.multiplyMM(locData.modelViewMatrix, 0, viewMatrix, 0, locData.modelMatrix, 0)
    Matrix.multiplyMM(locData.modelViewProjectionMatrix, 0, projectionMatrix, 0, locData.modelViewMatrix, 0)

    // Update shader properties and draw
    val virtualObjectShader = if (locData.kind == LocationKind.TRASHCAN) greenObjectShader else redObjectShader
    virtualObjectShader.setMat4("u_ModelViewProjection", locData.modelViewProjectionMatrix)
    val virtualObjectMesh = if (locData.kind == LocationKind.TRASHCAN) downArrowMesh else mapPinMesh
    draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer)
  }

  private fun showError(errorMessage: String) =
    activity.view.snackbarHelper.showError(activity, errorMessage)

  private fun showMessage(message: String) =
    activity.view.snackbarHelper.showMessage(activity, message)

  private fun haversineInKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    // https://stackoverflow.com/a/61990886/292502
    // Haversine to decide which locations sets to populate
    val lonDiff = (lon2 - lon1) * D2R
    val latDiff = (lat2 - lat1) * D2R
    val latSin = sin(latDiff / 2.0)
    val lonSin = sin(lonDiff / 2.0)
    val a = latSin * latSin + (cos(lat1 * D2R) * cos(lat2 * D2R) * lonSin * lonSin)
    val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
    return MEAN_EARTH_RADIUS * c
  }
}
