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

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.coroutineScope
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import dev.csaba.armap.recyclingtrashcans.helpers.ARCoreSessionLifecycleHelper
import dev.csaba.armap.recyclingtrashcans.helpers.GeoPermissionsHelper
import dev.csaba.armap.recyclingtrashcans.helpers.FileDownloader
import dev.csaba.armap.recyclingtrashcans.helpers.TrashcanGeoView
import dev.csaba.armap.common.helpers.FullScreenHelper
import dev.csaba.armap.common.samplerender.SampleRender
import io.reactivex.BackpressureStrategy
import io.reactivex.android.schedulers.AndroidSchedulers.*
import io.reactivex.disposables.Disposables
import io.reactivex.plugins.RxJavaPlugins
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class TrashcanGeoActivity : AppCompatActivity() {
  companion object {
    private const val TAG = "TrashcanGeoActivity"
    private const val LOCATIONS_FILE_NAME = "locations_v2_2.xml"
    private const val LOCATIONS_URL = "https://recyclingtrashcans.github.io/locations_v2.xml"
  }

  lateinit var arCoreSessionHelper: ARCoreSessionLifecycleHelper
  lateinit var view: TrashcanGeoView
  private lateinit var renderer: TrashcanGeoRenderer
  private val fileDownloader by lazy {
    FileDownloader(
      OkHttpClient.Builder().build()
    )
  }
  private var disposable = Disposables.disposed()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    RxJavaPlugins.setErrorHandler {
      Log.e("Error", it.localizedMessage ?: "")
    }

    // Setup ARCore session lifecycle helper and configuration.
    arCoreSessionHelper = ARCoreSessionLifecycleHelper(this)
    // If Session creation or Session.resume() fails, display a message and log detailed
    // information.
    arCoreSessionHelper.exceptionCallback =
      { exception ->
        val message =
          when (exception) {
            is UnavailableUserDeclinedInstallationException ->
              resources.getString(R.string.install_google_play)
            is UnavailableApkTooOldException -> resources.getString(R.string.update_ar_core)
            is UnavailableSdkTooOldException -> resources.getString(R.string.update_this_app)
            is UnavailableDeviceNotCompatibleException -> resources.getString(R.string.no_ar_support)
            is CameraNotAvailableException -> resources.getString(R.string.camera_not_available)
            else -> resources.getString(R.string.ar_core_exception, exception.toString())
          }
        Log.e(TAG, "ARCore threw an exception", exception)
        view.snackbarHelper.showError(this, message)
      }

    // Configure session features.
    arCoreSessionHelper.beforeSessionResume = ::configureSession
    lifecycle.addObserver(arCoreSessionHelper)

    // Set up the Trashcan AR renderer.
    renderer = TrashcanGeoRenderer(this)
    lifecycle.addObserver(renderer)

    // Set up Trashcan AR UI.
    view = TrashcanGeoView(this)
    lifecycle.addObserver(view)
    setContentView(view.root)

    // Sets up an example renderer using our TrashcanGeoRenderer.
    SampleRender(view.surfaceView, renderer, assets)

    lifecycle.coroutineScope.launch {
      downloadLocationsAsync()
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    disposable.dispose()
  }

  private suspend fun downloadLocationsAsync(): Deferred<Int> = coroutineScope {
    async {
      var cachedFile = File(cacheDir, LOCATIONS_FILE_NAME)

      disposable = fileDownloader.download(LOCATIONS_URL, cachedFile)
        .throttleFirst(100, TimeUnit.MILLISECONDS)
        .toFlowable(BackpressureStrategy.LATEST)
        .subscribeOn(Schedulers.io())
        .observeOn(mainThread())
        .subscribe({
          Log.i(TAG, "$it% Downloaded")
        }, {
          Log.e(TAG, it.localizedMessage, it)
          cachedFile = File(cacheDir, LOCATIONS_FILE_NAME)
          renderer.processLocations(cachedFile)
        }, {
          Log.i(TAG, "Download Complete")
          renderer.processLocations(cachedFile)
        })

      return@async 0
    }
  }

  // Configure the session, setting the desired options according to your usecase.
  private fun configureSession(session: Session) {
    session.configure(
      session.config.apply {
        // Enable Geospatial Mode.
        geospatialMode = Config.GeospatialMode.ENABLED
        // This finding mode is probably the default
        // https://developers.google.com/ar/develop/java/geospatial/terrain-anchors
        planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
      }
    )
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<String>,
    results: IntArray
  ) {
    super.onRequestPermissionsResult(requestCode, permissions, results)
    if (!GeoPermissionsHelper.hasGeoPermissions(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, resources.getString(R.string.permissions_needed), Toast.LENGTH_LONG)
        .show()
      if (!GeoPermissionsHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        GeoPermissionsHelper.launchPermissionSettings(this)
      }
      finish()
    }
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
  }
}
