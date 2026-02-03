package com.qualengine.data.model

import com.qualengine.core.math.MathUtils
import kotlin.math.abs
import kotlin.math.min

class ExplorerState {
    // Atoms data
    var allPoints: List<MathUtils.Point2D> = emptyList()
    var pointContents: List<String> = emptyList()

    // Viewport
    var width: Double = 0.0
    var height: Double = 0.0

    // Interaction
    var hoveredPoint: MathUtils.Point2D? = null
    var selectedPoint = mutableSetOf<MathUtils.Point2D>()

    //Marquee selection
    var isDragging: Boolean = false
    var dragStartX: Double = 0.0
    var dragStartY: Double = 0.0
    var dragCurrentX: Double = 0.0
    var dragCurrentY: Double = 0.0

    // Clustering data
    var pointClusterIds: IntArray = IntArray(0)
    var clusterCenters: Map<Int, MathUtils.Point2D> = emptyMap()
    var clusterThemes: MutableMap<Int, String> = mutableMapOf()

    // Clear transient state
    fun getSelectionBounds(): Bounds? {
        if (!isDragging)
            return null

        val minX = min(dragStartX, dragCurrentX)
        val minY = min(dragStartY, dragCurrentY)
        val w = abs(dragCurrentX - dragStartX)
        val h = abs(dragCurrentY - dragStartY)

        return Bounds(minX, minY, w, h)
    }

    data class Bounds(val x: Double, val y: Double, val w: Double, val h: Double)
}