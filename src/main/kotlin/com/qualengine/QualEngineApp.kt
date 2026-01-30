package com.qualengine

import com.qualengine.data.DatabaseFactory
import com.qualengine.model.Atomizer
import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage
import org.kordamp.bootstrapfx.BootstrapFX

class QualEngineApp : Application() {
    override fun start(stage: Stage) {
        DatabaseFactory.init()

        val fxmlLoader = FXMLLoader(QualEngineApp::class.java.getResource("main-view.fxml"))
        val scene = Scene(fxmlLoader.load(), 600.0, 400.0)

        scene.stylesheets.add(BootstrapFX.bootstrapFXStylesheet())

        stage.title = "QualEngine"
        stage.scene = scene
        stage.show()
    }
}
  
