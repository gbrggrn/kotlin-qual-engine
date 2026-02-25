package com.qualengine.core.clustering

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.ClusterResult
import com.qualengine.data.model.VectorPoint

class DBSCAN(
    private val epsilon: Double,
    private val minPoints: Int
) {
    private val vectorMath = DependencyRegistry.vectorMath

    fun runDBSCAN(points: List<VectorPoint>): ClusterResult {
        // === Extract vectors (embeddings)
        val vectors = points.map { it.embedding }

        val n = vectors.size
        val labels = IntArray(n) { 0 }
        var clusterId = 0

        // Pre-calculate vector magnitudes
        val magnitudes = vectors.map { vectorMath.getMagnitude(it) }.toDoubleArray()

        for (i in 0 until n) {
            if (labels[i] != 0) continue

            // Pass the RAW vectors to the helpers
            val neighbors = getNeighbors(vectors, magnitudes, i)

            if (neighbors.size < minPoints) {
                labels[i] = -1
            } else {
                clusterId++
                labels[i] = clusterId
                expandCluster(vectors, magnitudes, labels, i, neighbors, clusterId)
            }
        }

        return ClusterResult(
            clusterIds = labels,
            clusterCount = clusterId,
            noiseCount = labels.count { it == -1 }
        )
    }

    // ==================================
    // HELPERS
    // ==================================
    private fun expandCluster(
        vectors: List<DoubleArray>,
        magnitudes: DoubleArray,
        labels: IntArray,
        pIndex: Int,
        neighbors: MutableList<Int>,
        clusterId: Int
    ) {
        labels[pIndex] = clusterId
        var i = 0
        while (i < neighbors.size) {
            val neighbor = neighbors[i]
            if (labels[neighbor] == -1) labels[neighbor] = clusterId
            if (labels[neighbor] == 0) {
                labels[neighbor] = clusterId
                val newNeighbors = getNeighbors(vectors, magnitudes, neighbor)
                if (newNeighbors.size >= minPoints) neighbors.addAll(newNeighbors)
            }
            i++
        }
    }

    private fun getNeighbors(
        vectors: List<DoubleArray>,
        magnitudes: DoubleArray,
        index: Int
    ): MutableList<Int> {
        val neighbors = mutableListOf<Int>()
        val targetVec = vectors[index]
        val targetMag = magnitudes[index]

        for (i in vectors.indices) {
            if (i == index) continue
            // Calls your new VectorMath logic
            val dist = vectorMath.calculateCosineDistance(targetVec, targetMag, vectors[i], magnitudes[i])
            if (dist <= epsilon) neighbors.add(i)
        }
        return neighbors
    }
}