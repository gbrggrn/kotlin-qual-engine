package com.qualengine.qualengine

import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage
import org.kordamp.bootstrapfx.BootstrapFX

class HelloApplication : Application() {
    override fun start(stage: Stage) {
        val fxmlLoader = FXMLLoader(HelloApplication::class.java.getResource("hello-view.fxml"))
        val scene = Scene(fxmlLoader.load(), 600.0, 400.0)

        scene.stylesheets.add(BootstrapFX.bootstrapFXStylesheet())

        stage.title = "QualEngine"
        stage.scene = scene
        stage.show()
    }
}
  
