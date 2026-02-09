package com.qualengine.ui.explorer

import com.qualengine.app.DependencyRegistry
import com.qualengine.core.AnalysisContext
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.scene.canvas.Canvas
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment
import com.qualengine.core.clustering.DBSCAN
import com.qualengine.core.clustering.LayoutEngine
import com.qualengine.data.db.model.Paragraphs
import com.qualengine.data.model.AppState
import com.qualengine.data.model.VectorPoint
import com.qualengine.data.model.VirtualPoint
import com.qualengine.data.pipeline.InputPipeline
import javafx.scene.control.Button
import javafx.scene.control.TextField
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.Random
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

enum class ViewMode { GLOBAL, DOCUMENT, SEARCH, SELECTION}

class ExplorerController {
    // --- Config
    private var currentMode = ViewMode.GLOBAL
    private var currentFilterId: String? = null
    private val numberOfDetailsFor = 50

    // --- Dependencies
    private val ollamaClient = DependencyRegistry.ollamaClient
    private val databaseFactory = DependencyRegistry.databaseFactory
    private val ollamaEnricher = DependencyRegistry.ollamaEnricher
    private val clusterRefiner = DependencyRegistry.clusterRefiner
    private val layoutEngine = DependencyRegistry.layoutEngine
    private lateinit var renderer: ExplorerRenderer
    private lateinit var pipeline: InputPipeline

    // --- Local cache of raw data
    private var cachedPoints: List<VectorPoint> = emptyList()

    // --- UI Injection
    @FXML private lateinit var mapContainer: StackPane
    @FXML private lateinit var mapCanvas: Canvas
    @FXML private lateinit var loadingBox: VBox
    @FXML private lateinit var analyzingStatusLabel: Label
    @FXML private lateinit var detailsBox: VBox
    @FXML private lateinit var searchField: TextField
    @FXML private lateinit var exploreButton: Button
    @FXML private lateinit var backButton: Button

    @FXML
    fun initialize() {
        DependencyRegistry.explorerController = this

        renderer = DependencyRegistry.createRenderer(mapCanvas)
        pipeline = DependencyRegistry.inputPipeline

        // --- Bind layout resizing
        mapCanvas.widthProperty().bind(mapContainer.widthProperty())
        mapCanvas.heightProperty().bind(mapContainer.heightProperty())

        // Redraw on resize
        val resizeListener = { _: Any, _: Any, _: Any ->
            // Get canvas size/height
            val w = mapCanvas.width
            val h = mapCanvas.height

            // Update canvas size/height in state
            val currentState = AnalysisContext.state
            AnalysisContext.update(currentState.copy(
                width = w,
                height = h
            ))

            // Re-render
            renderer.render(AnalysisContext.state)
        }
        mapCanvas.widthProperty().addListener(resizeListener)
        mapCanvas.heightProperty().addListener(resizeListener)

        // --- Bind MouseEvents to InputPipeline
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

        // --- Initial Data Load
        loadDataFromDb()
    }

