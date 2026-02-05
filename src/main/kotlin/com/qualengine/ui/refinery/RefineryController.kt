package com.qualengine.ui.refinery

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.pipeline.RefineryJob
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.cell.ProgressBarTableCell
import javafx.stage.FileChooser
import kotlin.concurrent.thread

class RefineryController {
    // Dependencies
    private val refinery = DependencyRegistry.refinery

    // Components
    @FXML
    private lateinit var jobTable: TableView<RefineryJob>
    @FXML
    private lateinit var txtFilter: TextField
    @FXML
    private lateinit var btnProcess: Button

    // Cols
    @FXML
    private lateinit var colStatus: TableColumn<RefineryJob, String>
    @FXML
    private lateinit var colName: TableColumn<RefineryJob, String>
    @FXML
    private lateinit var colType: TableColumn<RefineryJob, String>
    @FXML
    private lateinit var colProgress: TableColumn<RefineryJob, Double>
    @FXML
    private lateinit var colAction: TableColumn<RefineryJob, Void>

    // Lists
    private val masterList = FXCollections.observableArrayList<RefineryJob>()
    private val filteredList = FilteredList(masterList) { true }

    @FXML
    fun initialize() {
        DependencyRegistry.refineryController = this
        jobTable.items = filteredList

        colStatus.setCellValueFactory { it.value.statusProperty }
        colName.setCellValueFactory { it.value.nameProperty }
        colType.setCellValueFactory { it.value.typeProperty }
        colProgress.setCellValueFactory { it.value.progressProperty.asObject() }
        colProgress.setCellFactory(ProgressBarTableCell.forTableColumn())

        colAction.setCellFactory {
            object : TableCell<RefineryJob, Void>() {
                private val btn = Button("X")

                init {
                    btn.style = "-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 5;"

                    btn.setOnAction {
                        if (index >= 0 && index < tableView.items.size) {
                            val job = tableView.items[index]
                            masterList.remove(job)
                        }
                    }
                }

                override fun updateItem(item: Void?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty) {
                        graphic = null
                    } else {
                        graphic = btn
                    }
                }
            }
        }

        txtFilter.textProperty().addListener { _, _, newValue ->
            filteredList.setPredicate { job ->
                if (newValue.isNullOrEmpty())
                    return@setPredicate true

                val lowerFilter = newValue.lowercase()

                job.nameProperty.get().lowercase().contains(lowerFilter) ||
                        job.statusProperty.get().lowercase().contains(lowerFilter)
            }
        }

    }

    @FXML
    fun onAddFile() {
        val chooser = FileChooser()
        chooser.title = "Select source files"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("Text files", "*.txt", "*.csv", "*.md"))
        val window = jobTable.scene.window

        val files = chooser.showOpenMultipleDialog(window)

        if (files != null) {
            for (file in files) {
                val alreadyExists = masterList.any { it.file.absolutePath == file.absolutePath }

                if (alreadyExists){
                    println("[WARNING] Skipped file duplicate: ${file.name}")
                    continue
                }
                masterList.add(RefineryJob(file))
            }
        }
    }

    @FXML
    fun onProcessQueue() {
        if (masterList.isEmpty()) return

        btnProcess.isDisable = true

        // Create a fixed copy of the list.
        // This prevents "ConcurrentModificationException" if the UI updates while processing.
        val queueSnapshot = ArrayList(masterList)

        thread(start = true, isDaemon = true) {
            for (job in queueSnapshot) {

                // Check status on snapshot, but verify with current state if needed
                if (job.statusProperty.get() == "Completed") continue

                Platform.runLater { job.setStatus("Starting...") }

                try {
                    // Heavy lifting upcoming
                    refinery.ingestFile(job.file) { progress, status ->

                        // TODO: Throttle UI updates?
                        // If this runs 1000 times/sec, it can freeze the UI.
                        // For paragraphs (1 per sec), standard runLater is fine.
                        Platform.runLater {
                            job.setStatus(status)
                            job.setProgress(progress)
                        }
                    }

                    // Success State
                    Platform.runLater {
                        job.setStatus("Completed")
                        job.setProgress(1.0)
                    }

                } catch (t: Throwable) { // CATCH EVERYTHING (Errors + Exceptions)

                    val errorMessage = when (t) {
                        is OutOfMemoryError -> "OOM Error (Restart App)"
                        else -> "Error: ${t.message?.take(20) ?: "Unknown"}"
                    }

                    Platform.runLater {
                        job.setStatus(errorMessage)
                        job.setProgress(0.0)
                    }
                    System.err.println("CRITICAL FAILURE on job ${job.file.name}:")
                    t.printStackTrace()
                }
            }

            Platform.runLater { btnProcess.isDisable = false }
        }
    }
}