package com.qualengine.data.pipeline

import javafx.scene.input.MouseEvent
import com.qualengine.core.AnalysisContext
import com.qualengine.data.model.VectorPoint
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.pow

class InputPipeline(
    // We keep the dependency injection pattern you had
    private val context: AnalysisContext
) {
    private val PADDING = 40.0
    private val DRAG_THRESHOLD = 5.0
    private var isRealDrag = false

    // --- HELPER: Coordinate Transform ---
    // Your old code calculated min/max every frame.
    // The new pipeline guarantees projectedX is 0..1, so we just scale it.
    private fun toScreenX(normalizedX: Double, width: Double): Double {
        return normalizedX * (width - PADDING * 2) + PADDING
    }

    private fun toScreenY(normalizedY: Double, height: Double): Double {
        return normalizedY * (height - PADDING * 2) + PADDING
    }

    fun handleMousePressed(event: MouseEvent) {
        val current = context.state

        isRealDrag = false

        // Logic: Clear selection UNLESS holding shift/control
        val newSelection = if (!event.isShiftDown && !event.isShortcutDown) {
            emptySet()
        } else {
            current.selectedPoints
        }

        context.update(current.copy(
            isDragging = true,
            dragStartX = event.x,
            dragStartY = event.y,
            dragCurrentX = event.x,
            dragCurrentY = event.y,
            selectedPoints = newSelection
        ))
    }

    fun handleMouseDragged(event: MouseEvent) {
        val current = context.state
        if (!current.isDragging) return

        // 1. Check Threshold (Your "Real Drag" Logic)
        if (!isRealDrag) {
            val dist = hypot(event.x - current.dragStartX, event.y - current.dragStartY)
            if (dist < DRAG_THRESHOLD) return // Ignore jitters
            isRealDrag = true
        }

        // 2. Calculate Marquee Bounds
        // We use min/max to ensure x is always top-left, even if dragging backwards
        val boundsX = minOf(current.dragStartX, event.x)
        val boundsY = minOf(current.dragStartY, event.y)
        val boundsW = kotlin.math.abs(event.x - current.dragStartX)
        val boundsH = kotlin.math.abs(event.y - current.dragStartY)

        // 3. Find Points Inside
        // OPTIMIZATION: We don't need to recalculate min/max of the dataset here.
        // We assume projectedX is already normalized (0..1).
        val captured = current.allPoints.filter { point ->
            val sx = toScreenX(point.projectedX, current.width)
            val sy = toScreenY(point.projectedY, current.height)

            sx >= boundsX && sx <= boundsX + boundsW &&
                    sy >= boundsY && sy <= boundsY + boundsH
        }.toSet()

        // 4. Update State
        // Note: If shift is held, we usually ADD to selection, but for marquee
        // it's often simpler to replace. Let's assume replace for the drag box.
        context.update(current.copy(
            dragCurrentX = event.x,
            dragCurrentY = event.y,
            selectedPoints = captured
        ))
    }

    fun handleMouseReleased(event: MouseEvent) {
        val current = context.state
        context.update(current.copy(isDragging = false))
    }

    fun handleMouseMove(event: MouseEvent) {
        val current = context.state
        if (current.isDragging || current.allPoints.isEmpty()) return

        val mouseX = event.x
        val mouseY = event.y

        // --- HIT TESTING ---
        var closestAtom: VectorPoint? = null
        var minDistance = 10.0 // Interaction Radius

        for (point in current.allPoints) {
            val sx = toScreenX(point.projectedX, current.width)
            val sy = toScreenY(point.projectedY, current.height)

            val distance = sqrt((mouseX - sx).pow(2) + (mouseY - sy).pow(2))

            if (distance < minDistance) {
                minDistance = distance
                closestAtom = point
            }
        }

        // Only update if something changed (Performance check)
        if (current.hoveredPoint != closestAtom) {
            context.update(current.copy(hoveredPoint = closestAtom))
        }
    }

    fun handleClick(event: MouseEvent) {
        val current = context.state
        if (current.isDragging && isRealDrag) return // Ignore click if we just finished a drag

        // Logic: Toggle selection if hovering
        current.hoveredPoint?.let { atom ->
            // Create a new set based on the old one
            val newSelection = if (current.selectedPoints.contains(atom)) {
                current.selectedPoints - atom // Toggle Off
            } else {
                current.selectedPoints + atom // Toggle On
            }

            context.update(current.copy(selectedPoints = newSelection))

        } ?: run {
            // Clicked empty space -> Clear
            if (!event.isShiftDown && !event.isShortcutDown) {
                context.update(current.copy(selectedPoints = emptySet()))
            }
        }
    }
}