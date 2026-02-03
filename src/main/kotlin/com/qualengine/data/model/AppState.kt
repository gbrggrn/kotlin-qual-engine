package com.qualengine.data.model

import kotlin.math.abs
import kotlin.math.min

data class AppState(
    // --- POINTS DATA ---
    val allPoints: List<VectorPoint> = emptyList(),

    // --- VIEWPORT ---
    val width: Double = 0.0,
    val height: Double = 0.0,

    // --- INTERACTION ---
    val hoveredPoint: VectorPoint? = null,
    val selectedPoints: Set<VectorPoint> = emptySet(), // Changed to Set (Immutable)

    // --- MARQUEE SELECTION (Transient State) ---
    val isDragging: Boolean = false,
    val dragStartX: Double = 0.0,
    val dragStartY: Double = 0.0,
    val dragCurrentX: Double = 0.0,
    val dragCurrentY: Double = 0.0,

    // --- CLUSTERING DATA ---
    val clusterCenters: Map<Int, VectorPoint> = emptyMap(),
    val clusterThemes: Map<Int, String> = emptyMap()
) {
    // --- HELPER LOGIC ---
    // Computed property: Calculates bounds on the fly based on the current state variables
    val selectionBounds: Bounds?
        get() {
            if (!isDragging) return null

            val minX = min(dragStartX, dragCurrentX)
            val minY = min(dragStartY, dragCurrentY)
            val w = abs(dragCurrentX - dragStartX)
            val h = abs(dragCurrentY - dragStartY)

            return Bounds(minX, minY, w, h)
        }

    // Helper class for the bounds
    data class Bounds(val x: Double, val y: Double, val w: Double, val h: Double)
}