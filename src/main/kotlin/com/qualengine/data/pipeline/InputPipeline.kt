package com.qualengine.data.pipeline

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.VectorPoint
import com.qualengine.ui.explorer.ViewMode
import javafx.scene.canvas.Canvas
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import kotlin.math.hypot

class InputPipeline(
    private val mainCanvas: Canvas// Renamed for clarity
) {
    private val DRAG_THRESHOLD = 5.0

    // Dependencies
    private val controller = DependencyRegistry.explorerController
    private val context = DependencyRegistry.analysisContext
    private val coordinateMapper = DependencyRegistry.createCoordinateMapper(mainCanvas)

    // State for tracking interaction lifecycle
    private var isDragging = false
    private var isMarqueeMode = false // true = Selecting, false = Panning

    // Track previous mouse position for Panning deltas
    private var lastMouseX = 0.0
    private var lastMouseY = 0.0
    private var dragStartX = 0.0
    private var dragStartY = 0.0

    // =================================================================
    // 1. ZOOM (The Camera Logic)
    // =================================================================
    fun handleScroll(event: ScrollEvent) {
        val currentCamera = context.state.camera
        val zoomFactor = 1.1

        // 1. Calculate new zoom
        val newZoom = if (event.deltaY > 0) {
            currentCamera.zoom * zoomFactor
        } else {
            currentCamera.zoom / zoomFactor
        }.coerceIn(0.01, 50.0) // Clamp to prevent infinity

        // 2. Zoom towards mouse cursor
        // Where is the mouse in the world NOW?
        val mouseWorld = coordinateMapper.screenToWorld(event.x, event.y, currentCamera)

        // Where is the mouse on screen relative to center?
        val screenOffsetX = event.x - (mainCanvas.width / 2.0)
        val screenOffsetY = event.y - (mainCanvas.height / 2.0)

        // Calculate new camera position to keep mouse over same world point
        val newCamX = mouseWorld.x - (screenOffsetX / newZoom)
        val newCamY = mouseWorld.y - (screenOffsetY / newZoom)

        // 3. Commit
        val newCamera = currentCamera.copy(x = newCamX, y = newCamY, zoom = newZoom)
        context.update(context.state.copy(camera = newCamera))
        controller.requestRender() // Helper in controller to trigger render
    }

    // =================================================================
    // 2. MOUSE PRESS (Decide Intent)
    // =================================================================
    fun handleMousePressed(event: MouseEvent) {
        lastMouseX = event.x
        lastMouseY = event.y
        dragStartX = event.x
        dragStartY = event.y
        isDragging = false

        // DECISION: Are we Panning or Selecting?
        // Shift = Marquee Selection. No Shift = Panning.
        isMarqueeMode = event.isShiftDown || event.isShortcutDown

        if (isMarqueeMode) {
            // Start Drawing the Selection Box
            context.update(context.state.copy(
                isDragging = true, // Re-using existing flag for UI overlay
                dragStartX = event.x,
                dragStartY = event.y,
                dragCurrentX = event.x,
                dragCurrentY = event.y
            ))
        }
    }

    // =================================================================
    // 3. DRAG (Execute Intent)
    // =================================================================
    fun handleMouseDragged(event: MouseEvent) {
        // Check Threshold to avoid micro-jitters
        if (!isDragging && hypot(event.x - dragStartX, event.y - dragStartY) > DRAG_THRESHOLD) {
            isDragging = true
        }
        if (!isDragging && !isMarqueeMode) return // Wait for threshold if panning

        if (isMarqueeMode) {
            handleMarqueeDrag(event)
        } else {
            handlePanDrag(event)
        }

        lastMouseX = event.x
        lastMouseY = event.y
    }

    private fun handlePanDrag(event: MouseEvent) {
        val camera = context.state.camera

        // Pixel Delta
        val dxPx = event.x - lastMouseX
        val dyPx = event.y - lastMouseY

        // World Delta (Reverse Zoom)
        // If zoomed in (2x), moving 100px on screen is only 50 units in world.
        val dxWorld = dxPx / camera.zoom
        val dyWorld = dyPx / camera.zoom

        // Move Camera OPPOSITE to drag (Grab and Pull)
        val newCamera = camera.copy(
            x = camera.x - dxWorld,
            y = camera.y - dyWorld
        )

        context.update(context.state.copy(camera = newCamera))
        controller.requestRender()
    }

    private fun handleMarqueeDrag(event: MouseEvent) {
        // Update the visual box for the renderer
        context.update(context.state.copy(
            dragCurrentX = event.x,
            dragCurrentY = event.y
        ))

        // Optional: Real-time selection highlighting can go here
        // (But usually better to do it on Release for performance)
    }

    // =================================================================
    // 4. RELEASE (Commit Selection)
    // =================================================================
    fun handleMouseReleased(event: MouseEvent) {
        if (isMarqueeMode && isDragging) {
            commitMarqueeSelection()
        }

        // Reset flags
        isDragging = false
        isMarqueeMode = false
        context.update(context.state.copy(isDragging = false))
        controller.updateNavButtons()
    }

    private fun commitMarqueeSelection() {
        val state = context.state
        val boundsX = minOf(state.dragStartX, state.dragCurrentX)
        val boundsY = minOf(state.dragStartY, state.dragCurrentY)
        val boundsW = kotlin.math.abs(state.dragCurrentX - state.dragStartX)
        val boundsH = kotlin.math.abs(state.dragCurrentY - state.dragStartY)

        // Find points inside the Screen Rectangle
        val captured = state.allPoints.filter { point ->
            // PROJECT TO SCREEN FIRST
            val screenPos = coordinateMapper.worldToScreen(point.projectedX, point.projectedY, state.camera)

            screenPos.x >= boundsX && screenPos.x <= boundsX + boundsW &&
                    screenPos.y >= boundsY && screenPos.y <= boundsY + boundsH
        }.toSet()

        context.update(state.copy(selectedPoints = captured))
    }

    // =================================================================
    // 5. HOVER & CLICK
    // =================================================================
    fun handleMouseMove(event: MouseEvent) {
        if (isDragging) return

        val state = context.state
        var closestPoint: VectorPoint? = null
        var minDistance = 15.0 // Hit radius in pixels

        // Hit Test in Screen Space
        for (point in state.allPoints) {
            val screenPos = coordinateMapper.worldToScreen(point.projectedX, point.projectedY, state.camera)
            val dist = hypot(event.x - screenPos.x, event.y - screenPos.y)

            if (dist < minDistance) {
                minDistance = dist
                closestPoint = point
            }
        }

        if (state.hoveredPoint != closestPoint) {
            context.update(state.copy(hoveredPoint = closestPoint))
            controller.requestRender()
        }
    }

    fun handleClick(event: MouseEvent) {
        if (isDragging) return // It was a drag, not a click

        val state = context.state
        val target = state.hoveredPoint

        if (target != null) {
            // Toggle Selection Logic
            val newSelection = if (event.isShiftDown || event.isShortcutDown) {
                // Add/Remove from existing
                if (state.selectedPoints.contains(target)) state.selectedPoints - target
                else state.selectedPoints + target
            } else {
                // Replace selection
                setOf(target)
            }
            context.update(state.copy(selectedPoints = newSelection))

            // Double Click -> Focus
            if (event.clickCount == 2) {
                controller.switchView(ViewMode.SELECTION)
            }
        } else {
            // Clicked Empty Space -> Deselect (unless Shift held)
            if (!event.isShiftDown && !event.isShortcutDown) {
                context.update(state.copy(selectedPoints = emptySet()))
            }
        }
        controller.updateNavButtons()
        controller.requestRender()
    }
}

