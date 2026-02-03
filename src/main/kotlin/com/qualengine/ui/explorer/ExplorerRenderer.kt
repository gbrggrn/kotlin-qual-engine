package com.qualengine.ui.explorer

import com.qualengine.data.model.AppState
import javafx.geometry.VPos
import javafx.scene.canvas.Canvas
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import kotlin.collections.iterator
import kotlin.math.abs
import kotlin.math.absoluteValue
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

class ExplorerRenderer(private val canvas: Canvas){
    fun render(state: AppState) {
        val graphics = canvas.graphicsContext2D
        val padding = 40.0
        val width = canvas.width
        val height = canvas.height

        // 1. Clear Screen
        graphics.fill = Color.web("#2c3e50")
        graphics.fillRect(0.0, 0.0, width, height)

        if (state.allPoints.isEmpty()) return

        // 2. Calculate Scale
        val drawWidth = width - (padding * 2)
        val drawHeight = height - (padding * 2)

        val clusterPalette = listOf(
            Color.CYAN, Color.MAGENTA, Color.LIME, Color.ORANGE, Color.DODGERBLUE, Color.HOTPINK
        )

        // --- UPDATED: DYNAMIC GRID WIREFRAMES ---
        // 1. Calculate Grid Dimensions (Must match ClusterUtils exactly)
        val validClusterCount = state.clusterCenters.size

        // Default fallback if no clusters exist yet
        var maxIslandRadiusRel = 0.05

        if (validClusterCount > 0) {
            // Calculate Rows/Cols exactly like the Layout Engine
            val cols = ceil(sqrt(validClusterCount.toDouble())).toInt()
            val rows = ceil(validClusterCount.toDouble() / cols).toInt()

            val cellWidth = 1.0 / cols
            val cellHeight = 1.0 / rows

            // 0.40 = 40% of the cell's smallest dimension
            maxIslandRadiusRel = min(cellWidth, cellHeight) * 0.40
        }

        // 2. Calculate Pixel Radius
        val radiusPx = maxIslandRadiusRel * drawWidth

        // 3. Draw the Circles
        graphics.stroke = Color.rgb(255, 255, 255, 0.15)
        graphics.lineWidth = 1.0

        for ((_, centerPoint) in state.clusterCenters) {
            val screenX = (centerPoint.projectedX * drawWidth) + padding
            val screenY = (centerPoint.projectedY * drawHeight) + padding

            graphics.strokeOval(
                screenX - radiusPx,
                screenY - radiusPx,
                radiusPx * 2,
                radiusPx * 2
            )
        }
        // ------------------------------------------------

        // 3. Draw Points
        for ((index, point) in state.allPoints.withIndex()) {
            val safeX = point.projectedX.coerceIn(0.0, 1.0)
            val safeY = point.projectedY.coerceIn(0.0, 1.0)

            val screenX = (safeX * drawWidth) + padding
            val screenY = (safeY * drawHeight) + padding

            when {
                // Hovered
                point == state.hoveredPoint -> {
                    graphics.fill = Color.YELLOW
                    graphics.fillOval(screenX - 4, screenY - 4, 8.0, 8.0)
                    graphics.stroke = Color.WHITE
                    graphics.strokeText("ID: ${point.id}", screenX + 10, screenY)
                }
                // Selected
                state.selectedPoints.contains(point) -> {
                    graphics.fill = Color.RED
                    graphics.fillOval(screenX - 3, screenY - 3, 6.0, 6.0)
                }
                // Normal
                else -> {
                    val clusterId = point.clusterId

                    if (clusterId != -1 && state.clusterCenters.containsKey(clusterId)) {

                        // --- DENSITY METRIC LOGIC ---
                        val center = state.clusterCenters[clusterId]!!
                        val centerX = (center.projectedX * drawWidth) + padding
                        val centerY = (center.projectedY * drawHeight) + padding

                        // Distance from center of island
                        val dx = screenX - centerX
                        val dy = screenY - centerY
                        val dist = sqrt(dx * dx + dy * dy)

                        // Normalize (0.0 = Center, 1.0 = Edge)
                        // radiusPx is the max size of the island we calculated earlier
                        val normalizedDist = (dist / radiusPx).coerceIn(0.0, 1.0)

                        // Dynamic Sizing: Center = 6px, Edge = 2.5px
                        // This creates the "3D Sphere" effect
                        val dotSize = 6.0 - (normalizedDist * 3.5)

                        val colorIndex = abs(clusterId) % clusterPalette.size
                        graphics.fill = clusterPalette[colorIndex]

                        // Draw centered
                        graphics.fillOval(screenX - (dotSize / 2), screenY - (dotSize / 2), dotSize, dotSize)
                    }
                    else {
                        // Background Stars (Faint, tiny, uniform)
                        graphics.fill = Color.rgb(200, 200, 200, 0.15)
                        graphics.fillOval(screenX - 1, screenY - 1, 2.0, 2.0)
                    }
                }
            }
        }

        // 5. Draw Labels
        graphics.textAlign = TextAlignment.CENTER
        graphics.textBaseline = VPos.CENTER
        graphics.font = Font.font("Arial", FontWeight.BOLD, 14.0)

        for ((id, centerPoint) in state.clusterCenters) {
            val screenX = (centerPoint.projectedX * drawWidth) + padding
            val screenY = (centerPoint.projectedY * drawHeight) + padding

            val label = state.clusterThemes[id] ?: "Group $id"

            graphics.fill = Color.WHITE
            graphics.fillText(label, screenX, screenY - radiusPx - 15)
        }
    }
}