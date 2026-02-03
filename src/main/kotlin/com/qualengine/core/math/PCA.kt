package com.qualengine.core.math

import com.qualengine.core.math.VectorMath
import com.qualengine.data.model.VectorPoint
import kotlin.random.Random

object PCA {

    fun performPCA(points: List<VectorPoint>): List<VectorPoint> {
        val vectors = points.map { it.embedding }
        if (vectors.isEmpty()) return emptyList()

        val dimensions = vectors[0].size
        val n = vectors.size

        // --- STEP 1: Center the Data ---
        // Calculate Mean Vector
        val means = DoubleArray(dimensions)
        for (v in vectors) {
            for (i in 0 until dimensions) {
                means[i] += v[i]
            }
        }
        for (i in 0 until dimensions) means[i] /= n.toDouble()

        // Create Centered Vectors (Element-wise subtraction)
        val centered = vectors.map { v ->
            val newVec = DoubleArray(dimensions)
            for (i in 0 until dimensions) {
                newVec[i] = v[i] - means[i]
            }
            newVec
        }

        // --- STEP 2: Find First Principal Component (PC1) ---
        // We use Power Iteration: Repeatedly multiply a random vector by the data
        // until it aligns with the direction of greatest variance.
        val pc1 = powerIteration(centered, dimensions)

        // --- STEP 3: Find Second Principal Component (PC2) ---
        // Remove PC1's influence from the data (Deflation) to find the next variance
        val residuals = centered.map { vec ->
            val projection = VectorMath.dotProduct(vec, pc1)
            val newVec = DoubleArray(dimensions)
            for (i in 0 until dimensions) {
                newVec[i] = vec[i] - (projection * pc1[i])
            }
            newVec
        }

        val pc2 = powerIteration(residuals, dimensions)

        // --- STEP 4: Project and Return ---
        // Dot product projects the high-dim vector onto the 2D PC axes
        return points.mapIndexed { index, originalPoint ->
            val vec = centered[index] // Use centered data for projection
            val x = VectorMath.dotProduct(vec, pc1)
            val y = VectorMath.dotProduct(vec, pc2)

            // Return updated point (Assume VectorPoint is immutable copy)
            originalPoint.copy(
                projectedX = x,
                projectedY = y
            )
        }
    }

    // --- HELPER: The "Magic" Math Loop ---
    // Finds the dominant eigenvector without building a giant covariance matrix.
    private fun powerIteration(data: List<DoubleArray>, dimensions: Int): DoubleArray {
        // 1. Start with a random vector
        val candidate = DoubleArray(dimensions) { Random.nextDouble() - 0.5 }
        normalize(candidate)

        // 2. Iterate until it stops changing (converges)
        // Usually takes 5-20 steps for decent approximation
        val iterations = 20

        for (iter in 0 until iterations) {
            // Matrix Multiplication trick: C * v  ~  X^T * (X * v)
            // Step A: temp = X * candidate (Project candidate onto all data points)
            val scores = DoubleArray(data.size)
            for (i in data.indices) {
                scores[i] = VectorMath.dotProduct(data[i], candidate)
            }

            // Step B: newCandidate = X^T * scores (Weighted sum of all data points)
            val newCandidate = DoubleArray(dimensions)
            for (i in data.indices) {
                val score = scores[i]
                val vec = data[i]
                for (d in 0 until dimensions) {
                    newCandidate[d] += vec[d] * score
                }
            }

            // Step C: Normalize to keep length = 1.0
            normalize(newCandidate)

            // Update candidate
            System.arraycopy(newCandidate, 0, candidate, 0, dimensions)
        }

        return candidate
    }

    private fun normalize(vec: DoubleArray) {
        val mag = VectorMath.getMagnitude(vec)
        if (mag > 0.000001) {
            for (i in vec.indices) {
                vec[i] /= mag
            }
        }
    }
}