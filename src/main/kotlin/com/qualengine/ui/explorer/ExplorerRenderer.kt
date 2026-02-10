package com.qualengine.ui.explorer

import com.qualengine.data.model.AppState
import com.qualengine.data.model.VectorPoint
import javafx.geometry.VPos
import javafx.scene.canvas.Canvas
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import javafx.scene.effect.DropShadow
import kotlin.collections.iterator
import kotlin.math.abs

class ExplorerRenderer(private val canvas: Canvas) {

    // 1. CLUSTER COLORS (For the Hulls/Backgrounds)
    // Uses Golden Angle to ensure distinct territories
    private fun getClusterColor(id: Int, opacity: Double = 1.0): Color {
        if (id == -1) return Color.rgb(150, 150, 150, opacity)
        val hue = (abs(id) * 137.508) % 360
        return Color.hsb(hue, 0.75, 1.0, opacity)
    }

    // 2. DOCUMENT COLORS (For the Points)
    // Uses HashCode to give every document a stable, unique color
    private fun getSourceColor(point: VectorPoint, opacity: Double = 1.0): Color {
        // If it's a paragraph (L2), use its parent ID. If Doc (L3), use its own ID.
        val sourceId = point.parentId ?: point.id
        val hue = (kotlin.math.abs(sourceId.hashCode()) % 360).toDouble()
        // We use a slightly different saturation/brightness so points pop against the hulls
        return Color.hsb(hue, 0.6, 0.95, opacity)
    }

    fun render(state: AppState) {
        val graphics = canvas.graphicsContext2D
        val padding = 40.0
        val width = canvas.width
        val height = canvas.height
        val drawWidth = width - (padding * 2)
        val drawHeight = height - (padding * 2)

        // Clear Screen
        graphics.fill = Color.web("#1e272e")
        graphics.fillRect(0.0, 0.0, width, height)

        if (state.allPoints.isEmpty()) return

        // ==================================================
        // PHASE 1: ATMOSPHERE (The Hulls) -> COLORED BY TOPIC
        // ==================================================
        if (state.currentLayer == 2) {
            for ((id, virtualCenterPoint) in state.clusterCenters) {
                val screenX = (virtualCenterPoint.x * drawWidth) + padding
                val screenY = (virtualCenterPoint.y * drawHeight) + padding
                val radiusPx = virtualCenterPoint.radius * drawWidth

                println("Center: ${virtualCenterPoint.clusterId}. Radius: $radiusPx")

                val baseColor = getClusterColor(id, 0.15) // 15% Opacity
                val borderColor = getClusterColor(id, 0.4) // 40% Opacity

                // Draw Hull
                graphics.fill = baseColor
                graphics.fillOval(screenX - radiusPx, screenY - radiusPx, radiusPx * 2, radiusPx * 2)
                graphics.stroke = borderColor
                graphics.lineWidth = 1.0
                graphics.strokeOval(screenX - radiusPx, screenY - radiusPx, radiusPx * 2, radiusPx * 2)
            }
        }

        // ==================================================
        // PHASE 2: PLANETS (The Points) -> COLORED BY DOCUMENT
        // ==================================================
        for (point in state.allPoints) {
            val safeX = point.projectedX.coerceIn(0.0, 1.0)
            val safeY = point.projectedY.coerceIn(0.0, 1.0)
            val screenX = (safeX * drawWidth) + padding
            val screenY = (safeY * drawHeight) + padding

            // COLOR STRATEGY: By Source (Document)
            val pointColor = getSourceColor(point, 0.9)

            when {
                // Hovered
                point == state.hoveredPoint -> {
                    graphics.fill = Color.WHITE
                    graphics.fillOval(screenX - 5, screenY - 5, 10.0, 10.0)
                    graphics.stroke = Color.WHITE
                    graphics.lineWidth = 1.0
                    graphics.strokeText("ID: ${point.id}", screenX + 12, screenY)
                }
                // Selected
                state.selectedPoints.contains(point) -> {
                    graphics.fill = Color.CYAN
                    graphics.fillOval(screenX - 4, screenY - 4, 8.0, 8.0)
                    graphics.stroke = Color.WHITE
                    graphics.lineWidth = 2.0
                    graphics.strokeOval(screenX - 4, screenY - 4, 8.0, 8.0)
                }
                // Normal
                else -> {
                    val baseSize = 6.0
                    graphics.fill = pointColor
                    graphics.fillOval(screenX - (baseSize / 2), screenY - (baseSize / 2), baseSize, baseSize)

                    // Black rim to make the dot distinct from the colored background
                    graphics.stroke = Color.rgb(0, 0, 0, 0.6)
                    graphics.lineWidth = 0.5
                    graphics.strokeOval(screenX - (baseSize / 2), screenY - (baseSize / 2), baseSize, baseSize)
                }
            }
        }

        // ==================================================
        // PHASE 3: LABELS
        // ==================================================
        // ... (Same label logic as before) ...
        graphics.textAlign = TextAlignment.CENTER
        graphics.textBaseline = VPos.BOTTOM
        graphics.font = Font.font("Segoe UI", FontWeight.BOLD, 12.0)

        if (state.currentLayer == 2) {
            for ((id, virtualCenterPoint) in state.clusterCenters) {
                val screenX = (virtualCenterPoint.x * drawWidth) + padding
                val screenY = (virtualCenterPoint.y * drawHeight) + padding
                val radiusPx = virtualCenterPoint.radius * drawWidth
                val label = state.clusterThemes[id] ?: "Group $id"

                graphics.fill = Color.rgb(255, 255, 255, 0.9)
                graphics.setEffect(javafx.scene.effect.DropShadow(2.0, Color.BLACK))
                graphics.fillText(label, screenX, screenY - radiusPx - 6)
                graphics.setEffect(null)
            }
        }
    }
}