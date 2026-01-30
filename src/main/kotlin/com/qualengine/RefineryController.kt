package com.qualengine

import javafx.fxml.FXML
import javafx.scene.control.TableView

class RefineryController {

    @FXML private lateinit var jobTable: TableView<Any>

    @FXML
    fun onAddFile() {
        println("Add File Clicked")
    }

    @FXML
    fun onProcessQueue() {
        println("Process Queue Clicked")
    }
}