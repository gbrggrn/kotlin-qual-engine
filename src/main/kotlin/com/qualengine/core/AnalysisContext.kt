package com.qualengine.core

import com.qualengine.data.model.AppState

object AnalysisContext {
    // === Stores AppState ===
    private var _state = AppState()

    // === Retrieve AppState ===
    val state: AppState
        get() = _state

    // === Update AppState ===
    fun update(newState: AppState) {
        _state = newState
    }
}