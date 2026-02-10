package com.qualengine.ui.explorer

import com.qualengine.data.model.AppState
import com.qualengine.data.model.VectorPoint
import javafx.geometry.VPos
import javafx.scene.canvas.Canvas
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import kotlin.collections.iterator

class ExplorerRenderer(
    private val canvas: Canvas,
    private val coordinateMapper: CoordinateMapper
) {

    // Helper: Distinct colors for clusters
    private fun getClusterColor(id: Int, opacity: Double = 1.0): Color {
        if (id == -1) return Color.rgb(150, 150, 150, opacity)
        val hue = (kotlin.math.abs(id) * 137.508) % 360
        return Color.hsb(hue, 0.75, 1.0, opacity)
    }

    // Helper: Distinct colors for documents
    private fun getSourceColor(point: VectorPoint, opacity: Double = 1.0): Color {
        val sourceId = point.parentId ?: point.id
        val hue = (kotlin.math.abs(sourceId.hashCode()) % 360).toDouble()
        return Color.hsb(hue, 0.6, 0.95, opacity)
    }

    fun render(state: AppState) {
        val graphics = canvas.graphicsContext2D
        val width = canvas.width
        val height = canvas.height
        val camera = state.camera

        // 1. CLEAR SCREEN
        graphics.fill = Color.web("#1e272e")
        graphics.fillRect(0.0, 0.0, width, height)

        if (state.allPoints.isEmpty()) return

        // 2. CALCULATE LEVEL OF DETAIL (LOD)
        // This is the "Semantic Zoom" logic
        val zoom = camera.zoom

        // Strategy:
        // Zoom < 0.2  : Galaxy View (Hulls + Big Labels only)
        // Zoom > 0.2  : Region View (Points appear as dots)
        // Zoom > 1.5  : Street View (Text snippets appear)
        val showHulls = true
        val showPoints = zoom > 0.2
        val showDetails = zoom > 1.5
        val fadeClusterLabels = zoom > 2.0 // Hide big theme labels when deep in the weeds

        // ==================================================
        // PHASE 1: THE TERRITORIES (Hulls)
        // ==================================================
        if (showHulls) {
            for ((id, center) in state.clusterCenters) {
                // Transform Center to Screen
                val screenPos = coordinateMapper.worldToScreen(center.x, center.y, camera)

                // Transform Radius (World Units -> Screen Pixels)
                // Note: We use the raw physics radius here.
                val radiusPx = center.radius * zoom

                // Cull off-screen hulls for performance
                if (screenPos.x + radiusPx < 0 || screenPos.x - radiusPx > width ||
                    screenPos.y + radiusPx < 0 || screenPos.y - radiusPx > height) {
                    continue
                }

                val baseColor = getClusterColor(id, 0.15)
                val borderColor = getClusterColor(id, 0.4)

                graphics.fill = baseColor
                graphics.fillOval(screenPos.x - radiusPx, screenPos.y - radiusPx, radiusPx * 2, radiusPx * 2)

                graphics.stroke = borderColor
                graphics.lineWidth = 1.0
                graphics.strokeOval(screenPos.x - radiusPx, screenPos.y - radiusPx, radiusPx * 2, radiusPx * 2)
            }
        }

        // ==================================================
        // PHASE 2: THE POPULATION (Points)
        // ==================================================
        if (showPoints) {
            for (point in state.allPoints) {
                val screenPos = coordinateMapper.worldToScreen(point.projectedX, point.projectedY, camera)

                // Cull off-screen points
                if (screenPos.x < -10 || screenPos.x > width + 10 ||
                    screenPos.y < -10 || screenPos.y > height + 10) {
                    continue
                }

                // Size scales slightly with zoom, but clamps to avoid becoming huge
                val pointSize = (4.0 * zoom).coerceIn(2.0, 12.0)
                val pointColor = getSourceColor(point, 0.9)

                // VISUAL STATES
                when {
                    // Hovered
                    point == state.hoveredPoint -> {
                        graphics.fill = Color.WHITE
                        graphics.fillOval(screenPos.x - 4, screenPos.y - 4, 8.0, 8.0)

                        // Tooltip (Always visible on hover)
                        graphics.stroke = Color.WHITE
                        graphics.lineWidth = 1.0
                        graphics.strokeText("ID: ${point.id.take(8)}...", screenPos.x + 10, screenPos.y)
                    }
                    // Selected
                    state.selectedPoints.contains(point) -> {
                        graphics.fill = Color.CYAN
                        graphics.fillOval(screenPos.x - 3, screenPos.y - 3, 6.0, 6.0)
                        graphics.stroke = Color.WHITE
                        graphics.lineWidth = 1.5
                        graphics.strokeOval(screenPos.x - 3, screenPos.y - 3, 6.0, 6.0)
                    }
                    // Normal
                    else -> {
                        graphics.fill = pointColor
                        graphics.fillOval(screenPos.x - (pointSize / 2), screenPos.y - (pointSize / 2), pointSize, pointSize)
                    }
                }

                // DETAILED TEXT (Only at high zoom)
                if (showDetails) {
                    graphics.fill = Color.rgb(255, 255, 255, 0.7)
                    graphics.font = Font.font("Arial", 10.0)
                    // Draw snippet
                    val text = point.metaData.take(15)
                    graphics.fillText(text, screenPos.x + 8, screenPos.y + 4)
                }
            }
        }

        // ==================================================
        // PHASE 3: THE MAP LABELS (Cluster Themes)
        // ==================================================
        // We draw these last so they float on top
        if (!fadeClusterLabels) {
            graphics.textAlign = TextAlignment.CENTER
            graphics.textBaseline = VPos.BOTTOM
            graphics.font = Font.font("Segoe UI", FontWeight.BOLD, 14.0)

            for ((id, center) in state.clusterCenters) {
                val screenPos = coordinateMapper.worldToScreen(center.x, center.y, camera)
                val radiusPx = center.radius * zoom

                // Simple culling
                if (screenPos.x < 0 || screenPos.x > width || screenPos.y < 0 || screenPos.y > height) continue

                val label = state.clusterThemes[id] ?: "Group $id"

                graphics.fill = Color.rgb(255, 255, 255, 0.9)
                graphics.setEffect(javafx.scene.effect.DropShadow(2.0, Color.BLACK))
                graphics.fillText(label, screenPos.x, screenPos.y - radiusPx - 5)
                graphics.setEffect(null)
            }
        }

        // ==================================================
        // PHASE 4: SELECTION MARQUEE
        // ==================================================
        if (state.isDragging) {
            val startX = minOf(state.dragStartX, state.dragCurrentX)
            val startY = minOf(state.dragStartY, state.dragCurrentY)
            val boxW = kotlin.math.abs(state.dragCurrentX - state.dragStartX)
            val boxH = kotlin.math.abs(state.dragCurrentY - state.dragStartY)

            // Draw semi-transparent blue box
            graphics.fill = Color.rgb(0, 120, 255, 0.2)
            graphics.fillRect(startX, startY, boxW, boxH)

            // Draw border
            graphics.stroke = Color.rgb(0, 120, 255, 0.8)
            graphics.lineWidth = 1.0
            graphics.strokeRect(startX, startY, boxW, boxH)
        }
    }
}

/*
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

 */