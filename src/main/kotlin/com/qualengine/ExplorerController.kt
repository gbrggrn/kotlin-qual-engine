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
    @FXML private lateinit var analyzingStatusLabel: Label

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
        explorerState.pointContents = texts
        explorerState.width = mapCanvas.width
        explorerState.height = mapCanvas.height

        renderer.render(explorerState)
    }

    private fun updateSidePanel() {
        detailsBox.children.clear()

        if (explorerState.selectedPoint.isEmpty()){
            val placeholder = Label("Select dots on the map to view details.\n\nHold SHIFT to select multiple.")
            placeholder.isWrapText = true
            placeholder.style = "-fx-text-fill: #95a5a6; -fx-font-style: italic; -fx-font-size: 14px;"
            placeholder.textAlignment = javafx.scene.text.TextAlignment.CENTER
            placeholder.maxWidth = Double.MAX_VALUE
            detailsBox.children.add(placeholder)
            return
        }

        val selectedSubset = explorerState.selectedPoint.take(numberOfDetailsFor)

        for (point in selectedSubset) {
            val content = explorerState.pointContents.getOrElse(point.originalIndex) { "Unknown Data "}

            // Create card
            val card = VBox()
            card.style = """
                -fx-background-color: white;
                -fx-border-color: #ecf0f1;
                -fx-border-width: 1;
                -fx-border-radius: 6;
                -fx-background-radius: 6;
                -fx-padding: 8;
                -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.1), 3, 0, 0, 1);
            """.trimIndent()

            // ID header
            val header = Label("ID: ${point.originalIndex}")
            header.style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 10px;"

            // Content
            val textArea = TextArea(content)
            textArea.isEditable = false
            textArea.isWrapText = true
            textArea.prefRowCount = 4
            textArea.style = """
                -fx-background-color: transparent;
                -fx-background-insets: 0;
                -fx-padding: 0;
                """.trimIndent()

            card.children.addAll(header, textArea)
            detailsBox.children.add(card)
        }

        // Overflow warning
        if (explorerState.selectedPoint.size > numberOfDetailsFor){
            val hiddenCount = explorerState.selectedPoint.size - numberOfDetailsFor
            val warning = Label("... and ${explorerState.selectedPoint.size - numberOfDetailsFor} more items.")
            warning.style = "-fx-text-fill: #7f8c8d; -fx-font-weight: bold; -fx-alignment: center;"
            warning.maxWidth = Double.MAX_VALUE
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
                explorerState.pointContents = texts
                explorerState.width = mapCanvas.width
                explorerState.height = mapCanvas.height

                renderer.render(explorerState)
            }

            val totalClusters = clusterResult.clusterCenters.size
            var processed = 0

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
                    analyzingStatusLabel.text = "Identifying theme ${processed + 1} / $totalClusters"
                    renderer.render(explorerState)
                }
                processed++
            }
            Platform.runLater { analyzingStatusLabel.text = "Theme analysis complete." }
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