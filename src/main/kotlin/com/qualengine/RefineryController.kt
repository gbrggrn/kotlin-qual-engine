package com.qualengine

import com.qualengine.model.RefineryJob
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.fxml.FXML
import javafx.scene.control.*
import javafx.scene.control.cell.ProgressBarTableCell
import javafx.stage.FileChooser
import kotlin.concurrent.thread

class RefineryController {

    @FXML private lateinit var jobTable: TableView<RefineryJob>
    @FXML private lateinit var txtFilter: TextField
    @FXML private lateinit var btnProcess: Button

    // Cols
    @FXML private lateinit var colStatus: TableColumn<RefineryJob, String>
    @FXML private lateinit var colName: TableColumn<RefineryJob, String>
    @FXML private lateinit var colType: TableColumn<RefineryJob, String>
    @FXML private lateinit var colProgress: TableColumn<RefineryJob, Double>
    @FXML private lateinit var colAction: TableColumn<RefineryJob, Void>

    private val masterList = FXCollections.observableArrayList<RefineryJob>()
    private val filteredList = FilteredList(masterList) { true }

    @FXML
    fun initialize() {
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
                    // Fixed CSS: added "-fx-font-" prefix to weight
                    btn.style = "-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 5;"

                    btn.setOnAction {
                        // Safety check: sometimes buttons capture clicks during table updates
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
        if (masterList.isEmpty())
            return

        btnProcess.isDisable = true

        thread (start = true, isDaemon = true) {
            for (job in masterList) {
                if (job.statusProperty.get() == "Completed")
                    continue

                Platform.runLater { job.setStatus("Starting...") }

                try {
                    Refinery.ingestFile(job.file) { progress, status ->
                        Platform.runLater {
                            job.setStatus(status)
                            job.setProgress(progress)
                        }
                    }
                } catch (e: Exception) {
                    Platform.runLater {
                        job.setStatus("Error")
                        job.setProgress(0.0)
                    }
                    e.printStackTrace()
                }
            }
            Platform.runLater { btnProcess.isDisable = false }
        }
    }
}