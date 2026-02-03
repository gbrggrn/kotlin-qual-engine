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
        analyzingStatusLabel.isVisible = true
        analyzingStatusLabel.text = "Fetching data..."

        thread(start = true) {
            // --- 1. DATA INGESTION ---
            val (vectors, texts) = transaction {
                val rows = Paragraphs.selectAll().toList()
                val v = rows.map { row -> row[Paragraphs.vector]!!.split(",").map { it.toDouble() } }
                val t = rows.map { row -> row[Paragraphs.content] }
                Pair(v, t)
            }

            if (vectors.isEmpty()) {
                Platform.runLater {
                    loadingBox.isVisible = false
                    // TODO: SHOW NO DATABASE FOUND
                }
                return@thread
            }

            // --- 2. THE MATHEMATICS ---

            // Semantic clustering (DBSCAN 384D)
            Platform.runLater { analyzingStatusLabel.text = "Clustering & Architecting..." }
            val clusterResult = ClusterUtils.runDBSCAN(vectors, epsilon = 0.23, minPoints = 3)

            // Try to assign outliers with "looser" rules
            ClusterUtils.assignOrphansToNearestCluster(vectors, clusterResult.clusterIds, maxDistance = 0.25)

            // Visual raw materials (PCA 2D)
            val rawPoints = MathUtils.performPCA(vectors)
            val normalizedPoints = ClusterUtils.normalizePointsForClustering(rawPoints)

            // Archipelago transformation
            val (archipelagoPoints, islandCenters) = ClusterUtils.createArchipelagoLayout(
                normalizedPoints,
                clusterResult.clusterIds,
                0
            )

            // --- 3. CONSTRUCT THE STATE ---
            val themes = islandCenters.keys.associateWith { "Analyzing..." }.toMutableMap()

            Platform.runLater {
                loadingBox.isVisible = false

                val finalPoints = archipelagoPoints.mapIndexed { index, p ->
                    MathUtils.Point2D(x = p.x, y = p.y, originalIndex = index)
                }

                // ASSIGN the new lists (Parallel Arrays Pattern)
                explorerState.allPoints = finalPoints
                explorerState.pointClusterIds = clusterResult.clusterIds // Colors live here
                explorerState.pointContents = texts // Text lives here

                // Map Metadata
                explorerState.clusterCenters = islandCenters
                explorerState.clusterThemes = themes
                explorerState.width = mapCanvas.width
                explorerState.height = mapCanvas.height

                // Render
                renderer.render(explorerState)
            }

            // --- 4. THE AI ENRICHMENT LOOP ---
            val totalClusters = archipelagoPoints.size
            var processed = 0

            for (clusterId in islandCenters.keys) {
                // Find text snippets belonging to this cluster
                val clusterSnippets = vectors.indices
                    .filter { i -> clusterResult.clusterIds[i] == clusterId }
                    .map { i -> texts[i] }

                // Summarize
                var smartLabel = OllamaEnricher.summarizeCluster(clusterSnippets)

                // Sanitize
                // 1. Remove "1.", quotes, punctuation
                smartLabel = smartLabel.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()

                // 2. The Guillotine: Take first 3 words max
                val words = smartLabel.split("\\s+".toRegex())
                if (words.size > 3) {
                    // Fallback: Just take the first 2 meaningful words
                    smartLabel = words.take(2).joinToString(" ")
                }

                // 3. Capitalize Title Case (Optional polish)
                smartLabel = smartLabel.split(" ").joinToString(" ") {
                    it.lowercase().replaceFirstChar { char -> char.uppercase() }
                }

                // Update Map
                themes[clusterId] = smartLabel
                processed++

                Platform.runLater {
                    explorerState.clusterThemes = themes
                    analyzingStatusLabel.text = "Identified Theme $processed / $totalClusters"
                    renderer.render(explorerState)
                }
            }

            Platform.runLater {
                analyzingStatusLabel.text = "Analysis Complete."
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