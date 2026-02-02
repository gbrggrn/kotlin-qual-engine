package com.qualengine

import com.qualengine.data.OllamaEnricher
import com.qualengine.logic.ClusterUtils
import com.qualengine.logic.MathUtils
import com.qualengine.logic.InputPipeline
import com.qualengine.model.ExplorerState
import com.qualengine.model.Paragraphs
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import kotlin.concurrent.thread

class ExplorerController {
    // UI variables
    @FXML private lateinit var mapContainer: StackPane
    @FXML private lateinit var mapCanvas: Canvas
    @FXML private lateinit var loadingBox: VBox

    // MouseEvent handling variables
    private val explorerState = ExplorerState()
    private lateinit var renderer: CanvasRenderer
    private lateinit var pipeline: InputPipeline

    // Details side view variables
    @FXML private lateinit var detailsBox: VBox
    @FXML private lateinit var txtContext: TextArea
    private val numberOfDetailsFor = 50

    // Cache points so we can redraw on resize without recalculating PCA
    private var currentAtoms: List<MathUtils.Point2D> = emptyList()

    @FXML
    fun initialize() {
        // Initialize logic classes to handle mouse events
        renderer = CanvasRenderer(mapCanvas)
        pipeline = InputPipeline(explorerState)

        // Make canvas resizable
        mapCanvas.widthProperty().bind(mapContainer.widthProperty())
        mapCanvas.heightProperty().bind(mapContainer.heightProperty())

        // Redraw when window resizes
        mapCanvas.widthProperty().addListener { _ -> drawMap() }
        mapCanvas.heightProperty().addListener { _ -> drawMap() }

        // Bind resize events
        mapCanvas.widthProperty().bind(mapContainer.widthProperty())
        mapCanvas.heightProperty().bind(mapContainer.heightProperty())

        // Mouse listeners
        mapCanvas.widthProperty().addListener { _, _, newW->
            explorerState.width = newW.toDouble(); renderer.render(explorerState)
        }
        mapCanvas.heightProperty().addListener { _, _, newW ->
            explorerState.height = newW.toDouble(); renderer.render(explorerState)
        }

        // Set mouse events
        mapCanvas.setOnMouseMoved { event ->
            pipeline.handleMouseMove(event)
            renderer.render(explorerState)
        }
        mapCanvas.setOnMousePressed { event ->
            pipeline.handleMousePressed(event)
            renderer.render(explorerState)
        }
        mapCanvas.setOnMouseDragged { event ->
            pipeline.handleMouseDragged(event)
            renderer.render(explorerState)
        }
        mapCanvas.setOnMouseReleased { event ->
            pipeline.handleMouseReleased(event)
            renderer.render(explorerState)
            updateSidePanel()
        }
        mapCanvas.setOnMouseClicked { event ->
            pipeline.handleClick(event)
            updateSidePanel()
        }
    }

    private fun renderPoints(points: List<MathUtils.Point2D>, texts: List<String>) {
        explorerState.allPoints = points
        explorerState.moleculeContents = texts
        explorerState.width = mapCanvas.width
        explorerState.height = mapCanvas.height

        renderer.render(explorerState)
    }

    private fun updateSidePanel() {
        detailsBox.children.clear()

        if (explorerState.selectedPoint.isEmpty()){
            val placeholder = Label("Select dots on the map to view details.")
            placeholder.isWrapText = true
            placeholder.style = "-fx-text-fill: #7f8c8d; -fx-font-style: italic;"
            detailsBox.children.add(placeholder)
            return
        }

        val selectedSubset = explorerState.selectedPoint.take(numberOfDetailsFor)

        for (atom in selectedSubset) {
            val content = explorerState.moleculeContents.getOrNull(atom.originalIndex) ?: "Unknown"

            val textArea = TextArea(content)

            textArea.isEditable = false
            textArea.isWrapText = true
            textArea.prefRowCount = 3
            textArea.style = """
                -fx-background-color: white;
                -fx-border-color: #bdc3c7;
                -fx-border-radius: 4;
                -fx-background-radius: 4;
                """.trimIndent()

            detailsBox.children.add(textArea)
        }

        if (explorerState.selectedPoint.size > 50){
            val warning = Label("... and ${explorerState.selectedPoint.size - 50} more items.")
            warning.style = "-fx-text-fill: #e74c3c; -fx-font-weight: bold;"
            detailsBox.children.add(warning)
        }
    }

