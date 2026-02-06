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
import com.qualengine.core.math.PCA
import com.qualengine.data.db.model.Paragraphs
import com.qualengine.data.model.AppState
import com.qualengine.data.model.ClusterResult
import com.qualengine.data.model.VectorPoint
import com.qualengine.data.model.VirtualPoint
import com.qualengine.data.pipeline.InputPipeline
import javafx.scene.control.Button
import javafx.scene.control.TextField
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.math.sqrt

enum class ViewMode { GLOBAL, DOCUMENT, SEARCH, SELECTION}

class ExplorerController {
    // --- Config
    private var currentMode = ViewMode.GLOBAL
    private var currentFilterId: String? = null
    private val numberOfDetailsFor = 50

    // --- Dependencies
    private val OllamaClient = DependencyRegistry.ollamaClient
    private val DatabaseFactory = DependencyRegistry.databaseFactory
    private val OllamaEnricher = DependencyRegistry.ollamaEnricher
    private val ClusterRefiner = DependencyRegistry.clusterRefiner
    private val LayoutEngine = DependencyRegistry.layoutEngine
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
                ViewMode.GLOBAL -> DatabaseFactory.getDocumentPoints()
                ViewMode.DOCUMENT -> DatabaseFactory.getParagraphPoints().filter { it.parentId == filterId}
                ViewMode.SELECTION -> AnalysisContext.state.selectedPoints.toList()
                ViewMode.SEARCH -> {
                    val queryVec = OllamaClient.getVector(filterId ?: "", 512)
                    DatabaseFactory.searchParagraphs(queryVec)
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
            val points = DatabaseFactory.getDocumentPoints()

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
        loadingBox.isVisible = true
        analyzingStatusLabel.isVisible = true
        analyzingStatusLabel.text = "Clustering..."

        thread(start = true) {
            val workingPoints = cachedPoints

            // --- CLUSTERING (Peak-Seeking Auto-Tune) ---
            val pointCount = workingPoints.size
            val targetIslands = (pointCount / 12).coerceIn(2, 8)

            var epsilon = 0.05
            var bestClusterIds = IntArray(pointCount) { -1 }
            var maxIslandsFound = 0
            var bestEpsilon = epsilon

            while (epsilon <= 0.40) { // Ceiling to prevent total document merging
                val dbscan = DBSCAN(epsilon = epsilon, minPoints = 3)
                val currentIds = dbscan.runDBSCAN(workingPoints).clusterIds
                val currentCount = currentIds.filter { it != -1 }.distinct().size

                // If this run found MORE (or equal) islands than before, save it as the 'Best'
                if (currentCount >= maxIslandsFound && currentCount > 0) {
                    maxIslandsFound = currentCount
                    bestClusterIds = currentIds.copyOf()
                    bestEpsilon = epsilon
                }

                // If we hit the target, we are done
                if (currentCount >= targetIslands) break

                // If we were at a peak and now clusters are disappearing, stop and keep the peak
                if (currentCount < maxIslandsFound && maxIslandsFound >= 2) {
                    println("Peak detected at ${epsilon - 0.02}. Reverting to $maxIslandsFound islands.")
                    break
                }

                epsilon += 0.02
            }

            // ALWAYS use the best state we found, not the 'current' state
            val finalClusterIds = bestClusterIds
            println("--- Auto-Tune Results ---")
            println("Best Epsilon: $bestEpsilon | Islands: $maxIslandsFound")

            var clusteredPoints = workingPoints.mapIndexed { index, p ->
                p.copy(clusterId = finalClusterIds[index])
            }

            // --- REFINEMENT (The Orphanage) ---
            // Only run this if we actually have clusters to adopt into!
            if (maxIslandsFound > 0) {
                // Orphan adoption now works because it sees the islands from the 'Best' run
                ClusterRefiner.assignOrphans(clusteredPoints, finalClusterIds, maxDistance = bestEpsilon * 1.2)

                // Re-apply IDs just in case the refiner modified the array in-place
                clusteredPoints = clusteredPoints.mapIndexed { index, p ->
                    p.copy(clusterId = finalClusterIds[index])
                }
            }

            // --- DIMENSIONALITY REDUCTION (PCA) ---
            Platform.runLater { analyzingStatusLabel.text = "Projecting 2D Map..." }
            val pcaPoints = PCA.performPCA(clusteredPoints)

            // --- LAYOUT ---
            val (finalPoints, finalAnchors) = if (AnalysisContext.state.currentLayer == 3) {
                // LAYER 3: Standard Semantic View
                val validClusters = finalClusterIds.filter { it != -1 }.distinct()
                val rawAnchors = mutableMapOf<Int, VirtualPoint>()

                validClusters.forEach { id ->
                    val clusterPoints = pcaPoints.filter { it.clusterId == id }
                    val avgX = clusterPoints.map { it.projectedX }.average()
                    val avgY = clusterPoints.map { it.projectedY }.average()
                    val radius = clusterPoints.maxOfOrNull { p ->
                        sqrt((p.projectedX - avgX).pow(2) + (p.projectedY - avgY).pow(2))
                    } ?: 0.05
                    rawAnchors[id] = VirtualPoint(id, avgX, avgY, radius)
                }

                // Normalize so the global document map fills the screen
                LayoutEngine.normalizeEverything(pcaPoints, rawAnchors)
            } else {
                // LAYER 2: Forced Island Layout (The Archipelago)
                LayoutEngine.createGalaxyLayout(pcaPoints, finalClusterIds)
            }

            // --- COMMIT ---
            Platform.runLater {
                val initialThemes = finalAnchors.keys.associateWith { "Processing..." }.toMutableMap()

                val newState = AnalysisContext.state.copy(
                    allPoints = finalPoints,
                    clusterCenters = finalAnchors,
                    clusterThemes = initialThemes
                )

                AnalysisContext.update(newState)
                renderer.render(newState)

                loadingBox.isVisible = false
                analyzingStatusLabel.isVisible = false

                println("\n====== CLUSTER CONTENTS INSPECTOR ======")

                // Group points by their cluster ID
                val clusters = clusteredPoints.groupBy { it.clusterId }

                clusters.forEach { (id, points) ->
                    if (id == -1) return@forEach // Skip noise

                    println("\n--- ISLAND $id (${points.size} chunks) ---")

                    // Grab the first 3 chunks from this island to sample the theme
                    points.take(3).forEach { p ->
                        // We assume 'p' has access to the raw text or ID.
                        // If your AnalysisPoint only has ID, perform a quick DB lookup here.

                        // Pseudo-code for DB lookup if needed:
                        val text = transaction {
                            Paragraphs.select { Paragraphs.id eq p.id }
                                .single()[Paragraphs.content]
                        }

                        println("   Example: \"${text.take(60).replace("\n", " ")}...\"")
                    }
                }
                println("\n========================================")

                //runAiLabeling(finalPoints, finalAnchors.keys)
            }
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
                3 -> DatabaseFactory.getDocumentPoints()
                2 -> DatabaseFactory.getParagraphsForDocs(parentIds)
                1 -> DatabaseFactory.getSentencesForParagraphs(parentIds)
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