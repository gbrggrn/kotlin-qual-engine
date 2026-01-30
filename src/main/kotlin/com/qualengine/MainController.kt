package com.qualengine

import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.layout.StackPane

class MainController {
    @FXML private lateinit var contentArea: StackPane
    @FXML private lateinit var btnRefinery: Button
    @FXML private lateinit var btnExplorer: Button

    // Cache view
    private var refineryView: Parent? = null

    @FXML
    fun initialize() {
        navToRefinery()
    }

    @FXML
    fun navToRefinery() {
        updateActiveButton(btnRefinery)

        if (refineryView == null) {
            val loader = FXMLLoader(javaClass.getResource("refinery-view.fxml"))
            refineryView = loader.load()
        }
        setContent(refineryView!!)
    }

    @FXML
    fun navToExplorer() {
        updateActiveButton(btnExplorer)

        contentArea.children.clear()
    }

    private fun setContent(node: Parent) {
        contentArea.children.clear()
        contentArea.children.add(node)
    }

    private fun updateActiveButton(active: Button) {
        val defaultStyle = "-fx-background-color: transparent; -fx-text-fill: #bdc3c7;"
        val activeStyle = "-fx-background-color: #34495e; -fx-text-fill: white;"

        btnRefinery.style = defaultStyle
        btnExplorer.style = defaultStyle

        active.style = activeStyle
    }
}