/*
import javafx.scene.input.MouseEvent
import com.qualengine.core.AnalysisContext
import com.qualengine.data.model.VectorPoint
import com.qualengine.ui.explorer.ExplorerController
import com.qualengine.ui.explorer.ViewMode
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.pow

class InputPipeline(
    private val context: AnalysisContext,
    private val explorerController: ExplorerController
) {
    private val PADDING = 40.0
    private val DRAG_THRESHOLD = 5.0
    private var isRealDrag = false

    private fun toScreenX(normalizedX: Double, width: Double): Double {
        return normalizedX * (width - PADDING * 2) + PADDING
    }

    private fun toScreenY(normalizedY: Double, height: Double): Double {
        return normalizedY * (height - PADDING * 2) + PADDING
    }

    fun handleScroll(event: MouseEvent) {

    }

    fun handleMousePressed(event: MouseEvent) {
        val current = context.state

        isRealDrag = false

        // Logic: Clear selection UNLESS holding shift/control
        val newSelection = if (!event.isShiftDown && !event.isShortcutDown) {
            emptySet()
        } else {
            current.selectedPoints
        }

        context.update(current.copy(
            isDragging = true,
            dragStartX = event.x,
            dragStartY = event.y,
            dragCurrentX = event.x,
            dragCurrentY = event.y,
            selectedPoints = newSelection
        ))
    }

    fun handleMouseDragged(event: MouseEvent) {
        val current = context.state
        if (!current.isDragging) return

        // 1. Check Threshold (Your "Real Drag" Logic)
        if (!isRealDrag) {
            val dist = hypot(event.x - current.dragStartX, event.y - current.dragStartY)
            if (dist < DRAG_THRESHOLD) return // Ignore jitters
            isRealDrag = true
        }

        // 2. Calculate Marquee Bounds
        // We use min/max to ensure x is always top-left, even if dragging backwards
        val boundsX = minOf(current.dragStartX, event.x)
        val boundsY = minOf(current.dragStartY, event.y)
        val boundsW = kotlin.math.abs(event.x - current.dragStartX)
        val boundsH = kotlin.math.abs(event.y - current.dragStartY)

        // 3. Find Points Inside
        // OPTIMIZATION: We don't need to recalculate min/max of the dataset here.
        // We assume projectedX is already normalized (0..1).
        val captured = current.allPoints.filter { point ->
            val sx = toScreenX(point.projectedX, current.width)
            val sy = toScreenY(point.projectedY, current.height)

            sx >= boundsX && sx <= boundsX + boundsW &&
                    sy >= boundsY && sy <= boundsY + boundsH
        }.toSet()

        // 4. Update State
        // Note: If shift is held, we usually ADD to selection, but for marquee
        // it's often simpler to replace. Let's assume replace for the drag box.
        context.update(current.copy(
            dragCurrentX = event.x,
            dragCurrentY = event.y,
            selectedPoints = captured
        ))
    }

    fun handleMouseReleased(event: MouseEvent) {
        val current = context.state
        context.update(current.copy(isDragging = false))
        explorerController.updateNavButtons()
    }

    fun handleMouseMove(event: MouseEvent) {
        val current = context.state
        if (current.isDragging || current.selectedPoints.isNotEmpty()) return

        val mouseX = event.x
        val mouseY = event.y

        // --- HIT TESTING ---
        var closestAtom: VectorPoint? = null
        var minDistance = 10.0 // Interaction Radius

        for (point in current.allPoints) {
            val sx = toScreenX(point.projectedX, current.width)
            val sy = toScreenY(point.projectedY, current.height)

            val distance = sqrt((mouseX - sx).pow(2) + (mouseY - sy).pow(2))

            if (distance < minDistance) {
                minDistance = distance
                closestAtom = point
            }
        }

        // Only update if something changed (Performance check)
        if (current.hoveredPoint != closestAtom) {
            context.update(current.copy(hoveredPoint = closestAtom))
        }
    }

    fun handleClick(event: MouseEvent) {
        val current = context.state
        // Ignore click if drag just finished
        if (current.isDragging && isRealDrag)
            return

        if (event.clickCount == 2 && current.hoveredPoint != null) {
            explorerController.switchView(ViewMode.SELECTION)
        }
        // Toggle selection if hovering
        current.hoveredPoint?.let { point ->
            // Create a new set based on the old one
            val newSelection = if (current.selectedPoints.contains(point)) {
                current.selectedPoints - point // Toggle Off
            } else {
                current.selectedPoints + point // Toggle On
            }

            context.update(current.copy(selectedPoints = newSelection))

            explorerController.updateNavButtons()
        } ?: run {
            // Clicked empty space -> Clear
            if (!event.isShiftDown && !event.isShortcutDown) {
                context.update(current.copy(selectedPoints = emptySet()))
                explorerController.updateNavButtons()
            }
        }
    }
    */