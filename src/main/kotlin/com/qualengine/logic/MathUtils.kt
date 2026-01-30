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