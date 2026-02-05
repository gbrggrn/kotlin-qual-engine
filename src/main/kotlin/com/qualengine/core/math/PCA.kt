package com.qualengine.core.math

import com.qualengine.data.model.VectorPoint

object PCA {

    fun performPCA(points: List<VectorPoint>): List<VectorPoint> {
        val vectors = points.map { it.embedding }
        if (vectors.isEmpty()) return emptyList()

        val dimensions = vectors[0].size
        val n = vectors.size

        // --- STEP 1: CONTEXT ERASURE (Local Mean) ---
        val means = DoubleArray(dimensions)
        for (v in vectors) {
            for (i in 0 until dimensions) means[i] += v[i]
        }
        for (i in 0 until dimensions) means[i] /= n.toDouble()

        val centered = vectors.map { v ->
            DoubleArray(dimensions) { i -> v[i] - means[i] }
        }

        // --- STEP 2: FIND AXES OF DIFFERENCE ---
        val pc1 = powerIteration(centered, dimensions)

        // Deflate to find the second axis
        val residuals = centered.map { vec ->
            val proj = VectorMath.dotProduct(vec, pc1)
            DoubleArray(dimensions) { i -> vec[i] - (proj * pc1[i]) }
        }
        val pc2 = powerIteration(residuals, dimensions)

        // --- STEP 3: CONTRASTIVE PROJECTION ---
        val rawProjections = points.mapIndexed { i, _ ->
            val vec = centered[i]
            val x = VectorMath.dotProduct(vec, pc1)
            val y = VectorMath.dotProduct(vec, pc2)
            x to y
        }

        // Find the specific range of THIS subset's differences
        val minX = rawProjections.minOf { it.first }
        val maxX = rawProjections.maxOf { it.first }
        val minY = rawProjections.minOf { it.second }
        val maxY = rawProjections.maxOf { it.second }

        val rangeX = (maxX - minX).coerceAtLeast(1e-9)
        val rangeY = (maxY - minY).coerceAtLeast(1e-9)

        // --- STEP 4: BLOOM TO UNIT SQUARE ---
        return points.mapIndexed { i, p ->
            val (rx, ry) = rawProjections[i]
            p.copy(
                projectedX = (rx - minX) / rangeX,
                projectedY = (ry - minY) / rangeY
            )
        }
    }

    // --- HELPER: The "Magic" Math Loop ---
    // Finds the dominant eigenvector without building a giant covariance matrix.
    private fun powerIteration(data: List<DoubleArray>, dimensions: Int): DoubleArray {
        // 1. Start with fixed seed (deterministic start)
        val fixedRandom = kotlin.random.Random(42)
        val candidate = DoubleArray(dimensions) { fixedRandom.nextDouble() - 0.5 }
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