    @FXML
    fun onRefreshMap() {
        loadingBox.isVisible = true

        thread(start = true) {
            // Fetch and parse data
            val (vectors, texts) = transaction {
                // Fetch all rows
                val rows = Paragraphs.selectAll().toList()

                // Extract Vectors: String? -> List<Double>
                val v = rows.map { row ->
                    // "!!" asserts it's not null.
                    // Split by comma to get the numbers back
                    row[Paragraphs.vector]!!
                        .split(",")
                        .map { it.toDouble() }
                }

                // Extract Text: String
                val t = rows.map { row -> row[Paragraphs.content] }

                Pair(v, t)
            }

            // Handle Empty Database
            if (vectors.isEmpty()) {
                Platform.runLater {
                    loadingBox.isVisible = false
                    // TODO: SHOW NO DATABASE FOUND
                }
                return@thread
            }

            // Perform PCA math
            val rawPoints = MathUtils.performPCA(vectors)
            val normalizedPoints = ClusterUtils.normalizePointsForClustering(rawPoints)

            val clusterResult = ClusterUtils.runDBSCAN(normalizedPoints, epsilon = 0.15, minPoints = 2)
            println("DEBUG: Found ${clusterResult.clusterCenters.size} clusters.")
            val themes = clusterResult.clusterCenters.keys.associateWith { "Analyzing..." }.toMutableMap()

            // Draw
            Platform.runLater {
                loadingBox.isVisible = false
                explorerState.allPoints = normalizedPoints
                explorerState.pointClusterIds = clusterResult.clusterIds
                explorerState.clusterCenters = clusterResult.clusterCenters
                explorerState.clusterThemes = themes
                explorerState.moleculeContents = texts
                explorerState.width = mapCanvas.width
                explorerState.height = mapCanvas.height

                renderer.render(explorerState)
            }

            // AI-labeling! Runs on same thread in the background, updating the UI progressively
            for (clusterId in clusterResult.clusterCenters.keys) {
                // Find the texts of this cluster
                val clusterTexts = normalizedPoints.indices
                    .filter { clusterResult.clusterIds[it] == clusterId }
                    .map { texts[it] }

                // Prompt llm to summarize
                val smartLabel = OllamaEnricher.summarizeCluster(clusterTexts)

                // Live update the UI
                themes[clusterId] = smartLabel
                Platform.runLater {
                    explorerState.clusterThemes = themes
                    renderer.render(explorerState)
                }
            }
        }
    }

    private fun drawMap() {
        if (currentAtoms.isEmpty()) return

        val gc = mapCanvas.graphicsContext2D
        val w = mapCanvas.width
        val h = mapCanvas.height

        // Clear Screen
        gc.fill = Color.web("#2c3e50")
        gc.fillRect(0.0, 0.0, w, h)

        // Find Bounds to normalize to screen size
        val minX = currentAtoms.minOf { it.x }
        val maxX = currentAtoms.maxOf { it.x }
        val minY = currentAtoms.minOf { it.y }
        val maxY = currentAtoms.maxOf { it.y }

        // Draw Dots
        gc.fill = Color.CYAN
        for (p in currentAtoms) {
            // Map Math-Coordinates to Screen-Coordinates
            val screenX = ((p.x - minX) / (maxX - minX)) * (w - 40) + 20
            val screenY = ((p.y - minY) / (maxY - minY)) * (h - 40) + 20

            gc.fillOval(screenX, screenY, 4.0, 4.0)
        }
    }
}