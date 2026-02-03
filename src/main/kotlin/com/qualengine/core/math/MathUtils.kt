package com.qualengine.core.math

import kotlin.collections.iterator
import kotlin.math.pow
import kotlin.math.sqrt

object MathUtils {

    data class Point2D(val x: Double, val y: Double, val originalIndex: Int)

    /**
     * A lightweight implementation of PCA (Principal Component Analysis).
     * projects N-dimensional vectors down to 2D for visualization.
     */
    fun performPCA(vectors: List<List<Double>>): List<Point2D> {
        if (vectors.isEmpty()) return emptyList()
        val dimensions = vectors[0].size
        val n = vectors.size

        // 1. Center the data (Mean Subtraction)
        val means = DoubleArray(dimensions)
        for (v in vectors) {
            for (i in 0 until dimensions) {
                means[i] += v[i]
            }
        }
        for (i in 0 until dimensions) means[i] /= n.toDouble()

        val centered = vectors.map { vec ->
            vec.mapIndexed { i, v -> v - means[i] }
        }

        // 2. Simplified Covariance & Power Iteration (to find Top 2 Principal Components)
        // NOTE: For a production app with 10k+ vectors, use a library like EJML.
        // This is a "good enough" approximation for < 2000 items.

        val pc1 = computePrincipalComponent(centered, dimensions)

        // Remove pc1 from data to find pc2
        val residual = subtractComponent(centered, pc1)
        val pc2 = computePrincipalComponent(residual, dimensions)

        // 3. Project data onto PC1 and PC2
        return vectors.mapIndexed { index, vec ->
            val x = dotProduct(vec, pc1)
            val y = dotProduct(vec, pc2)
            Point2D(x, y, index)
        }
    }

    private fun computePrincipalComponent(data: List<List<Double>>, dim: Int): List<Double> {
        // This used a random start vector before, rotating the map every refresh.
        var guess = List(dim) { 1.0 }
        guess = normalize(guess)

        // Power Iteration to converge on the dominant eigenvector
        for (iter in 0 until 10) { // 10 iterations is usually enough for vis
            val nextGuess = DoubleArray(dim)

            for (row in data) {
                val dot = dotProduct(row, guess)
                for (i in 0 until dim) {
                    nextGuess[i] += row[i] * dot
                }
            }
            guess = normalize(nextGuess.toList())
        }
        return guess
    }

    private fun subtractComponent(data: List<List<Double>>, component: List<Double>): List<List<Double>> {
        return data.map { row ->
            val projection = dotProduct(row, component)
            row.mapIndexed { i, v -> v - (component[i] * projection) }
        }
    }

    private fun dotProduct(a: List<Double>, b: List<Double>): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun normalize(v: List<Double>): List<Double> {
        val mag = sqrt(v.sumOf { it.pow(2) })
        return if (mag == 0.0) v else v.map { it / mag }
    }
}

object ClusterUtils {

    data class ClusterResult(
        val clusterIds: IntArray,
        // We removed 'clusterCenters' from here because DBSCAN
        // doesn't know about 2D screen coordinates anymore.
    )

    /**
     * High-Dimensional DBSCAN.
     * Uses Cosine Distance (ideal for AI Embeddings).
     * * @param vectors The raw 384-dimensional embeddings from the DB.
     * @param epsilon Distance threshold (0.0 = identical, 0.2 = close, 1.0 = orthogonal).
     * @param minPoints Minimum items to form a cluster.
     */
    fun runDBSCAN(vectors: List<List<Double>>, epsilon: Double = 0.17, minPoints: Int = 4): ClusterResult {
        val n = vectors.size
        val labels = IntArray(n) { 0 } // 0 = undefined, -1 = noise, >0 = cluster
        var clusterId = 0

        // Optimization: Pre-calculate vector magnitudes to speed up distance checks
        val magnitudes = vectors.map { vec ->
            Math.sqrt(vec.sumOf { it * it })
        }

        for (i in 0 until n) {
            if (labels[i] != 0) continue // Already visited

            // Find neighbors using High-Dimensional Cosine Distance
            val neighbors = getNeighbors(vectors, magnitudes, i, epsilon)

            if (neighbors.size < minPoints) {
                labels[i] = -1 // Mark as Noise
            } else {
                clusterId++ // Start new cluster
                expandCluster(vectors, magnitudes, labels, i, neighbors, clusterId, epsilon, minPoints)
            }
        }

        return ClusterResult(labels)
    }

