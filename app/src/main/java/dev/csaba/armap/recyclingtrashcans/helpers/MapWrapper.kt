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

import android.content.Context
// import android.graphics.Point
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
// import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker
// import kotlin.math.sqrt


/**
 * https://stackoverflow.com/questions/14123243/google-maps-android-api-v2-interactive-infowindow-like-in-original-android-go/15040761#15040761
 * https://stackoverflow.com/a/15040761/292502
 */
class MapWrapper : FrameLayout {
  /**
   * Reference to a GoogleMap object
   */
  private var map: GoogleMap? = null

  /**
   * Vertical offset in pixels between the bottom edge of our InfoWindow
   * and the marker position (by default it's bottom edge too).
   * It's a good idea to use custom markers and also the InfoWindow frame,
   * because we probably can't rely on the sizes of the default marker and frame.
   */
  private var bottomOffsetPixels = 0

  /**
   * A currently selected marker
   */
  private var marker: Marker? = null

  /**
   * Our custom view which is returned from either the InfoWindowAdapter.getInfoContents
   * or InfoWindowAdapter.getInfoWindow
   */
  private var infoWindow: View? = null

  constructor(context: Context) : super(context)

  constructor(context: Context, attributeSet: AttributeSet?) : super(context, attributeSet)

  constructor(context: Context, attributeSet: AttributeSet?, defStyle: Int) : super(context, attributeSet, defStyle)

  /**
   * Must be called before we can route the touch events
   */
  fun init(map: GoogleMap?, bottomOffsetPixels: Int) {
    this.map = map
    this.bottomOffsetPixels = bottomOffsetPixels
  }

  /**
   * Best to be called from either the InfoWindowAdapter.getInfoContents
   * or InfoWindowAdapter.getInfoWindow.
   */
  fun setMarkerWithInfoWindow(marker: Marker?, infoWindow: View?) {
    this.marker = marker
    this.infoWindow = infoWindow
  }

  override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
    var ret = false
    // Make sure that the infoWindow is shown and we have all the needed references
    if (marker != null && marker!!.isInfoWindowShown && map != null && infoWindow != null) {
      // Get a marker position on the screen
      val point = map!!.projection.toScreenLocation(marker!!.position)

      // Make a copy of the MotionEvent and adjust it's location
      // so it is relative to the infoWindow left top corner
      val copyEv = MotionEvent.obtain(ev)
      copyEv.offsetLocation(
        (-point.x + infoWindow!!.width / 2).toFloat(),
        (-point.y + infoWindow!!.height + bottomOffsetPixels).toFloat()
      )

      // Dispatch the adjusted MotionEvent to the infoWindow
      ret = infoWindow!!.dispatchTouchEvent(copyEv)
    }

    // If the infoWindow consumed the touch event, then just return true.
    // Otherwise pass this event to the super class and return it's result
    return ret || super.dispatchTouchEvent(ev)
  }

/*
  private var touchSlop = 0
  private var down: Point? = null
  private var listener: ((Point) -> Unit)? = null

  constructor(context: Context) : super(context) {
    setup(context)
  }

  constructor(context: Context, attributeSet: AttributeSet?) : super(context, attributeSet) {
    setup(context)
  }

  private fun setup(context: Context) {
    val vc = ViewConfiguration.get(context)
    touchSlop = vc.scaledTouchSlop
  }

  fun setup(listener: ((Point) -> Unit)?) {
    this.listener = listener
  }

  private fun distance(p1: Point, p2: Point): Double {
    val xDiff = (p1.x - p2.x).toDouble()
    val yDiff = (p1.y - p2.y).toDouble()
    return sqrt(xDiff * xDiff + yDiff * yDiff)
  }

  override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
    if (listener == null) {
      return false
    }
    val x = event.x.toInt()
    val y = event.y.toInt()
    val tapped = Point(x, y)
    when (event.action) {
      MotionEvent.ACTION_DOWN -> down = tapped
      MotionEvent.ACTION_MOVE -> if (down != null && distance(down!!, tapped) >= touchSlop) {
        down = null
      }
      MotionEvent.ACTION_UP -> if (down != null && distance(down!!, tapped) < touchSlop) {
        listener?.invoke(tapped)
        return true
      }
      else -> {
      }
    }
    return false
  }
 */
}