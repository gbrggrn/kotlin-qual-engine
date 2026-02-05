package com.qualengine.data.model

data class VirtualPoint(
    val clusterId: Int,
    var x: Double,      // Projected UI position
    var y: Double,
    val radius: Double, // Semantic spread
    val theme: String = ""
)
