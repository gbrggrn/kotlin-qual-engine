package com.qualengine.core.clustering

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.VectorPoint

object ClusterRefiner {
    private val vectorMath = DependencyRegistry.vectorMath

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
        val centroidMagnitudes = centroids.mapValues { (_, vec) -> vectorMath.getMagnitude(vec) }

        // Pre-calculate magnitudes for points (Optimization)
        val pointMagnitudes = DoubleArray(points.size) { i ->
            vectorMath.getMagnitude(points[i].embedding)
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
                    val dist = vectorMath.calculateCosineDistance(
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

    private const val MAX_CLUSTER_SIZE = 15
    private const val MIN_SPLIT_SIZE = 6
    private const val SPLIT_SENSITIVITY = 1.2

    fun splitLargeClusters(points: List<VectorPoint>, clusterIds: IntArray): IntArray {
        val newClusterIds = clusterIds.clone()

        // Group by the current clusters
        val clusters = clusterIds.indices
            .groupBy { clusterIds[it] }
            .filter { it.key != -1 } // Ignore noise

        // Find next safe ID to assign new clusters to
        var nextId = (clusterIds.maxOrNull() ?: 0) + 1

        for ((id, indices) in clusters) {
            // Only split if it is a larger cluster than allowed
            if (indices.size > MAX_CLUSTER_SIZE) {

                println("Found excessively large cluster (${indices.size}). Splitting...")

                // Perform recursive split, evaluating as we go
                val subClusters = recursiveSplit(indices, points)

                // If multiple new clusters are returned: assign new IDs
                if (subClusters.size > 1) {
                    for (subClusterIndices in subClusters) {
                        val newId = nextId++
                        for (index in subClusterIndices) {
                            newClusterIds[index] = nextId++
                        }
                    }
                }
                // If subClusters.size == 1: no split happened so original IDs are kept
            }
        }
        return newClusterIds
    }

    private fun recursiveSplit(indices: List<Int>, points: List<VectorPoint>): List<List<Int>> {
        // Evaluate size: if too small, no split
        if (indices.size < MIN_SPLIT_SIZE)
            return listOf(indices)

        // Calculate centroid of the group to split
        val vectors = indices.map { points[it].embedding }
        val centroid = vectorMath.calculateCentroid(vectors)

        // Find principal axis between centroid and furthest point
        val furthestVector = vectors.maxByOrNull { vectorMath.distance(it, centroid) } ?: return listOf(indices)
        val axis = vectorMath.subtract(furthestVector, centroid)

        // No split if all points are identical
        if (vectorMath.getMagnitude(axis) == 0.0)
            return listOf(indices)

        // Project every point onto the principal axis and sort
        val sortedIndices = indices.sortedBy { index ->
            vectorMath.dotProduct(points[index].embedding, axis)
        }

        // Scan for "fault line", exactly as in ThematicSplitter
        val projections = sortedIndices.map { vectorMath.dotProduct(points[it].embedding, axis) }

        var maxGap = - 1.0
        var splitIndex = -1

        // Calculate gaps between the points (that are now in sequence)
        val gaps = DoubleArray(sortedIndices.size -1)
        for (i in 0 until sortedIndices.size -1) {
            val gap = projections[i + 1] - projections[i]
            gaps[i] = gap
            if (gap > maxGap) {
                maxGap = gap
                splitIndex = i
            }
        }

        val avgGap = gaps.average()

        // Evaluate split
        val isMassive = indices.size > (MAX_CLUSTER_SIZE * 2) // If massive cluster
        val isFaultLine = maxGap > (avgGap * SPLIT_SENSITIVITY) // If huge gap

        if (splitIndex != -1 && (isFaultLine || isMassive)) {
            // Slice me nice!
            val clusterA = sortedIndices.subList(0, splitIndex + 1)
            val clusterB = sortedIndices.subList(splitIndex +1, sortedIndices.size)

            println("Recursive splitter split: cluster A(${clusterA.size}, cluster B(${clusterB.size}")

            // Recurse on children
            return recursiveSplit(clusterA, points) + recursiveSplit(clusterB, points)
        }
        return listOf(indices)
    }
}