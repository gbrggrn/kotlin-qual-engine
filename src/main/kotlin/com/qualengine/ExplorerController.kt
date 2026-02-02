package com.qualengine

import com.qualengine.logic.MathUtils
import com.qualengine.logic.InputPipeline
import com.qualengine.model.ExplorerState
import com.qualengine.model.Sentences
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
import kotlin.math.exp

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
            pipeline.handleMove(event)
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

    private fun renderAtoms(points: List<MathUtils.Point2D>, texts: List<String>) {
        explorerState.renderedAtoms = points
        explorerState.atomsContents = texts
        explorerState.width = mapCanvas.width
        explorerState.height = mapCanvas.height

        renderer.render(explorerState)
    }

    private fun updateSidePanel() {
        detailsBox.children.clear()

        if (explorerState.selectedAtoms.isEmpty()){
            val placeholder = Label("Select dots on the map to view details.")
            placeholder.isWrapText = true
            placeholder.style = "-fx-text-fill: #7f8c8d; -fx-font-style: italic;"
            detailsBox.children.add(placeholder)
            return
        }

        val selectedSubset = explorerState.selectedAtoms.take(numberOfDetailsFor)

        for (atom in selectedSubset) {
            val content = explorerState.atomsContents.getOrNull(atom.originalIndex) ?: "Unknown"

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

        if (explorerState.selectedAtoms.size > 50){
            val warning = Label("... and ${explorerState.selectedAtoms.size - 50} more items.")
            warning.style = "-fx-text-fill: #e74c3c; -fx-font-weight: bold;"
            detailsBox.children.add(warning)
        }
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
            val atoms = MathUtils.performPCA(vectors)

            // 3. Draw
            Platform.runLater {
                loadingBox.isVisible = false
                renderAtoms(atoms, texts)
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