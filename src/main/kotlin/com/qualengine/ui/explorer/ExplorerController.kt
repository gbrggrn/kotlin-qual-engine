package com.qualengine.ui.explorer

import com.qualengine.core.AnalysisContext
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment
import com.qualengine.core.analysis.OllamaEnricher
import com.qualengine.core.clustering.ClusterRefiner
import com.qualengine.core.clustering.DBSCAN
import com.qualengine.core.clustering.LayoutEngine
import com.qualengine.core.math.PCA
import com.qualengine.data.db.DatabaseFactory
import com.qualengine.data.model.VectorPoint
import com.qualengine.data.pipeline.InputPipeline
import kotlin.concurrent.thread

class ExplorerController {

    // --- UI INJECTION ---
    @FXML private lateinit var mapContainer: StackPane
    @FXML private lateinit var mapCanvas: Canvas
    @FXML private lateinit var loadingBox: VBox
    @FXML private lateinit var analyzingStatusLabel: Label
    @FXML private lateinit var detailsBox: VBox

    // --- LOGIC ENGINE ---
    // The Renderer is now "Dumb" - it just paints what is in the Context
    private lateinit var renderer: ExplorerRenderer

    // Handles mouse interaction (Selection, Hover)
    // NOTE: You might need to update InputPipeline to use AnalysisContext later
    private lateinit var pipeline: InputPipeline

    // Local cache of raw data (so we don't hit DB on every re-cluster)
    private var cachedPoints: List<VectorPoint> = emptyList()

    // Config
    private val numberOfDetailsFor = 50

    @FXML
    fun initialize() {
        renderer = ExplorerRenderer(mapCanvas)
        // 2. Setup Interaction
        // We pass the global state to the pipeline
        pipeline = InputPipeline(AnalysisContext)

        // 3. Bind Layout Resizing
        mapCanvas.widthProperty().bind(mapContainer.widthProperty())
        mapCanvas.heightProperty().bind(mapContainer.heightProperty())

        // Redraw on Resize
        val resizeListener = { _: Any, _: Any, _: Any ->
            // Update Context with new dimensions
            val currentState = AnalysisContext.state
            // If you track width/height in state, update it here.
            // Otherwise just re-render.
            renderer.render(currentState)
        }
        mapCanvas.widthProperty().addListener(resizeListener)
        mapCanvas.heightProperty().addListener(resizeListener)

        // 4. Bind Mouse Events to Pipeline
        mapCanvas.setOnMouseMoved { e -> pipeline.handleMouseMove(e); repaint() }
        mapCanvas.setOnMousePressed { e -> pipeline.handleMousePressed(e); repaint() }
        mapCanvas.setOnMouseDragged { e -> pipeline.handleMouseDragged(e); repaint() }
        mapCanvas.setOnMouseReleased { e ->
            pipeline.handleMouseReleased(e)
            repaint()
            updateSidePanel() // Update text details on release
        }
        mapCanvas.setOnMouseClicked { e ->
            pipeline.handleClick(e)
            updateSidePanel()
        }

        // 5. Initial Data Load
        loadDataFromDb()
    }

    private fun repaint() {
        renderer.render(AnalysisContext.state)
    }

    // ============================================================================================
    // PHASE 1: INGESTION
    // ============================================================================================

    fun loadDataFromDb() {
        loadingBox.isVisible = true
        analyzingStatusLabel.isVisible = true
        analyzingStatusLabel.text = "Loading from Database..."

        thread(start = true) {
            // 1. Fetch clean objects from Factory
            val points = DatabaseFactory.getAllVectorPoints()

            Platform.runLater {
                if (points.isEmpty()) {
                    analyzingStatusLabel.text = "No data found."
                    loadingBox.isVisible = false
                } else {
                    cachedPoints = points
                    analyzingStatusLabel.text = "Loaded ${points.size} tickets."
                    // Auto-start analysis
                    runAnalysisPipeline()
                }
            }
        }
    }

    // ============================================================================================
    // PHASE 2: THE MATH PIPELINE (Refresh Map)
    // ============================================================================================

    @FXML
    fun onRefreshMap() {
        if (cachedPoints.isEmpty()) {
            loadDataFromDb()
            return
        }
        runAnalysisPipeline()
    }

