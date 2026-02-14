package com.qualengine.ui.explorer

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.AppState
import com.qualengine.data.model.VectorPoint
import javafx.geometry.Point2D
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

    private val geometryMath = DependencyRegistry.geometryMath

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
        val shapePoints = state.clusterShapes

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
        val showPoints = zoom > 0.4
        val showDetails = zoom > 1.5
        val isGalaxyView = zoom < 0.8
        val fadeClusterLabels = zoom > 2.0 // Hide big theme labels when deep in the weeds

        // ==================================================
        // THE TERRITORIES (Hulls)
        // ==================================================
        if (isGalaxyView){
            // === GALAXY VIEW ===

            // 1. Draw The "Blob" (Super-Hull)
            drawSuperHull(graphics, state, state.coreClusterIds, Color.rgb(100, 150, 255, 0.2))

            // 2. Draw The Outliers (Normal Hulls, but maybe simpler?)
            // For now, let's just draw their normal hulls to show they are distinct
            for (set in state.outlierClusterIds) {
                drawSuperHull(graphics, state, set, Color.rgb(100, 150, 255, 0.2))
            }
        }

        if (showHulls) {
            for ((id, shapePoints) in state.clusterShapes) {
                if (shapePoints.isEmpty()) continue

                // --- FALLBACK FOR TINY CLUSTERS ---
                // If hull failed or too small, draw a simple blob
                if (shapePoints.size < 3) {
                    val center = state.clusterCenters[id] ?: continue
                    val screenPos = coordinateMapper.worldToScreen(center.x, center.y, camera)
                    val radius = center.radius * camera.zoom * 0.5 // Smaller visual radius

                    graphics.fill = getClusterColor(id, 0.4)
                    graphics.fillOval(screenPos.x - radius, screenPos.y - radius, radius * 2, radius * 2)
                    continue
                }

                // --- DRAW ORGANIC HULL ---
                graphics.beginPath()

                val first = coordinateMapper.worldToScreen(shapePoints[0].x, shapePoints[0].y, camera)
                graphics.moveTo(first.x, first.y)

                for (i in 1 until shapePoints.size) {
                    val p = coordinateMapper.worldToScreen(shapePoints[i].x, shapePoints[i].y, camera)
                    graphics.lineTo(p.x, p.y)
                }

                // Connects the last point back to the first.
                graphics.closePath()

                // VISUAL TWEAKS
                // Increase Opacity (0.15 -> 0.3) so it looks like a distinct region
                graphics.fill = getClusterColor(id, 0.3)
                graphics.fill()

                graphics.stroke = getClusterColor(id, 0.8)
                graphics.lineWidth = 2.0
                graphics.stroke()
            }
        }

        // =========================
        // THE CONNECTIONS (Lines between clusters)
        // =========================
        val hoveredId = state.hoveredPoint?.clusterId

        if (hoveredId != null) {
            val relatedIds = state.clusterConnections[hoveredId] ?: emptyList()

            // Draw lines to all related clusters
            graphics.stroke = Color.rgb(255, 255, 255, 0.4) // Faint white
            graphics.lineWidth = 1.5

            val start = state.clusterCenters[hoveredId]

            if (start != null) {
                val startScreen = coordinateMapper.worldToScreen(start.x, start.y, camera)

                for (friendId in relatedIds) {
                    val end = state.clusterCenters[friendId] ?: continue
                    val endScreen = coordinateMapper.worldToScreen(end.x, end.y, camera)

                    // Draw a Quadratic Curve for elegance (straight lines look stiff)
                    graphics.beginPath()
                    graphics.moveTo(startScreen.x, startScreen.y)

                    // Control point: Midpoint but offset slightly (visual flair)
                    val cx = (startScreen.x + endScreen.x) / 2
                    val cy = (startScreen.y + endScreen.y) / 2 - 20.0 // slight arch

                    graphics.quadraticCurveTo(cx, cy, endScreen.x, endScreen.y)
                    graphics.stroke()
                }
            }
        }

        // ==================================================
        // THE POPULATION (Points)
        // ==================================================
        if (showPoints) {
            for (point in state.allPoints) {
                val screenPos = coordinateMapper.worldToScreen(point.projectedX, point.projectedY, camera)

                // Cull off-screen points
                if (screenPos.x < -50 || screenPos.x > width + 50 ||
                    screenPos.y < -50 || screenPos.y > height + 50) {
                    continue
                }

                // Size scales slightly with zoom, but clamps to avoid becoming huge
                val pointSize = (2.0 * (zoom * 2.0)).coerceIn(2.0, 12.0)
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
                    graphics.font = Font.font("Arial", 12.0)
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

                val label = state.clusterLabels[id] ?: "Group $id"

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

    private fun drawSuperHull(g: javafx.scene.canvas.GraphicsContext, state: AppState, clusterIds: Set<Int>, color: Color) {
        if (clusterIds.isEmpty()) return

        // 1. Collect ALL points from ALL core clusters
        val allCorePoints = state.allPoints.filter { it.clusterId in clusterIds }
        if (allCorePoints.isEmpty()) return

        // 2. Calculate the Convex Hull of this mega-group
        // (You might need to expose your GeometryUtils.computeConvexHull to the renderer
        //  or pre-calculate this in the Controller if performance lags)
        val rawPoints = allCorePoints.map { Point2D(it.projectedX, it.projectedY) }
        val hull = geometryMath.computeConvexHull(rawPoints)
        val smoothHull = geometryMath.smoothPolygon(hull, iterations = 6)

        // 3. Draw
        g.beginPath()
        if (smoothHull.isNotEmpty()) {
            val start = coordinateMapper.worldToScreen(smoothHull[0].x, smoothHull[0].y, state.camera)
            g.moveTo(start.x, start.y)
            for (p in smoothHull.drop(1)) {
                val sc = coordinateMapper.worldToScreen(p.x, p.y, state.camera)
                g.lineTo(sc.x, sc.y)
            }
            g.closePath()
        }

        g.fill = color
        g.fill()

        g.stroke = color.brighter()
        g.lineWidth = 3.0
        g.stroke()
    }
}