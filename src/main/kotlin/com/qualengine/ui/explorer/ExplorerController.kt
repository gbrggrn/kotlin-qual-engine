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
import com.qualengine.data.model.AppState
import com.qualengine.data.model.VectorPoint
import com.qualengine.data.model.VirtualPoint
import com.qualengine.data.pipeline.InputPipeline
import javafx.geometry.Point2D
import javafx.scene.control.Button
import javafx.scene.control.TextField
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin

enum class ViewMode { GLOBAL, SEARCH, SELECTION}

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
    private val geometryMath = DependencyRegistry.geometryMath
    private val labelGenerator = DependencyRegistry.labelGenerator
    private lateinit var renderer: ExplorerRenderer
    private lateinit var pipeline: InputPipeline
    private lateinit var coordinateMapper: CoordinateMapper

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

        pipeline = DependencyRegistry.createInputPipeline(mapCanvas)
        coordinateMapper = DependencyRegistry.createCoordinateMapper(mapCanvas)
        renderer = DependencyRegistry.createRenderer(mapCanvas, coordinateMapper)

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
        mapCanvas.setOnMouseMoved { e -> pipeline.handleMouseMove(e); requestRender() }
        mapCanvas.setOnMousePressed { e -> pipeline.handleMousePressed(e); requestRender() }
        mapCanvas.setOnMouseDragged { e -> pipeline.handleMouseDragged(e); requestRender() }
        mapCanvas.setOnScroll { e -> pipeline.handleScroll(e); requestRender() }
        mapCanvas.setOnMouseReleased { e ->
            pipeline.handleMouseReleased(e)
            requestRender()
            updateSidePanel() // Update text details on release
        }
        mapCanvas.setOnMouseClicked { e ->
            pipeline.handleClick(e)
            updateSidePanel()
        }

        // --- Initial Data Load
        loadDataFromDb()
    }

    fun requestRender() {
        renderer.render(AnalysisContext.state)
    }

    fun switchView(mode: ViewMode, filterId: String? = null) {
        this.currentMode = mode
        this.currentFilterId = filterId

        thread(start = true) {
            val points = when(mode) {
                ViewMode.GLOBAL -> {
                    databaseFactory.getParagraphPoints()
                }
                ViewMode.SELECTION -> {
                    AnalysisContext.state.selectedPoints.toList()
                }
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

    private fun calculateFitToScreenCamera(
        points: Map<Int, VirtualPoint>,
        canvasWidth: Double,
        canvasHeight: Double,
    ): AppState.Camera {
        if (points.isEmpty())
            return AppState.Camera(0.0, 0.0, 1.0)

        // Define the bounding box of the physical world
        val minX = points.values.minOf { it.x - it.radius }
        val maxX = points.values.maxOf { it.x + it.radius }
        val minY = points.values.minOf { it.y - it.radius }
        val maxY = points.values.maxOf { it.y + it.radius }

        // Define center of the data
        val dataCenterX = (minX + maxX) / 2.0
        val dataCenterY = (minY + maxY) / 2.0

        // Calculate dimensions of the data (the width & height of the physical world)
        val dataWidth = maxX - minX
        val dataHeight = maxY - minY

        // Calculate zoom to fit (+ 10% padding)
        val padding = 1.1
        val zoomX = canvasWidth / (dataWidth * padding)
        val zoomY = canvasHeight / (dataHeight * padding)

        // Pick the smaller zoom so that everything fits
        val finalZoom = minOf(zoomX, zoomY)

        return AppState.Camera(
            x = dataCenterX,
            y = dataCenterY,
            zoom = finalZoom
        )
    }

    // ============================================================================================
    // PHASE 1: INGESTION
    // ============================================================================================

    fun loadDataFromDb() {
        loadingBox.isVisible = true
        analyzingStatusLabel.isVisible = true
        analyzingStatusLabel.text = "Loading thematic galaxy..."

        thread(start = true) {
            // 1. Fetch clean objects from Factory
            val points = databaseFactory.getParagraphPoints()

            Platform.runLater {
                cachedPoints = points
                AnalysisContext.update(AnalysisContext.state.copy(
                    allPoints = points
                ))
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
            // Position points around normalized cluster centerpoints
            val finalPoints = positionClusters(workingPoints, finalClusterIds, clusterLayout.positions)
            // Generate organic "hulls" that wrap around the clusters
            val clusterShapes = createClusterHulls(finalPoints, finalClusterIds, clusterLayout.positions)

            // === COMMIT TO STATE ===
            Platform.runLater {
                // Initialize labels as placeholders. User can trigger AI Labeling later.
                val initialThemes = clusterLayout.positions.keys.associateWith { "Processing..." }

                val initialCamera = calculateFitToScreenCamera(
                    clusterLayout.positions,
                    mapCanvas.width,
                    mapCanvas.height
                )

                val newState = AnalysisContext.state.copy(
                    allPoints = finalPoints,
                    clusterCenters = clusterLayout.positions,
                    clusterThemes = initialThemes,
                    clusterShapes = clusterShapes,
                    camera = initialCamera,
                    coreClusterIds = clusterLayout.coreIds,
                    outlierClusterIds = clusterLayout.outlierIds,
                    clusterConnections = clusterLayout.clusterConnections
                )

                // Update Global State
                AnalysisContext.update(newState)

                // Trigger Visuals
                requestRender()

                // Cleanup UI
                loadingBox.isVisible = false
                analyzingStatusLabel.isVisible = false

                // Log Results
                println("--- Analysis Complete ---")
                println("Clusters Created: ${clusterLayout.positions.size}")
                println("Points Processed: ${finalPoints.size}")
            }

        }
    }

    private fun createClusterHulls(points: List<VectorPoint>, ids: IntArray, clusterLayout: Map<Int, VirtualPoint>): Map<Int, List<Point2D>> {
        val groupedPoints = points.groupBy { it.clusterId }

        val clusterShapes = clusterLayout.keys.associateWith { id ->
            val clusterPoints = groupedPoints[id] ?: emptyList()
            val rawPoints = clusterPoints.map { Point2D(it.projectedX, it.projectedY) }
            val hull = geometryMath.computeConvexHull(rawPoints)
            geometryMath.smoothPolygon(hull, iterations = 3)
        }

        return clusterShapes
    }

    private fun positionClusters(points: List<VectorPoint>, ids: IntArray, clusterLayout: Map<Int, VirtualPoint>): List<VectorPoint> {
        val rng = java.util.Random()

        return points.mapIndexed { index, p ->
            val cid = ids[index]

            val center = clusterLayout[cid] ?: VirtualPoint(-1, 0.0, 0.0, 10.0)

            // Gaussian distribution - "hot core"
            val gX = rng.nextGaussian() / 3.0
            val gY = rng.nextGaussian() / 3.0

            val offsetX = gX.coerceIn(-1.0, 1.0) * center.radius
            val offsetY = gY.coerceIn(-1.0, 1.0) * center.radius

            p.copy(
                clusterId = cid,
                projectedX = center.x + offsetX,
                projectedY = center.y + offsetY)
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

    @FXML
    fun onExplore() { // Rename to onFocusSelection() if possible
        val current = AnalysisContext.state
        if (current.selectedPoints.isEmpty()) return

        // 1. Find the Bounding Box of the SELECTION
        // (Note: We use the raw projected coordinates)
        val minX = current.selectedPoints.minOf { it.projectedX }
        val maxX = current.selectedPoints.maxOf { it.projectedX }
        val minY = current.selectedPoints.minOf { it.projectedY }
        val maxY = current.selectedPoints.maxOf { it.projectedY }

        // 2. Calculate the Center
        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0

        // 3. Calculate Zoom to Fit
        val width = maxX - minX
        val height = maxY - minY

        // Add 20% padding so points aren't on the edge of the screen
        val padding = 1.2
        val screenW = mapCanvas.width
        val screenH = mapCanvas.height

        // Protect against selecting a single point (width = 0)
        val safeWidth = if (width < 1.0) 100.0 else width
        val safeHeight = if (height < 1.0) 100.0 else height

        val zoomX = screenW / (safeWidth * padding)
        val zoomY = screenH / (safeHeight * padding)

        // Pick the smaller zoom so it fits in both dimensions
        // Clamp it so we don't zoom in to the atomic level
        val targetZoom = minOf(zoomX, zoomY).coerceAtMost(5.0)

        // 4. Update Camera ONLY
        val newCamera = current.camera.copy(
            x = centerX,
            y = centerY,
            zoom = targetZoom
        )

        // Push current camera to stack before moving (for Back button)
        val newHistory = current.cameraHistory + current.camera

        AnalysisContext.update(current.copy(
            camera = newCamera,
            cameraHistory = newHistory,
            selectedPoints = emptySet() // Clear selection
        ))

        // Force render
        renderer.render(AnalysisContext.state)
    }

    @FXML
    fun onBack() { // Rename to onResetView()
        val current = AnalysisContext.state

        // Option A: Simple "Fit to Galaxy" (Recommended)
        // Recalculate camera for ALL points
        val minX = current.allPoints.minOf { it.projectedX }
        val maxX = current.allPoints.maxOf { it.projectedX }
        val minY = current.allPoints.minOf { it.projectedY }
        val maxY = current.allPoints.maxOf { it.projectedY }

        val centerX = (minX + maxX) / 2.0
        val centerY = (minY + maxY) / 2.0

        val width = maxX - minX
        val height = maxY - minY
        val zoom = minOf(mapCanvas.width / width, mapCanvas.height / height) * 0.9

        val homeCamera = current.camera.copy(
            x = centerX,
            y = centerY,
            zoom = zoom
        )

        AnalysisContext.update(current.copy(
            camera = homeCamera,
            selectedPoints = emptySet() // Often nice to clear selection on reset
        ))

        renderer.render(AnalysisContext.state)
    }

    fun updateNavButtons() {
        val current = AnalysisContext.state
        backButton.isDisable = current.cameraHistory.isEmpty()
        exploreButton.isDisable = current.selectedPoints.isEmpty()
    }
}