    fun switchView(mode: ViewMode, filterId: String? = null) {
        this.currentMode = mode
        this.currentFilterId = filterId

        thread(start = true) {
            val points = when(mode) {
                ViewMode.GLOBAL -> databaseFactory.getDocumentPoints()
                ViewMode.DOCUMENT -> databaseFactory.getParagraphPoints().filter { it.parentId == filterId}
                ViewMode.SELECTION -> AnalysisContext.state.selectedPoints.toList()
                ViewMode.SEARCH -> {
                    val queryVec = ollamaClient.getVector(filterId ?: "", 512)
                    databaseFactory.searchParagraphs(queryVec)
                }
            }

            Platform.runLater {
                cachedPoints = points
                runAnalysisPipeline()
            }
        }
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
        analyzingStatusLabel.text = "Loading thematic layers..."

        thread(start = true) {
            // 1. Fetch clean objects from Factory
            val points = databaseFactory.getDocumentPoints()

            Platform.runLater {
                cachedPoints = points
                AnalysisContext.update(AnalysisContext.state.copy(
                    allPoints = points,
                    currentLayer = 3,
                    navigationStack = emptyList())
                )
                runAnalysisPipeline()
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
        uiHandshake()

        thread(start = true) {
            val workingPoints = cachedPoints

            // === DISCOVERY ===
            // DBSCAN clustering to find the big themes
            val (bestClusterIds, bestEpsilon) = clusterOnAutoEpsilon(workingPoints)
            // Recursive splitting to split the large clusters
            val fracturedClusterIds = clusterRefiner.splitLargeClusters(workingPoints, bestClusterIds)
            // Assignment of remaining orphans to clusters
            val tempPoints = workingPoints.mapIndexed { i, p -> p.copy(clusterId = fracturedClusterIds[i]) }
            clusterRefiner.assignOrphans(tempPoints, fracturedClusterIds, maxDistance = bestEpsilon * 1.2)
            // The final clusterIds
            val finalClusterIds = fracturedClusterIds

            // === LAYOUT ====
            Platform.runLater { analyzingStatusLabel.text = "Assembling layout..." }
            // Compute the layout (returns Map<Int, VirtualPoint>)
            val clusterLayout = layoutEngine.computeLayout(workingPoints, finalClusterIds)
            // Normalize to viewport coordinates
            val normalizedLayout = normalizeLayout(clusterLayout)
            // Position points around normalized cluster centerpoints
            val finalPoints = positionClusters(workingPoints, finalClusterIds, normalizedLayout)

            // === COMMIT TO STATE ===
            Platform.runLater {
                // Initialize labels as placeholders. User can trigger AI Labeling later.
                val initialThemes = clusterLayout.keys.associateWith { "Processing..." }

                val newState = AnalysisContext.state.copy(
                    allPoints = finalPoints,
                    clusterCenters = normalizedLayout,
                    clusterThemes = initialThemes
                )

                // Update Global State
                AnalysisContext.update(newState)

                // Trigger Visuals
                renderer.render(newState)

                // Cleanup UI
                loadingBox.isVisible = false
                analyzingStatusLabel.isVisible = false

                // Log Results
                println("--- Analysis Complete ---")
                println("Clusters Created: ${clusterLayout.size}")
                println("Points Processed: ${finalPoints.size}")
            }

        }
    }

    private fun positionClusters(points: List<VectorPoint>, ids: IntArray, clusterLayout: Map<Int, VirtualPoint>): List<VectorPoint> {
        val rng = java.util.Random()

        val maxJitter = 0.04

        return points.mapIndexed { index, p ->
            val cid = ids[index]

            val center = clusterLayout[cid] ?: VirtualPoint(-1, 0.5, 0.5, 0.0)

            // Orbit Jitter: Scatter points around their star
            val angle = rng.nextDouble() * 2 * Math.PI
            val radius = rng.nextDouble() * maxJitter

            val localX = center.x + cos(angle) * radius
            val localY = center.y + sin(angle) * radius

            p.copy(clusterId = cid, projectedX = localX, projectedY = localY)
        }

    }
    private fun uiHandshake() {
        Platform.runLater {
            loadingBox.isVisible = true
            analyzingStatusLabel.isVisible = true
            analyzingStatusLabel.text = "Clustering..."
        }
    }

    private fun clusterOnAutoEpsilon(workingPoints: List<VectorPoint>, pointCount: Int = workingPoints.size): Pair<IntArray, Double> {
        val targetClusters = (pointCount / 12).coerceIn(2, 8)
        var bestClusterIds = IntArray(pointCount) { -1 }
        var maxClustersFound = 0
        var bestEpsilon = 0.05
        var epsilon = 0.05

        while (epsilon <= 0.40) {
            val dbscan = DBSCAN(epsilon = epsilon, minPoints = 3)
            val currentIds = dbscan.runDBSCAN(workingPoints).clusterIds
            val currentCount = currentIds.filter { it != -1 }.distinct().size

            if (currentCount >= maxClustersFound && currentCount > 0) {
                maxClustersFound = currentCount
                bestClusterIds = currentIds.copyOf()
                bestEpsilon = epsilon
            }

            if (currentCount >= targetClusters) break
            if (currentCount < maxClustersFound && maxClustersFound >= 2) break // Peak detected

            epsilon += 0.02
        }

        return Pair(bestClusterIds, bestEpsilon)
    }

    private fun normalizeLayout(rawLayout: Map<Int, VirtualPoint>): Map<Int, VirtualPoint> {
        if (rawLayout.isEmpty()) return rawLayout

        // 1. Find the boundaries of the physics universe
        val minX = rawLayout.values.minOf { it.x }
        val maxX = rawLayout.values.maxOf { it.x }
        val minY = rawLayout.values.minOf { it.y }
        val maxY = rawLayout.values.maxOf { it.y }

        // Avoid divide-by-zero if there's only 1 cluster
        val width = (maxX - minX).coerceAtLeast(1.0)
        val height = (maxY - minY).coerceAtLeast(1.0)

        // 2. Padding (Keep clusters 5% away from the edge)
        val padding = 0.05

        // 3. Create a new map with normalized coordinates
        return rawLayout.mapValues { (_, vp) ->
            val normX = padding + ((vp.x - minX) / width) * (1.0 - padding * 2)
            val normY = padding + ((vp.y - minY) / height) * (1.0 - padding * 2)

            val visualRadius = 0.05

            // Return a COPY with new coordinates
            vp.copy(x = normX, y = normY, radius = visualRadius)
        }
    }


    // ============================================================================================
    // PHASE 3: AI ENRICHMENT TODO: Rebuild this so that the user can choose WHAT to label - auto-labeling is too expensive.
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
                var label = ollamaEnricher.summarizeCluster(snippets)

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

    // ==============================================
    // SEARCH
    // ==============================================
    @FXML
    fun onSearchAction() {
        val query = searchField.text
        if (query.isNullOrBlank())
            // Return to global view state
            switchView(ViewMode.GLOBAL)
        else
            // Switch to search view state and trigger search by adding query
            switchView(ViewMode.SEARCH, query)
    }

    // ===============================================
    // HIERARCHICAL NAVIGATION
    // ===============================================
    @FXML
    fun onExplore(){
        val current = AnalysisContext.state
        if (current.selectedPoints.isEmpty())
            return

        // --- DOWN: identify target layer
        val firstSelected = current.selectedPoints.first()
        val nextLayer = firstSelected.layer - 1
        // Return if already at "sentence" level
        if (nextLayer < 1)
            return

        val parentIds = current.selectedPoints.map { it.id }

        thread(start = true) {
            // Fetch the data we've selected for view
            val childPoints = when (nextLayer) {
                3 -> databaseFactory.getDocumentPoints()
                2 -> databaseFactory.getParagraphsForDocs(parentIds)
                1 -> databaseFactory.getSentencesForParagraphs(parentIds)
                else -> emptyList()
            }

            Platform.runLater {
                // Save current to history
                val historyEntry = AppState.NavigationState(cachedPoints, current.currentLayer)

                // Update state and local cache
                cachedPoints = childPoints
                AnalysisContext.update( current.copy(
                    selectedPoints = emptySet(),
                    currentLayer = nextLayer,
                    navigationStack = current.navigationStack + historyEntry,
                ))
                updateNavButtons()
                runAnalysisPipeline()
            }
        }
    }

    @FXML
    fun onBack() {
        val current = AnalysisContext.state
        if (current.navigationStack.isEmpty())
            return

        val previous = current.navigationStack.last()

        cachedPoints = previous.points

        AnalysisContext.update( current.copy(
            selectedPoints = emptySet(),
            currentLayer = previous.layer,
            navigationStack = current.navigationStack.dropLast(1),
        ))

        updateNavButtons()
        runAnalysisPipeline()
    }

    fun updateNavButtons() {
        val current = AnalysisContext.state
        backButton.isDisable = current.navigationStack.isEmpty()
        exploreButton.isDisable = current.selectedPoints.isEmpty() || current.currentLayer <= 1
    }
}