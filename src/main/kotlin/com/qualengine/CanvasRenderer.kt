package com.qualengine

import com.qualengine.model.ExplorerState

import javafx.scene.canvas.Canvas
import javafx.scene.paint.Color

class CanvasRenderer(private val canvas: Canvas){
    fun render(state: ExplorerState) {
        val graphics = canvas.graphicsContext2D
        val width = canvas.width
        val height = canvas.height

        graphics.fill = Color.web("#2c3e50")
        graphics.fillRect(0.0, 0.0, width, height)

        if (state.renderedAtoms.isEmpty())
            return

        val minX = state.renderedAtoms.minOf { it.x }
        val maxX = state.renderedAtoms.maxOf { it.x }
        val minY = state.renderedAtoms.minOf { it.y }
        val maxY = state.renderedAtoms.maxOf { it.y }

        val rangeX = maxX - minX
        val rangeY = maxY - minY

        val padding = 40.0

        for (atom in state.renderedAtoms) {
            val screenX = ((atom.x - minX) / rangeX ) * (width - padding * 2) + padding
            val screenY = ((atom.y - minY) / rangeY ) * (height - padding * 2) + padding

            when {
                atom == state.hoveredAtom -> {
                    graphics.fill = Color.RED
                    graphics.fillOval(screenX - 4, screenY -4, 8.0, 8.0)

                    graphics.stroke = Color.WHITE
                    graphics.strokeText("ID: ${atom.originalIndex}", screenX + 10, screenY)
                }
                state.selectedAtoms.contains(atom) -> {
                    graphics.fill = Color.YELLOW
                    graphics.fillOval(screenX -3, screenY -3, 6.0, 6.0)
                }
                else -> {
                    graphics.fill = Color.LIGHTGRAY
                    graphics.fillOval(screenX - 2, screenY -2, 4.0, 4.0)
                }
            }
        }
        // Draw box selection (multiples)
        state.getSelectionBounds()?.let { box ->
            graphics.stroke = Color.CYAN
            graphics.lineWidth = 1.0

            graphics.fill = Color.rgb(0, 255, 255, 0.2)
            graphics.fillRect(box.x, box.y, box.w, box.h)
            graphics.strokeRect(box.x, box.y, box.w, box.h)
        }
    }
}