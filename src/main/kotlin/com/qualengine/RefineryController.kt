package com.qualengine

import com.qualengine.model.RefineryJob
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.cell.ProgressBarTableCell
import javafx.stage.FileChooser
import kotlin.concurrent.thread

class RefineryController {

    @FXML private lateinit var jobTable: TableView<RefineryJob>
    @FXML private lateinit var colName: TableColumn<RefineryJob, String>
    @FXML private lateinit var colType: TableColumn<RefineryJob, String>
    @FXML private lateinit var colProgress: TableColumn<RefineryJob, Double>
    @FXML private lateinit var colAction: TableColumn<RefineryJob, String>

    @FXML private lateinit var btnProcess: Button

    private val jobList = FXCollections.observableArrayList<RefineryJob>()

    @FXML
    fun initialize() {
        jobTable.items = jobList

        colName.setCellValueFactory { it.value.nameProperty }
        colType.setCellValueFactory { it.value.typeProperty }

        colProgress.setCellValueFactory { it.value.progressProperty.asObject() }
        colProgress.setCellFactory(ProgressBarTableCell.forTableColumn())
    }

    @FXML
    fun onAddFile() {
        val chooser = FileChooser()
        chooser.title = "Choose a file"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("Text files", "*.txt", "*.csv", "*.md"))

        val files = chooser.showOpenMultipleDialog(jobTable.scene.window)

        if (files != null) {
            for (file in files) {
                jobList.add(RefineryJob(file))
            }
        }
    }

    @FXML
    fun onProcessQueue() {
        if (jobList.isEmpty())
            return

        btnProcess.isDisable = true

        thread (start = true, isDaemon = true) {
            for (job in jobList) {
                if (job.statusProperty.get() == "Completed")
                    continue

                Platform.runLater { job.setStatus("Starting...") }

                try {
                    Refinery.ingestFile(job.file) { statusMessage ->
                        Platform.runLater {
                            job.setStatus(statusMessage)
                            job.setProgress(-1.0) // CALCULATE PROPERLY LATER!!!
                        }
                    }

                    Platform.runLater {
                        job.setStatus("Completed")
                        job.setProgress(1.0)
                    }
                } catch (e: Exception) {
                    Platform.runLater {
                        job.setStatus("Error: ${e.message}")
                        job.setProgress(0.0)
                    }
                    e.printStackTrace()
                }
            }
            Platform.runLater { btnProcess.isDisable = false }
        }
    }
}