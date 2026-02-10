package com.qualengine.core.clustering

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.ClusterResult
import com.qualengine.data.model.VectorPoint
import kotlinx.coroutines.flow.combine

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

    // --- CONFIGURATION ---
    // The "Hard Limit". No cluster shall pass this size.
    private const val MAX_CLUSTER_SIZE = 20

    // The "Dust Limit". Don't create shards smaller than this.
    // This prevents splitting a cluster of 21 into 20 and 1.
    private const val MIN_FRAGMENT_SIZE = 5

    /**
     * The Entry Point: Takes the initial DBSCAN results and fractures them
     * until every cluster meets the MAX_CLUSTER_SIZE constraint.
     */
    fun splitLargeClusters(points: List<VectorPoint>, clusterIds: IntArray): IntArray {
        val newClusterIds = clusterIds.clone()
        var nextClusterId = (clusterIds.maxOrNull() ?: 0) + 1

        // 1. Group indices by their current Cluster ID
        // Filter out -1 (Noise) for now, we'll handle them separately if needed
        // OR: Include noise if you want to cluster the garbage too.
        // Let's include noise as a specific "Cluster 0" to see if it breaks down nicely.
        val clusters = clusterIds.indices.groupBy {
            if (clusterIds[it] == -1) Int.MAX_VALUE else clusterIds[it]
        }

        for ((id, indices) in clusters) {
            // If it's a valid cluster OR the "Noise Blob"
            if (indices.size > MAX_CLUSTER_SIZE) {

                // EXECUTE THE FRACTURE
                val fragments = recursiveSplit(indices, points)

                // Assign new IDs to the fragments
                for (fragment in fragments) {
                    // If fragment is just dust, revert to noise
                    if (fragment.size < 3) {
                        fragment.forEach { newClusterIds[it] = -1 }
                    } else {
                        // Assign a new, unique ID for this solar system
                        val newId = nextClusterId++
                        fragment.forEach { newClusterIds[it] = newId }
                    }
                }
            }
        }

        return newClusterIds
    }

    /**
     * Recursively splits a list of indices until all chunks are <= MAX_CLUSTER_SIZE.
     */
    private fun recursiveSplit(indices: List<Int>, points: List<VectorPoint>): List<List<Int>> {
        // 1. BASE CASE: Success!
        if (indices.size <= MAX_CLUSTER_SIZE) {
            return listOf(indices)
        }

        // 2. LINEARIZE: Find the best axis to project onto
        val vectors = indices.map { points[it].embedding }
        val centroid = vectorMath.calculateCentroid(vectors)

        // Find furthest point to define the "Long Axis" of the cloud
        val furthestVector = vectors.maxByOrNull { vectorMath.distance(it, centroid) }
            ?: return listOf(indices)

        val axis = vectorMath.subtract(furthestVector, centroid)
        if (vectorMath.getMagnitude(axis) == 0.0) return listOf(indices) // Identical points

        // Project points onto the axis and sort them
        val sortedIndices = indices.sortedBy { index ->
            vectorMath.dotProduct(points[index].embedding, axis)
        }

        // 3. FIND THE BEST CUT
        // We look for the largest semantic gap, BUT we are constrained by MIN_FRAGMENT_SIZE.
        // We cannot cut at index 0 or index (size-1).

        val projections = sortedIndices.map { vectorMath.dotProduct(points[it].embedding, axis) }

        var bestSplitIndex = -1
        var maxGap = -1.0

        // Valid cut range: from MIN_SIZE to (Total - MIN_SIZE)
        val startScan = MIN_FRAGMENT_SIZE - 1
        val endScan = sortedIndices.size - MIN_FRAGMENT_SIZE - 1

        if (startScan > endScan) {
            // Cluster is too small to split safely (e.g. size 8, min 5).
            // Return as is, even if slightly violates max size (better than dust).
            return listOf(indices)
        }

        for (i in startScan..endScan) {
            val gap = projections[i+1] - projections[i]
            if (gap > maxGap) {
                maxGap = gap
                bestSplitIndex = i
            }
        }

        // 4. EXECUTE SPLIT
        if (bestSplitIndex != -1) {
            val clusterA = sortedIndices.subList(0, bestSplitIndex + 1)
            val clusterB = sortedIndices.subList(bestSplitIndex + 1, sortedIndices.size)

            // Recurse! Keep splitting A and B until they behave.
            return recursiveSplit(clusterA, points) + recursiveSplit(clusterB, points)
        }

        // Fallback (should theoretically not be reached if math holds)
        return listOf(indices)
    }
}