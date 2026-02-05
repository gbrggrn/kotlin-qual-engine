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
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow
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
                    val layer = point.layer
                    // If it's a Doc (L3), it has no parent, so use its own ID for the color hue
                    val sourceId = point.parentId ?: point.id

                    val hue = (abs(sourceId.hashCode()) % 360).toDouble()
                    val sourceBaseColor = Color.hsb(hue, 0.7, 0.9)

                    // Base size by current view depth
                    val baseSize = when (state.currentLayer) {
                        3 -> 14.0 // Documents (Anchors)
                        2 -> 7.0  // Paragraphs (Thematic)
                        1 -> 3.0  // Sentences (Dust)
                        else -> 5.0
                    }

                    // --- RENDER BY LAYER ---
                    when (layer) {
                        3 -> { // DOCUMENT LAYER
                            graphics.fill = sourceBaseColor
                            graphics.fillOval(screenX - (baseSize / 2), screenY - (baseSize / 2), baseSize, baseSize)

                            // Text Label for Docs (Title/Origin)
                            graphics.fill = Color.WHITE
                            graphics.font = Font.font("System", FontWeight.BOLD, 10.0)
                            graphics.fillText(point.metaData.take(15), screenX, screenY + baseSize + 5)
                        }

                        2 -> { // PARAGRAPH LAYER
                            // Keep your decay logic if you like it, but check for center existence
                            var finalSize = baseSize
                            val clusterId = point.clusterId

                            if (clusterId != -1 && state.clusterCenters.containsKey(clusterId)) {
                                val center = state.clusterCenters[clusterId]!!
                                val centerX = (center.projectedX * drawWidth) + padding
                                val centerY = (center.projectedY * drawHeight) + padding
                                val dist = sqrt((screenX - centerX).pow(2) + (screenY - centerY).pow(2))
                                finalSize = baseSize - ((dist / radiusPx).coerceIn(0.0, 1.0) * (baseSize * 0.4))
                            }

                            graphics.fill = sourceBaseColor
                            graphics.fillOval(
                                screenX - (finalSize / 2),
                                screenY - (finalSize / 2),
                                finalSize,
                                finalSize
                            )

                            // Subtle rim
                            graphics.stroke = Color.rgb(255, 255, 255, 0.5)
                            graphics.strokeOval(
                                screenX - (finalSize / 2),
                                screenY - (finalSize / 2),
                                finalSize,
                                finalSize
                            )
                        }

                        1 -> { // SENTENCE LAYER
                            graphics.fill = sourceBaseColor.deriveColor(0.0, 1.0, 1.2, 0.4) // Semi-transparent dust
                            graphics.fillOval(screenX - (baseSize / 2), screenY - (baseSize / 2), baseSize, baseSize)
                        }
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