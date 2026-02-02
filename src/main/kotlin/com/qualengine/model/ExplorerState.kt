package com.qualengine.model

import com.qualengine.logic.MathUtils.Point2D
import com.qualengine.model.Documents.integer
import java.awt.Rectangle

class ExplorerState {
    // Atoms data
    var renderedAtoms: List<Point2D> = emptyList()

    // Interaction state
    var mouseX: Double = 0.0
    var mouseY: Double = 0.0

    var width: Double = 0.0
    var height: Double = 0.0

    var hoveredAtom: Point2D? = null
    var selectedAtoms = mutableSetOf<Point2D>()

    // Viewport
    var zoomLevel: Double = 0.0
    var panOffSetX: Double = 0.0
    var panOffSetY: Double = 0.0

    // Clear transient state
    fun clearSelection() {
        selectedAtoms.clear()
        hoveredAtom = null
    }
}

