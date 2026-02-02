package com.qualengine.logic

import com.qualengine.model.ExplorerState
import com.qualengine.logic.MathUtils.Point2D
import javafx.scene.input.MouseEvent
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

class InputPipeline(private val state: ExplorerState) {
    private val PADDING = 40.0
    private val DRAG_THRESHOLD = 5.0
    private var isRealDrag = false

    fun handleMousePressed(event: MouseEvent){
        state.isDragging = true
        state.dragStartX = event.x
        state.dragStartY = event.y
        state.dragCurrentX = event.x
        state.dragCurrentY = event.y

        isRealDrag = false

        // Clear selection UNLESS holding shift/control
        if (!event.isShiftDown && !event.isShortcutDown){
            state.selectedPoint.clear()
        }
    }

    fun handleMouseDragged(event: MouseEvent) {
        if (!state.isDragging)
            return

        state.dragCurrentX = event.x
        state.dragCurrentY = event.y

        // Check if it's a "real" drag
        val dist = hypot(event.x - state.dragStartX, event.y - state.dragStartY)

        if (!isRealDrag && dist < DRAG_THRESHOLD)
            return

        // Recalculate selection rectangle
        val bounds = state.getSelectionBounds() ?: return

        if (state.allPoints.isEmpty())
            return

        val minX = state.allPoints.minOf { it.x }
        val minY = state.allPoints.minOf { it.y }
        val maxX = state.allPoints.maxOf { it.x }
        val maxY = state.allPoints.maxOf { it.y }
        val rangeX = (maxX - minX).coerceAtLeast(0.0001)
        val rangeY = (maxY - minY).coerceAtLeast(0.0001)
        val width = state.width
        val height = state.height

        for (point in state.allPoints){
            val screenX = ((point.x - minX) / rangeX) * (width - PADDING * 2) + PADDING
            val screenY = ((point.y - minY) / rangeY) * (height - PADDING * 2) + PADDING

            if (screenX >= bounds.x && screenX <= bounds.x + bounds.w &&
                screenY >= bounds.y && screenY <= bounds.y + bounds.h) {
                state.selectedPoint.add(point)
            }
        }
    }

    fun handleMouseReleased(event: MouseEvent) {
        state.isDragging = false
    }

    fun handleMouseMove(event: MouseEvent) {
        if (state.isDragging)
            return

        val mouseX = event.x
        val mouseY = event.y
        val width = state.width
        val height = state.height

        // Determine canvas size (same as in CanvasRenderer)
        if (state.allPoints.isEmpty())
            return

        val minX = state.allPoints.minOf { it.x }
        val maxX = state.allPoints.maxOf { it.x }
        val minY = state.allPoints.minOf { it.y }
        val maxY = state.allPoints.maxOf { it.y }

        // Begin testing for hits
        var closestAtom: Point2D? = null
        var minDistance = 10.0

        for (point in state.allPoints) {
            // Convert points to coordinates on the canvas
            val screenX = ((point.x - minX) / (maxX - minX)) * (width - PADDING * 2) + PADDING
            val screenY = ((point.y - minY) / (maxY - minY)) * (height - PADDING * 2) + PADDING

            // Calculate distance
            val distance = sqrt((mouseX - screenX).pow(2) + (mouseY - screenY).pow(2))

            // Check if distance is WITHIN min distance
            if (distance < minDistance) {
                minDistance = distance
                closestAtom = point
            }
        }
        // Update state
        state.hoveredPoint = closestAtom
    }

    fun handleClick(event: MouseEvent) {
        if (state.isDragging)
            return

        // Only handle click if real drag
        if (!isRealDrag) {
            // If hovering - toggle selection
            state.hoveredPoint?.let { atom ->
                if (state.selectedPoint.contains(atom)) {
                    state.selectedPoint.remove(atom)
                } else {
                    state.selectedPoint.add(atom)
                }
            }?: run {
                // Clear if empty space is clicked
                state.selectedPoint.clear()
            }
        }
    }
}