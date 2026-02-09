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

    /*
    private const val MAX_CLUSTER_SIZE = 20
    private const val MIN_SPLIT_SIZE = 5
    private const val SPLIT_SENSITIVITY = 1.1

    fun splitLargeClusters(points: List<VectorPoint>, clusterIds: IntArray): IntArray {
        val newClusterIds = clusterIds.clone()

        // Many points usually end up as noise - we collect all those into a "mega cluster"
        val noiseIndices = clusterIds.indices.filter { clusterIds[it] == -1 }

        // If there are a lot of noise-points: assign arbitrary ID
        val tempNoiseId = Int.MAX_VALUE
        if (noiseIndices.isNotEmpty())
            noiseIndices.forEach { newClusterIds[it] = tempNoiseId }

        // Group by the current clusters
        val clusters = clusterIds.indices
            .groupBy { clusterIds[it] }

        // Find next safe ID to assign new clusters to
        var nextId = (clusterIds.filter { it != -1 }.maxOrNull() ?: 0) + 1

        var debugNumOfClusterSplit = 0
        var debugNumOfClustersReverted = 0
        for ((id, indices) in clusters) {
            // Split if large noise-blob or mega-cluster
            val isNoiseBlob = (id == tempNoiseId)
            val isMegaCluster = indices.size > MAX_CLUSTER_SIZE

            if (isNoiseBlob || isMegaCluster) {
                // Perform recursive split, evaluating as we go
                val subClusters = recursiveSplit(indices, points)
                // If multiple new clusters are returned: assign new IDs
                if (subClusters.size > 1) {
                    for (subClusterIndices in subClusters) {
                        debugNumOfClusterSplit++
                        // Skip if the resulting split chunk was too small to be meaningful
                        if (subClusterIndices.size < 3) {
                            subClusterIndices.forEach { newClusterIds[it] = -1 } // Revert to noise
                            debugNumOfClustersReverted++
                        } else {
                            val newClusterId = nextId++
                            for (index in subClusterIndices) {
                                newClusterIds[index] = newClusterId
                            }
                        }
                    }
                }
                // If subClusters.size == 1: no split happened so original IDs are kept
            }
        }
        println("Splits performed: $debugNumOfClusterSplit. Clusters reverted back to noise (<3): $debugNumOfClustersReverted")
        return newClusterIds
    }

    private fun recursiveSplit(indices: List<Int>, points: List<VectorPoint>): List<List<Int>> {
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

        val potentialSplits = (0 until sortedIndices.size - 1).map { i ->
            val gap = projections[i + 1] - projections[i]
            i to gap
        }

        val avgGap = potentialSplits.map { it.second }.average()

        val rankedSplits = potentialSplits.sortedByDescending { it.second }

        val isHuge = indices.size > 50

        for ((splitIndex, gapValue) in rankedSplits) {
            val isFaultLine = if (isHuge)
                gapValue > avgGap else gapValue > (avgGap * 1.2)

            if (!isFaultLine)
                break

            val sizeA = splitIndex + 1
            val sizeB = indices.size - sizeA

            if (sizeA >= 3 && sizeB >= 3) {
                val clusterA = sortedIndices.subList(0, splitIndex + 1)
                val clusterB = sortedIndices.subList(splitIndex + 1, sortedIndices.size)

                return recursiveSplit(clusterA, points) + recursiveSplit(clusterB, points)
            }
        }
        return listOf(indices)
    }

    private const val MERGE_THRESHOLD = 0.15

    fun clusterReMerger(points: List<VectorPoint>, clusterIds: IntArray): IntArray {
        val newClusterIds = clusterIds.clone()

        val clusters = newClusterIds.indices
            .groupBy { newClusterIds[it] }
            .filter { it.key != -1 }
            .toMutableMap()

        val centroids = HashMap<Int, DoubleArray>()
        clusters.keys.forEach { id ->
            val vectors = clusters[id]!!.map { points[it].embedding }
            centroids[id] = vectorMath.calculateCentroid(vectors)
        }

        var debugMergedClusters = 0
        val debugTotalClusters = centroids.size

        var merged = true
        while(merged) {
            merged = false
            val currentIds = clusters.keys.toList()

            pairLoop@ for(i in 0 until currentIds.size) {
                for(j in i + 1 until currentIds.size) {
                    val idA = currentIds[i]
                    val idB = currentIds[j]

                    if (!clusters.containsKey(idA) || !clusters.containsKey(idB))
                        continue

                    val centerA = centroids[idA]!!
                    val centerB = centroids[idB]!!
                    val magA = vectorMath.getMagnitude(centerA)
                    val magB = vectorMath.getMagnitude(centerB)

                    val dist = vectorMath.calculateCosineDistance(centerA, magA, centerB, magB)
                    if (dist < 30) {
                        println("DEBUG: Distance between $idA and $idB is $dist")
                    }

                    if (dist < MERGE_THRESHOLD) {
                        // Merge into A
                        val combinedIndices = clusters[idA]!! + clusters[idB]!!
                        clusters[idA] = combinedIndices

                        // Remove B
                        clusters.remove(idB)
                        centroids.remove(idB)

                        // Recalculate centroid A
                        val combinedVectors = combinedIndices.map { points[it].embedding }
                        centroids[idA] = vectorMath.calculateCentroid(combinedVectors)

                        debugMergedClusters++
                        merged = true
                        break@pairLoop
                    }
                }
            }
        }

        for(i in newClusterIds.indices) {
            if (newClusterIds[i] != -1)
                newClusterIds[i] = -1
        }

        for ((id, indices) in clusters) {
            indices.forEach { newClusterIds[it] = id }
        }

        println("Merged $debugMergedClusters of total $debugTotalClusters clusters")
        return newClusterIds
    }
     */
}