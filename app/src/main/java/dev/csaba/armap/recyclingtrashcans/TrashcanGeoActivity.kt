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
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.coroutineScope
import dev.csaba.armap.recyclingtrashcans.helpers.FileDownloader
import io.github.sceneview.ar.ArSceneView
import io.github.sceneview.ar.node.ArModelNode
import io.github.sceneview.math.Position
import io.github.sceneview.utils.doOnApplyWindowInsets
import io.github.sceneview.utils.setFullScreen
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


class TrashcanGeoActivity : AppCompatActivity(R.layout.activity_main) {
  companion object {
    private const val TAG = "TrashcanGeoActivity"
    private const val LOCATIONS_FILE_NAME = "locations_v2_2.xml"
    private const val LOCATIONS_URL = "https://recyclingtrashcans.github.io/locations_v2.xml"
  }

  lateinit var sceneView: ArSceneView
  lateinit var loadingView: View
  private val fileDownloader by lazy {
    FileDownloader(
      OkHttpClient.Builder().build()
    )
  }
  private var disposable = Disposables.disposed()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setFullScreen(
      findViewById(R.id.rootView),
      fullScreen = true,
      hideSystemBars = false,
      fitsSystemWindows = false
    )

    sceneView = findViewById(R.id.sceneView)
    loadingView = findViewById(R.id.loadingView)

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
}
