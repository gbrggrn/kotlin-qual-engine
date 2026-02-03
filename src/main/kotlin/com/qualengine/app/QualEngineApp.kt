package com.qualengine.app

import com.qualengine.data.db.DatabaseFactory
import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage

class QualEngineApp : Application() {
    override fun start(stage: Stage) {
        DatabaseFactory.init()

        val fxmlLoader = FXMLLoader(QualEngineApp::class.java.getResource("/com/qualengine/main-view.fxml"))
        val scene = Scene(fxmlLoader.load(), 1000.0, 700.0)

        scene.stylesheets.add(QualEngineApp::class.java.getResource("/com/qualengine/styles.css").toExternalForm())

        stage.title = "QualEngine"
        stage.scene = scene
        stage.show()
    }
}