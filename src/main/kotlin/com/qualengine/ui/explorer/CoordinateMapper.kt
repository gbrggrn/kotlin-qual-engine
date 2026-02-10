package com.qualengine.ui.explorer

import javafx.scene.canvas.Canvas
import javafx.geometry.Point2D
import com.qualengine.data.model.AppState.Camera

class CoordinateMapper(private val canvas: Canvas) {

    // === Convert the physics-created world to Java FX screen coordinates
    // This method is used for rendering points!
    fun worldToScreen(worldX: Double, worldY: Double, camera: Camera): Point2D {
        val centerX = canvas.width / 2.0
        val centerY = canvas.height / 2.0

        // Move the world so that the camera is at (0,0) relative
        val relX = worldX - camera.x
        val relY = worldY - camera.y

        // Apply zoom
        val scaledX = relX * camera.zoom
        val scaledY = relY * camera.zoom

        // Offset: move (0,0) to the center of the canvas
        return Point2D(centerX + scaledX, centerY + scaledY)
    }

    // === Convert Java FX screen coordinates to physics-created world coordinates
    // This method is used for mouse clicks and zooming towards the cursor!
    fun screenToWorld(screenX: Double, screenY: Double, camera: Camera): Point2D {
        val centerX = canvas.width / 2.0
        val centerY = canvas.height / 2.0

        // Remove offset
        val offsetX = screenX - centerX
        val offsetY = screenY - centerY

        // Unscale
        val relX = offsetX / camera.zoom
        val relY = offsetY / camera.zoom

        return Point2D(relX + camera.x, relY + camera.y)
    }
}