package com.qualengine

import com.qualengine.logic.MathUtils
import com.qualengine.model.Sentences
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.concurrent.thread

class ExplorerController {

    @FXML private lateinit var mapContainer: StackPane
    @FXML private lateinit var mapCanvas: Canvas
    @FXML private lateinit var loadingBox: VBox

    @FXML
    fun initialize() {
        // Make canvas resizable
        mapCanvas.widthProperty().bind(mapContainer.widthProperty())
        mapCanvas.heightProperty().bind(mapContainer.heightProperty())

        // Redraw when window resizes
        mapCanvas.widthProperty().addListener { _ -> drawMap() }
        mapCanvas.heightProperty().addListener { _ -> drawMap() }
    }

    @FXML
    fun onRefreshMap() {
        loadingBox.isVisible = true

        thread(start = true) {
            // 1. Fetch Data
            val (vectors, texts) = transaction {
                val rows = Sentences.selectAll().limit(2000) // Cap for performance
                val v = rows.map { row ->
                    // Parse string "0.1,0.2..." back to List<Double>
                    row[Sentences.vector]!!.split(",").map { it.toDouble() }
                }
                val t = rows.map { it[Sentences.content] }
                Pair(v, t)
            }

            if (vectors.isEmpty()) {
                Platform.runLater { loadingBox.isVisible = false }
                return@thread
            }

            // 2. Run Math (Heavy CPU)
            val points = MathUtils.performPCA(vectors)

            // 3. Draw
            Platform.runLater {
                loadingBox.isVisible = false
                renderPoints(points)
            }
        }
    }

    // Cache points so we can redraw on resize without recalculating PCA
    private var currentPoints: List<MathUtils.Point2D> = emptyList()

    private fun renderPoints(points: List<MathUtils.Point2D>) {
        currentPoints = points
        drawMap()
    }

    private fun drawMap() {
        if (currentPoints.isEmpty()) return

        val gc = mapCanvas.graphicsContext2D
        val w = mapCanvas.width
        val h = mapCanvas.height

        // Clear Screen
        gc.fill = Color.web("#2c3e50")
        gc.fillRect(0.0, 0.0, w, h)

        // Find Bounds to normalize to screen size
        val minX = currentPoints.minOf { it.x }
        val maxX = currentPoints.maxOf { it.x }
        val minY = currentPoints.minOf { it.y }
        val maxY = currentPoints.maxOf { it.y }

        // Draw Dots
        gc.fill = Color.CYAN
        for (p in currentPoints) {
            // Map Math-Coordinates to Screen-Coordinates
            val screenX = ((p.x - minX) / (maxX - minX)) * (w - 40) + 20
            val screenY = ((p.y - minY) / (maxY - minY)) * (h - 40) + 20

            gc.fillOval(screenX, screenY, 4.0, 4.0)
        }
    }
}