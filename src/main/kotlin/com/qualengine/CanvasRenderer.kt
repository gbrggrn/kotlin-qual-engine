package com.qualengine

import com.qualengine.model.ExplorerState

import javafx.scene.canvas.Canvas
import javafx.scene.paint.Color

class CanvasRenderer(private val canvas: Canvas){
    fun render(state: ExplorerState) {
        val graphics = canvas.graphicsContext2D
        val width = canvas.width
        val height = canvas.height

        // Clear screen
        graphics.fill = Color.web("#2c3e50")
        graphics.fillRect(0.0, 0.0, width, height)

        if (state.allPoints.isEmpty())
            return

        // Calculate scale
        val minX = state.allPoints.minOf { it.x }
        val maxX = state.allPoints.maxOf { it.x }
        val minY = state.allPoints.minOf { it.y }
        val maxY = state.allPoints.maxOf { it.y }

        val rangeX = maxX - minX
        val rangeY = maxY - minY

        val padding = 40.0

        val clusterPalette = listOf(
            Color.CYAN, Color.MAGENTA, Color.LIME, Color.ORANGE, Color.DODGERBLUE, Color.HOTPINK
        )

        for ((index, point) in state.allPoints.withIndex()) {
            val screenX = ((point.x - minX) / rangeX) * (width - padding * 2) + padding
            val screenY = ((point.y - minY) / rangeY) * (height - padding * 2) + padding

            when {
                // Hovered point = yellow
                point == state.hoveredPoint -> {
                    graphics.fill = Color.YELLOW
                    graphics.fillOval(screenX - 4, screenY -4, 8.0, 8.0)

                    graphics.stroke = Color.WHITE
                    graphics.strokeText("ID: ${point.originalIndex}", screenX + 10, screenY)
                }
                // Selected point = red
                state.selectedPoint.contains(point) -> {
                    graphics.fill = Color.RED
                    graphics.fillOval(screenX -3, screenY -3, 6.0, 6.0)
                }
                else -> {
                    val clusterId = state.pointClusterIds.getOrElse(index) { 0 }

                    if (clusterId == -1) {
                        // No cluster = gray
                        graphics.fill = Color.rgb(150, 150, 150, 0.3)
                    } else if (clusterId > 0) {
                        // Clustered = color from palette
                        val colorIndex = (clusterId - 1) % clusterPalette.size
                        graphics.fill = clusterPalette[colorIndex]
                    } else {
                        // Fallback color = white
                        graphics.fill = Color.WHITE
                    }

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

        // Draw cluster labels
        graphics.textAlign = javafx.scene.text.TextAlignment.CENTER
        graphics.textBaseline = javafx.geometry.VPos.CENTER
        graphics.font = javafx.scene.text.Font.font("Arial", javafx.scene.text.FontWeight.BOLD, 14.0)

        for ((id, centerPoint) in state.clusterCenters) {
            // Center point to screen coordinates
            val screenX = ((centerPoint.x - minX) / rangeX) * (width - padding * 2) + padding
            val screenY = ((centerPoint.y - minY) / rangeY) * (height - padding * 2) + padding

            // Get the label
            val label = state.clusterThemes[id] ?: "Group $id"

            // Draw background for text
            val textWidth = label.length * 9.0
            val textHeight = 24.0

            graphics.fill = Color.rgb(0, 0, 0, 0.7)
            graphics.fillRoundRect(
                screenX - (textWidth / 2) - 5,
                screenY - (textHeight / 2),
                textWidth + 10,
                textHeight,
                10.0, 10.0
            )

            // Draw text
            graphics.fill = Color.WHITE
            graphics.fillText(label, screenX, screenY)
        }
    }
}