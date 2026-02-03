package com.qualengine.data.pipeline

import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.SimpleStringProperty
import java.io.File

class RefineryJob (val file: File) {

    // Table view in refinery watches these:
    val nameProperty = SimpleStringProperty(file.name)
    val typeProperty = SimpleStringProperty(file.extension.uppercase())
    val statusProperty = SimpleStringProperty("Queued")
    val progressProperty = SimpleDoubleProperty(0.0)

    // Helper to set status
    fun setStatus(msg: String) {
        statusProperty.set(msg)
    }

    // Helper to set value of progress-bar
    fun setProgress(value: Double) {
        progressProperty.set(value)
    }
}