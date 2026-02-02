package com.qualengine.model

import com.qualengine.logic.MathUtils.Point2D

class ExplorerState {
    // Atoms data
    var allPoints: List<Point2D> = emptyList()
    var moleculeContents: List<String> = emptyList()

    // Viewport
    var width: Double = 0.0
    var height: Double = 0.0

    // Interaction
    var hoveredPoint: Point2D? = null
    var selectedPoint = mutableSetOf<Point2D>()

    //Marquee selection
    var isDragging: Boolean = false
    var dragStartX: Double = 0.0
    var dragStartY: Double = 0.0
    var dragCurrentX: Double = 0.0
    var dragCurrentY: Double = 0.0

    // Clustering data
    var pointClusterIds: IntArray = IntArray(0)
    var clusterCenters: Map<Int, Point2D> = emptyMap()
    var clusterThemes: MutableMap<Int, String> = mutableMapOf()

    // Clear transient state
    fun getSelectionBounds(): Bounds? {
        if (!isDragging)
            return null

        val minX = kotlin.math.min(dragStartX, dragCurrentX)
        val minY = kotlin.math.min(dragStartY, dragCurrentY)
        val w = kotlin.math.abs(dragCurrentX - dragStartX)
        val h = kotlin.math.abs(dragCurrentY - dragStartY)

        return Bounds(minX, minY, w, h)
    }

    data class Bounds(val x: Double, val y: Double, val w: Double, val h: Double)
}

