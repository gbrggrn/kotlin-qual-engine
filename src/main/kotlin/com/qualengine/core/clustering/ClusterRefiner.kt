package com.qualengine.core.clustering

import com.qualengine.core.math.VectorMath
import com.qualengine.data.model.VectorPoint

object ClusterRefiner {

    /**
     * The "Orphanage": Looks for "Noise" points (-1) and assigns them to a cluster
     * if they are within [maxDistance] of that cluster's centroid.
     * * @param points The full list of data points.
     * @param clusterIds The mutable array of current cluster assignments (modified in-place).
     */
    fun assignOrphans(
        points: List<VectorPoint>,
        clusterIds: IntArray,
        maxDistance: Double = 0.25
    ) {
        if (points.isEmpty()) return

        // 1. Calculate Centroids for valid clusters
        val centroids = mutableMapOf<Int, DoubleArray>()
        val counts = mutableMapOf<Int, Int>()
        val dimensions = points[0].embedding.size

        // Sum up vectors (Fast Accumulation)
        for (i in points.indices) {
            val id = clusterIds[i]
            if (id == -1) continue // Skip noise

            val vec = points[i].embedding
            val sum = centroids.getOrPut(id) { DoubleArray(dimensions) }

            for (d in 0 until dimensions) {
                sum[d] += vec[d]
            }
            counts[id] = (counts[id] ?: 0) + 1
        }

        // Average them to get final Centroids
        for ((id, count) in counts) {
            val sum = centroids[id]!!
            for (d in 0 until dimensions) {
                sum[d] /= count.toDouble()
            }
        }

        // Pre-calculate magnitudes for the centroids (Optimization)
        val centroidMagnitudes = centroids.mapValues { (_, vec) -> VectorMath.getMagnitude(vec) }

        // Pre-calculate magnitudes for points (Optimization)
        val pointMagnitudes = DoubleArray(points.size) { i ->
            VectorMath.getMagnitude(points[i].embedding)
        }

        // 2. Loop through Orphans
        var adoptedCount = 0
        for (i in points.indices) {
            if (clusterIds[i] == -1) { // It's an orphan

                var bestCluster = -1
                var bestDist = Double.MAX_VALUE
                val orphanVec = points[i].embedding
                val orphanMag = pointMagnitudes[i]

                // Check against every cluster centroid
                for ((id, centerVec) in centroids) {
                    val dist = VectorMath.calculateCosineDistance(
                        orphanVec, orphanMag,
                        centerVec, centroidMagnitudes[id]!!
                    )

                    if (dist < bestDist) {
                        bestDist = dist
                        bestCluster = id
                    }
                }

                // 3. Adopt if close enough
                if (bestCluster != -1 && bestDist <= maxDistance) {
                    clusterIds[i] = bestCluster
                    adoptedCount++
                }
            }
        }

        println("ClusterRefiner: Adopted $adoptedCount orphans into clusters.")
    }
}