    private fun expandCluster(
        vectors: List<List<Double>>,
        magnitudes: List<Double>,
        labels: IntArray,
        pIndex: Int,
        neighbors: MutableList<Int>,
        clusterId: Int,
        epsilon: Double,
        minPoints: Int
    ) {
        labels[pIndex] = clusterId

        var i = 0
        while (i < neighbors.size) {
            val neighbor = neighbors[i]

            // If it was Noise, it's now a Border Point of this cluster
            if (labels[neighbor] == -1) {
                labels[neighbor] = clusterId
            }

            // If it was Undefined, process it
            if (labels[neighbor] == 0) {
                labels[neighbor] = clusterId

                // Check if this neighbor is also a Core Point
                val newNeighbors = getNeighbors(vectors, magnitudes, neighbor, epsilon)
                if (newNeighbors.size >= minPoints) {
                    neighbors.addAll(newNeighbors)
                }
            }
            i++
        }
    }

    private fun getNeighbors(
        vectors: List<List<Double>>,
        magnitudes: List<Double>,
        index: Int,
        eps: Double
    ): MutableList<Int> {
        val neighbors = mutableListOf<Int>()
        val v1 = vectors[index]
        val mag1 = magnitudes[index]

        for (i in vectors.indices) {
            if (i == index) continue

            // Calculate Cosine Distance
            val dist = calculateCosineDistance(v1, mag1, vectors[i], magnitudes[i])

            if (dist <= eps) {
                neighbors.add(i)
            }
        }
        return neighbors
    }

    /**
     * Calculates Cosine Distance (1 - Similarity).
     * Returns 0.0 if identical, 1.0 if orthogonal, 2.0 if opposite.
     */
    private fun calculateCosineDistance(v1: List<Double>, mag1: Double, v2: List<Double>, mag2: Double): Double {
        if (mag1 == 0.0 || mag2 == 0.0) return 1.0 // Safety check

        var dot = 0.0
        // Manual loop is faster than built-in zip/sum for huge arrays
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
        }

        val similarity = dot / (mag1 * mag2)

        // Clamp to handle floating point errors (e.g. 1.0000000002)
        val clampedSim = similarity.coerceIn(-1.0, 1.0)