    private fun runAnalysisPipeline() {
        loadingBox.isVisible = true
        analyzingStatusLabel.isVisible = true
        analyzingStatusLabel.text = "Clustering..."

        thread(start = true) {
            val workingPoints = cachedPoints

            // A. CLUSTERING (DBSCAN)
            val dbscan = DBSCAN(epsilon = 0.23, minPoints = 3)
            val clusterResult = dbscan.runDBSCAN(workingPoints)

            // Apply labels to points
            var clusteredPoints = workingPoints.mapIndexed { index, p ->
                p.copy(clusterId = clusterResult.clusterIds[index])
            }

            // B. REFINEMENT (The Orphanage)
            // Modify IDs in place to adopt orphans
            ClusterRefiner.assignOrphans(clusteredPoints, clusterResult.clusterIds, maxDistance = 0.25)

            // Re-apply refined labels
            clusteredPoints = clusteredPoints.mapIndexed { index, p ->
                p.copy(clusterId = clusterResult.clusterIds[index])
            }

            // C. DIMENSIONALITY REDUCTION (PCA)
            Platform.runLater { analyzingStatusLabel.text = "Projecting 2D Map..." }
            val pcaPoints = PCA.performPCA(clusteredPoints)

            // D. LAYOUT (Archipelago)
            // Calculates gravity and island positions
            val (finalPoints, islandCenters) = LayoutEngine.createArchipelagoLayout(
                pcaPoints,
                clusterResult.clusterIds
            )

            // E. NORMALIZE (Fit to Unit Square 0..1)
            //val viewablePoints = LayoutEngine.normalizeToUnitSquare(finalPoints)

            // --- COMMIT STATE ---
            Platform.runLater {
                // Create initial themes map (Loading state)
                val initialThemes = islandCenters.keys.associateWith { "Processing..." }.toMutableMap()

                val newState = AnalysisContext.state.copy(
                    allPoints = finalPoints,
                    clusterCenters = islandCenters, // Ensure Type match here
                    clusterThemes = initialThemes
                )

                AnalysisContext.update(newState)
                renderer.render(newState)

                loadingBox.isVisible = false

                // Trigger AI in background
                runAiLabeling(finalPoints, islandCenters.keys)
            }
        }
    }

    // ============================================================================================
    // PHASE 3: AI ENRICHMENT
    // ============================================================================================

    private fun runAiLabeling(points: List<VectorPoint>, activeClusterIds: Set<Int>) {
        thread(start = true) {
            val total = activeClusterIds.size
            var processed = 0

            val currentThemes = AnalysisContext.state.clusterThemes.toMutableMap()

            for (clusterId in activeClusterIds) {
                // 1. Gather Text
                val snippets = points.filter { it.clusterId == clusterId }.map { it.metaData }

                // 2. Call AI
                var label = OllamaEnricher.summarizeCluster(snippets)

                // 3. Sanitize
                label = sanitizeLabel(label)

                // 4. Update
                currentThemes[clusterId] = label
                processed++

                Platform.runLater {
                    AnalysisContext.update(AnalysisContext.state.copy(clusterThemes = currentThemes))
                    renderer.render(AnalysisContext.state)
                    analyzingStatusLabel.text = "AI Labeling: $processed / $total"
                }
            }
            Platform.runLater { analyzingStatusLabel.text = "Analysis Complete." }
        }
    }

    private fun sanitizeLabel(raw: String): String {
        var clean = raw.replace(Regex("[^a-zA-Z0-9 ]"), " ").trim()
        val words = clean.split("\\s+".toRegex())
        if (words.size > 3) {
            clean = words.take(2).joinToString(" ")
        }
        return clean.split(" ").joinToString(" ") {
            it.lowercase().replaceFirstChar { c -> c.uppercase() }
        }
    }

    // ============================================================================================
    // UI DETAILS PANEL
    // ============================================================================================

    private fun updateSidePanel() {
        detailsBox.children.clear()

        // Read selection from Global State
        val selectedPoints = AnalysisContext.state.selectedPoints

        if (selectedPoints.isEmpty()){
            val placeholder = Label("Select dots on the map to view details.\n\nHold SHIFT to select multiple.")
            placeholder.isWrapText = true
            placeholder.style = "-fx-text-fill: #95a5a6; -fx-font-style: italic; -fx-font-size: 14px;"
            placeholder.textAlignment = TextAlignment.CENTER
            placeholder.maxWidth = Double.MAX_VALUE
            detailsBox.children.add(placeholder)
            return
        }

        val subset = selectedPoints.take(numberOfDetailsFor)

        for (point in subset) {
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

            // Header
            val header = Label("ID: ${point.id} | Cluster: ${point.clusterId}")
            header.style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 10px;"

            // Content
            val textArea = TextArea(point.metaData) // Access text directly from VectorPoint
            textArea.isEditable = false
            textArea.isWrapText = true
            textArea.prefRowCount = 4
            textArea.style = "-fx-background-color: transparent; -fx-background-insets: 0; -fx-padding: 0;"

            card.children.addAll(header, textArea)
            detailsBox.children.add(card)
        }

        if (selectedPoints.size > numberOfDetailsFor){
            val warning = Label("... and ${selectedPoints.size - numberOfDetailsFor} more items.")
            warning.style = "-fx-text-fill: #7f8c8d; -fx-font-weight: bold; -fx-alignment: center;"
            warning.maxWidth = Double.MAX_VALUE
            detailsBox.children.add(warning)
        }
    }
}