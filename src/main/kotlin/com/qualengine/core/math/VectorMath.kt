package com.qualengine.core.math

import kotlin.math.pow
import kotlin.math.sqrt

object VectorMath {
    /**
     * Calculates Cosine Distance (1 - Similarity).
     * Returns 0.0 if identical, 1.0 if orthogonal, 2.0 if opposite.
     */
    fun calculateCosineDistance(
        v1: DoubleArray, mag1: Double,
        v2: DoubleArray, mag2: Double)
    : Double {
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

    fun dotProduct(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    fun getMagnitude(a: DoubleArray): Double {
        var sum = 0.0
        for (v in a) {
            sum += v * v
        }
        return sqrt(sum)
    }

    fun blend(child: DoubleArray, parent: DoubleArray, childWeight: Double = 0.8): DoubleArray {
        // Check dimensions first (now: nomic-embed-text 768)
        if (child.size != parent.size)
            return child

        val parentWeight = 1.0 - childWeight
        val result = DoubleArray(child.size)

        for (i in child.indices) {
            result[i] = (child[i] * childWeight) + (parent[i] * parentWeight)
        }

        return result
    }
}