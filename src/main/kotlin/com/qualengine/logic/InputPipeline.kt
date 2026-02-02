package com.qualengine.logic

import com.qualengine.model.ExplorerState
import com.qualengine.logic.MathUtils.Point2D
import javafx.event.Event
import javafx.scene.input.MouseEvent
import kotlin.math.pow
import kotlin.math.sqrt

class InputPipeline(private val state: ExplorerState) {
    private val PADDING = 40.0

    fun handleMousePressed(event: MouseEvent){
        state.isDragging = true
        state.dragStartX = event.x
        state.dragStartY = event.y
        state.dragCurrentX = event.x
        state.dragCurrentY = event.y

        // Clear selection UNLESS holding shift
        if (!event.isShiftDown){
            state.selectedAtoms.clear()
        }
    }

    fun handleMouseDragged(event: MouseEvent) {
        if (!state.isDragging)
            return

        state.dragCurrentX = event.x
        state.dragCurrentY = event.y

        // Recalculate selection rectangle
        val bounds = state.getSelectionBounds()?: return

        if (state.renderedAtoms.isEmpty())
            return

        val minX = state.renderedAtoms.minOf { it.x }
        val minY = state.renderedAtoms.minOf { it.y }
        val maxX = state.renderedAtoms.maxOf { it.x }
        val maxY = state.renderedAtoms.maxOf { it.y }
        val rangeX = maxX - minX
        val rangeY = maxY - minY
        val width = state.width
        val height = state.height

        for (atom in state.renderedAtoms){
            val screenX = ((atom.x - minX) / rangeX) * (width - PADDING * 2) + PADDING
            val screenY = ((atom.y - minY) / rangeY) * (height - PADDING * 2) + PADDING

            if (screenX >= bounds.x && screenX <= bounds.x + bounds.w &&
                screenY >= bounds.y && screenY <= bounds.y + bounds.h) {
                state.selectedAtoms.add(atom)
            }
        }
    }

    fun handleMouseReleased(event: MouseEvent) {
        state.isDragging = false
    }

    fun handleMove(event: MouseEvent) {
        val mouseX = event.x
        val mouseY = event.y
        val width = state.width
        val height = state.height

        // Determine canvas size (same as in CanvasRenderer)
        if (state.renderedAtoms.isEmpty())
            return

        val minX = state.renderedAtoms.minOf { it.x }
        val maxX = state.renderedAtoms.maxOf { it.x }
        val minY = state.renderedAtoms.minOf { it.y }
        val maxY = state.renderedAtoms.maxOf { it.y }

        // Begin testing for hits
        var closestAtom: Point2D? = null
        var minDistance = 10.0

        for (atom in state.renderedAtoms) {
            // Convert points to coordinates on the canvas
            val screenX = ((atom.x - minX) / (maxX - minX)) * (width - PADDING * 2) + PADDING
            val screenY = ((atom.y - minY) / (maxY - minY)) * (height - PADDING * 2) + PADDING

            // Calculate distance
            val distance = sqrt((mouseX - screenX).pow(2) + (mouseY - screenY).pow(2))

            // Check if distance is WITHIN min distance
            if (distance < minDistance) {
                minDistance = distance
                closestAtom = atom
            }
        }
        // Update state
        state.hoveredAtom = closestAtom
    }

    fun handleClick(event: MouseEvent) {
        // If hovering - toggle selection
        state.hoveredAtom?.let { atom ->
            if (state.selectedAtoms.contains(atom)) {
                state.selectedAtoms.remove(atom)
            } else {
                state.selectedAtoms.add(atom)
            }
        }?: run {
            // Clear if empty space is clicked
            state.selectedAtoms.clear()
        }
    }
}