package com.qualengine.logic

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
        val clusterCenters: Map<Int, MathUtils.Point2D>
    )

    /**
     * DBSCAN (Density-Based Spatial Clustering).
     * @param points The 2D points from your map.
     * @param epsilon How close points must be to touch (0.0 - 1.0). Try 0.05 to start.
     * @param minPoints Minimum points to form a valid cluster (e.g., 3).
     */
    fun runDBSCAN(points: List<MathUtils.Point2D>, epsilon: Double = 0.08, minPoints: Int = 3): ClusterResult {
        val n = points.size
        val labels = IntArray(n) { 0 } // 0 = undefined
        var clusterId = 0

        for (i in 0 until n) {
            if (labels[i] != 0) continue // Already visited

            val neighbors = getNeighbors(points, i, epsilon)

            if (neighbors.size < minPoints) {
                labels[i] = -1 // Noise
            } else {
                clusterId++ // Start new cluster
                expandCluster(points, labels, i, neighbors, clusterId, epsilon, minPoints)
            }
        }

        // Calculate Centers for labels later
        val centers = calculateCentroids(points, labels)

        return ClusterResult(labels, centers)
    }

    private fun expandCluster(
        points: List<MathUtils.Point2D>,
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

            if (labels[neighbor] == -1) {
                labels[neighbor] = clusterId // Change Noise to Border Point
            }

            if (labels[neighbor] == 0) {
                labels[neighbor] = clusterId
                val newNeighbors = getNeighbors(points, neighbor, epsilon)
                if (newNeighbors.size >= minPoints) {
                    neighbors.addAll(newNeighbors)
                }
            }
            i++
        }
    }

    private fun getNeighbors(points: List<MathUtils.Point2D>, index: Int, eps: Double): MutableList<Int> {
        val neighbors = mutableListOf<Int>()
        val p1 = points[index]
        for (i in points.indices) {
            if (i == index) continue
            val p2 = points[i]
            val dist = sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))
            if (dist <= eps) {
                neighbors.add(i)
            }
        }
        return neighbors
    }

    private fun calculateCentroids(points: List<MathUtils.Point2D>, labels: IntArray): Map<Int, MathUtils.Point2D> {
        val sums = mutableMapOf<Int, Pair<Double, Double>>()
        val counts = mutableMapOf<Int, Int>()

        for (i in points.indices) {
            val id = labels[i]
            if (id == -1) continue // Ignore noise

            val (sumX, sumY) = sums.getOrDefault(id, 0.0 to 0.0)
            sums[id] = (sumX + points[i].x) to (sumY + points[i].y)
            counts[id] = counts.getOrDefault(id, 0) + 1
        }

        return sums.mapValues { (id, sum) ->
            val count = counts[id]!!
            MathUtils.Point2D(sum.first / count, sum.second / count, -1)
        }
    }

    fun normalizePointsForClustering(points: List<MathUtils.Point2D>): List<MathUtils.Point2D> {
        if (points.isEmpty())
            return points

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
}