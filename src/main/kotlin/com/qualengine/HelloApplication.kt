package com.qualengine

import com.qualengine.data.DatabaseFactory
import com.qualengine.model.Atomizer
import javafx.application.Application
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.stage.Stage
import org.kordamp.bootstrapfx.BootstrapFX

class HelloApplication : Application() {
    override fun start(stage: Stage) {
        DatabaseFactory.init()

        // --- ATOMIZER TEST ---
        val fakeDocId = "DOC-101" // Pretend we just ingested a PDF
        val rawText = "The user interface is confusing. I couldn't find the logout button. System crashed twice."

        val atoms = Atomizer.atomize(fakeDocId, rawText)

        println("--- ATOMIZER REPORT ---")
        atoms.forEach { atom ->
            println("ID: ${atom.id} | Parent: ${atom.docId} | Order: ${atom.index} | Text: ${atom.content}")
        }
        println("-----------------------")

        val fxmlLoader = FXMLLoader(HelloApplication::class.java.getResource("hello-view.fxml"))
        val scene = Scene(fxmlLoader.load(), 600.0, 400.0)

        scene.stylesheets.add(BootstrapFX.bootstrapFXStylesheet())

        stage.title = "QualEngine"
        stage.scene = scene
        stage.show()
    }
}
  