        return 1.0 - clampedSim
    }

    fun normalizePointsForClustering(points: List<MathUtils.Point2D>): List<MathUtils.Point2D> {
        if (points.isEmpty()) return points

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        val rangeX = (maxX - minX).coerceAtLeast(0.0001)
        val rangeY = (maxY - minY).coerceAtLeast(0.0001)

        return points.map { p ->
            MathUtils.Point2D(
                x = (p.x - minX) / rangeX,
                y = (p.y - minY) / rangeY,
                originalIndex = p.originalIndex
            )
        }
    }

    // Add this to ClusterUtils object
    fun assignOrphansToNearestCluster(
        vectors: List<List<Double>>,
        clusterIds: IntArray,
        maxDistance: Double = 0.25
    ) {
        // 1. Calculate the "Average Vector" (Centroid) for each valid cluster
        val centroids = mutableMapOf<Int, List<Double>>()
        val counts = mutableMapOf<Int, Int>()

        // Sum up vectors for each cluster
        for (i in vectors.indices) {
            val id = clusterIds[i]
            if (id == -1) continue

            val currentSum = centroids.getOrDefault(id, List(384) { 0.0 })
            centroids[id] = currentSum.zip(vectors[i]) { a, b -> a + b }
            counts[id] = counts.getOrDefault(id, 0) + 1
        }

        // Average them
        val finalCentroids = centroids.mapValues { (id, sum) ->
            val count = counts[id]!!
            sum.map { it / count }
        }

        // pre-calculate magnitudes for the centroids to speed up the loop
        val centroidMagnitudes = finalCentroids.mapValues { (_, vec) ->
            Math.sqrt(vec.sumOf { it * it })
        }
        val vectorMagnitudes = vectors.map { vec -> Math.sqrt(vec.sumOf { it * it }) }

        // 2. Loop through Orphans (Noise points)
        var adoptedCount = 0
        for (i in clusterIds.indices) {
            if (clusterIds[i] == -1) {

                // Find closest cluster
                var bestCluster = -1
                var bestDist = Double.MAX_VALUE

                for ((id, centerVec) in finalCentroids) {
                    val dist = calculateCosineDistance(
                        vectors[i], vectorMagnitudes[i],
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
        println("DEBUG: Adopted $adoptedCount orphans into clusters.")
    }

    fun createArchipelagoLayout(
        rawPoints: List<MathUtils.Point2D>,
        clusterIds: IntArray,
        ignored: Int
    ): Pair<List<MathUtils.Point2D>, Map<Int, MathUtils.Point2D>> {

        // 1. Identify Valid Clusters
        val validClusters = clusterIds.filter { it != -1 }.distinct().sorted()
        val count = validClusters.size

        if (count == 0) return Pair(rawPoints, emptyMap())

        // 2. GRID MATH (The "Muffin Tin")
        // Calculate rows/cols to fit screen aspect ratio (roughly square or landscape)
        val cols = Math.ceil(Math.sqrt(count.toDouble())).toInt()
        val rows = Math.ceil(count.toDouble() / cols).toInt()

        val cellWidth = 1.0 / cols
        val cellHeight = 1.0 / rows

        // ISLAND SIZE: fit inside the smaller dimension of the cell
        // 0.45 = 90% of the half-width (leaves a 10% gap between islands)
        val maxIslandRadius = Math.min(cellWidth, cellHeight) * 0.40

        // 3. Define Island Centers
        val islandCenters = mutableMapOf<Int, MathUtils.Point2D>()
        validClusters.forEachIndexed { index, clusterId ->
            val col = index % cols
            val row = index / cols

            val cx = (col * cellWidth) + (cellWidth / 2)
            val cy = (row * cellHeight) + (cellHeight / 2)

            islandCenters[clusterId] = MathUtils.Point2D(cx, cy, -1)
        }

        // 4. Map Points (Gravity & Compression)
        val newPoints = rawPoints.map { MathUtils.Point2D(it.x, it.y, it.originalIndex) }.toMutableList()

        validClusters.forEach { clusterId ->
            val indices = rawPoints.indices.filter { clusterIds[it] == clusterId }
            if (indices.isEmpty()) return@forEach

            // A. Centroid (Center of Mass)
            val sumX = indices.sumOf { rawPoints[it].x }
            val sumY = indices.sumOf { rawPoints[it].y }
            val centroidX = sumX / indices.size
            val centroidY = sumY / indices.size

            // B. Furthest Point (Current Radius)
            var currentRadius = 0.0
            indices.forEach { i ->
                val dx = rawPoints[i].x - centroidX
                val dy = rawPoints[i].y - centroidY
                val dist = Math.sqrt(dx*dx + dy*dy)
                if (dist > currentRadius) currentRadius = dist
            }
            if (currentRadius < 0.001) currentRadius = 0.001

            // C. Move & Compress
            val islandCenter = islandCenters[clusterId]!!
            val baseScale = maxIslandRadius / currentRadius

            indices.forEach { i ->
                val original = rawPoints[i]
                val vecX = original.x - centroidX
                val vecY = original.y - centroidY

                // 1. Scale to fit
                var finalVecX = vecX * baseScale
                var finalVecY = vecY * baseScale

                // 2. GRAVITY (Pull inwards)
                // We pull everyone 10% closer to center to create a dense core
                finalVecX *= 0.9
                finalVecY *= 0.9

                // 3. HARD CLAMP (The "Electric Fence")
                // If a point is still outside the circle, drag it back to the edge.
                val dist = Math.sqrt(finalVecX*finalVecX + finalVecY*finalVecY)
                if (dist > maxIslandRadius) {
                    val clampRatio = maxIslandRadius / dist
                    finalVecX *= clampRatio
                    finalVecY *= clampRatio
                }

                newPoints[i] = MathUtils.Point2D(
                    islandCenter.x + finalVecX,
                    islandCenter.y + finalVecY,
                    original.originalIndex
                )
            }
        }

        return Pair(newPoints, islandCenters)
    }
}