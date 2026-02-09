package com.qualengine.core.clustering

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.VectorPoint
import com.qualengine.data.model.VirtualPoint
import java.util.Random
import kotlin.collections.emptyMap
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object LayoutEngine {

    private val vectorMath = DependencyRegistry.vectorMath

    // CONFIGURATION
    private const val ITERATIONS = 300
    private const val REPULSION_STRENGTH = 500.0
    private const val ATTRACTION_STRENGTH = 0.8
    private const val DAMPING = 0.9
    private const val CLUSTER_RADIUS = 40.0

    // Helper for internal math (since VirtualPoint requires ID/Radius)
    private data class Velocity(var x: Double, var y: Double)

    /**
     * Calculates the 2D positions for each Cluster ID based on semantic similarity.
     * Returns VirtualPoints with RAW (unbounded) physics coordinates.
     */
    fun computeLayout(
        points: List<VectorPoint>,
        clusterIds: IntArray
    ): Map<Int, VirtualPoint> {

        // 1. Prepare Nodes (Calculate Centroids)
        val clusters = clusterIds.indices
            .groupBy { clusterIds[it] }
            .filter { it.key != -1 } // Ignore noise

        val nodeIds = clusters.keys.toList()
        val nodeCount = nodeIds.size

        // Calculate normalized centroids for cosine similarity
        val centroids = nodeIds.associateWith { id ->
            val indices = clusters[id]!!
            val vectors = indices.map { points[it].embedding }
            val raw = vectorMath.calculateCentroid(vectors)
            normalize(raw)
        }

        // 2. Initialize Positions (Random Scatter)
        // We use VirtualPoint to hold the position state
        val rng = Random()
        val positions = nodeIds.associateWith { id ->
            VirtualPoint(
                clusterId = id,
                x = rng.nextDouble() * 1000 - 500,
                y = rng.nextDouble() * 1000 - 500,
                radius = CLUSTER_RADIUS, // Placeholder, updated later by normalization logic
                theme = "Processing..."
            )
        }

        // Initialize Velocities (Internal helper)
        val velocities = nodeIds.associateWith { Velocity(0.0, 0.0) }

        // 3. Pre-calculate Similarities (The "Springs")
        val similarityMatrix = Array(nodeCount) { DoubleArray(nodeCount) }
        for (i in 0 until nodeCount) {
            for (j in i + 1 until nodeCount) {
                val idA = nodeIds[i]
                val idB = nodeIds[j]

                val vecA = centroids[idA]!!
                val vecB = centroids[idB]!!

                val sim = vectorMath.dotProduct(vecA, vecB)

                // Only link if similarity is relevant (> 0.5)
                // Square it to emphasize strong connections
                val weight = if (sim > 0.5) sim.pow(2) else 0.0

                similarityMatrix[i][j] = weight
                similarityMatrix[j][i] = weight
            }
        }

        // 4. Run Physics Simulation
        repeat(ITERATIONS) {

            // A. Repulsion (Coulomb's Law)
            for (i in 0 until nodeCount) {
                val idA = nodeIds[i]
                val posA = positions[idA]!!
                val velA = velocities[idA]!!

                var fx = 0.0
                var fy = 0.0

                for (j in 0 until nodeCount) {
                    if (i == j) continue
                    val idB = nodeIds[j]
                    val posB = positions[idB]!!

                    val dx = posA.x - posB.x
                    val dy = posA.y - posB.y
                    val distSq = dx * dx + dy * dy
                    val dist = sqrt(distSq).coerceAtLeast(0.1)

                    val force = REPULSION_STRENGTH / dist
                    fx += (dx / dist) * force
                    fy += (dy / dist) * force
                }

                velA.x += fx
                velA.y += fy
            }

            // B. Attraction (Hooke's Law)
            for (i in 0 until nodeCount) {
                val idA = nodeIds[i]
                val posA = positions[idA]!!
                val velA = velocities[idA]!!

                for (j in 0 until nodeCount) {
                    if (i == j) continue
                    val weight = similarityMatrix[i][j]
                    if (weight <= 0.0) continue

                    val idB = nodeIds[j]
                    val posB = positions[idB]!!

                    val dx = posB.x - posA.x
                    val dy = posB.y - posA.y

                    velA.x += dx * weight * ATTRACTION_STRENGTH
                    velA.y += dy * weight * ATTRACTION_STRENGTH
                }
            }

            // C. Gravity (Center Pull) & Update
            for (id in nodeIds) {
                val pos = positions[id]!!
                val vel = velocities[id]!!

                // Gravity
                vel.x -= pos.x * 0.005
                vel.y -= pos.y * 0.005

                // Damping (Friction)
                vel.x *= DAMPING
                vel.y *= DAMPING

                // Speed Limit
                val speed = sqrt(vel.x * vel.x + vel.y * vel.y)
                if (speed > 50.0) {
                    vel.x = (vel.x / speed) * 50.0
                    vel.y = (vel.y / speed) * 50.0
                }

                // Apply
                pos.x += vel.x
                pos.y += vel.y
            }
        }

        // Physics got us 90% there. Now we force circles apart if they are touching.
        val collisionIterations = 50
        val nodeValues = positions.values.toList()

        repeat(collisionIterations) {
            for (i in nodeValues.indices) {
                val n1 = nodeValues[i]
                for (j in i + 1 until nodeValues.size) {
                    val n2 = nodeValues[j]

                    val dx = n1.x - n2.x
                    val dy = n1.y - n2.y
                    val distSq = dx * dx + dy * dy
                    val dist = sqrt(distSq)

                    // We want them at least (Radius1 + Radius2) apart + Padding
                    val minDist = n1.radius + n2.radius + 15.0

                    if (dist < minDist && dist > 0.001) {
                        // Calculate how much they overlap
                        val overlap = minDist - dist

                        // Push them apart proportional to the overlap
                        // Each node moves half the overlap distance away
                        val moveX = (dx / dist) * overlap * 0.5
                        val moveY = (dy / dist) * overlap * 0.5

                        n1.x += moveX
                        n1.y += moveY
                        n2.x -= moveX
                        n2.y -= moveY
                    }
                }
            }
        }

        return positions
    }

    private fun normalize(v: DoubleArray): DoubleArray {
        var sum = 0.0
        for (d in v) sum += d * d
        val mag = sqrt(sum)
        if (mag == 0.0) return v
        return DoubleArray(v.size) { i -> v[i] / mag }
    }

    /*
    fun createGalaxyLayout(
        rawPoints: List<VectorPoint>,
        clusterIds: IntArray
    ): Pair<List<VectorPoint>, Map<Int, VirtualPoint>> {
        val validClusters = clusterIds.filter { it != -1 }.distinct().sorted()
        if (validClusters.isEmpty()) {
            println("WARNING: No clusters found. Defaulting to raw PCA layout.")
            // If no clusters, just return the PCA points normalized to fill the screen
            // so at least they aren't a tiny dot in the middle.
            return LayoutEngine.normalizeEverything(rawPoints, emptyMap())
        }
        val anchors = mutableMapOf<Int, VirtualPoint>()

        // 1. PIN ANCHORS: Use 0.1 to 0.9 to stay within the canvas
        validClusters.forEachIndexed { index, id ->
            val angle = (2.0 * Math.PI * index) / validClusters.size
            // We force them away from the center (0.5)
            anchors[id] = VirtualPoint(
                id,
                x = 0.5 + 0.35 * cos(angle),
                y = 0.5 + 0.35 * sin(angle),
                radius = 0.12
            )
        }

        // 2. ATTACH POINTS: Map them to their new island "home"
        val finalPoints = rawPoints.map { p ->
            val anchor = anchors[p.clusterId] ?: return@map p

            // Take the PCA variance and scale it to be a local "cloud"
            // This prevents the global scale from squashing them
            val localX = (p.projectedX - 0.5) * 0.3
            val localY = (p.projectedY - 0.5) * 0.3

            p.copy(
                projectedX = anchor.x + localX,
                projectedY = anchor.y + localY
            )
        }

        // CRITICAL: DO NOT CALL normalizeEverything here.
        // We want the raw 0.1 - 0.9 coordinates we just made.
        return Pair(finalPoints, anchors)
    }

    fun normalizeEverything(
        points: List<VectorPoint>,
        virtualCenterPoints: Map<Int, VirtualPoint>
    ): Pair<List<VectorPoint>, Map<Int, VirtualPoint>> {
        if (points.isEmpty()) return points to virtualCenterPoints

        val minX = points.minOf { it.projectedX }
        val maxX = points.maxOf { it.projectedX }
        val minY = points.minOf { it.projectedY }
        val maxY = points.maxOf { it.projectedY }

        val rangeX = (maxX - minX).coerceAtLeast(0.0001)
        val rangeY = (maxY - minY).coerceAtLeast(0.0001)

        // Use the MAX range to keep things proportional
        val maxRange = maxOf(rangeX, rangeY)
        val margin = 0.1 // 10% padding so dots don't hit the canvas edge

        val zoomedPoints = points.map { p ->
            // Center the smaller dimension
            val offsetX = (maxRange - rangeX) / 2
            val offsetY = (maxRange - rangeY) / 2

            val nx = (p.projectedX - minX + offsetX) / maxRange
            val ny = (p.projectedY - minY + offsetY) / maxRange

            p.copy(
                projectedX = margin + nx * (1.0 - 2 * margin),
                projectedY = margin + ny * (1.0 - 2 * margin)
            )
        }

        // Apply identical transformation to anchors
        val zoomedVirtualCenterPoints = virtualCenterPoints.mapValues { (_, a) ->
            val offsetX = (maxRange - rangeX) / 2
            val offsetY = (maxRange - rangeY) / 2

            val nx = (a.x - minX + offsetX) / maxRange
            val ny = (a.y - minY + offsetY) / maxRange

            a.copy(
                x = margin + nx * (1.0 - 2 * margin),
                y = margin + ny * (1.0 - 2 * margin),
                radius = a.radius / maxRange * (1.0 - 2 * margin)
            )
        }

        return Pair(zoomedPoints, zoomedVirtualCenterPoints)
    }
     */
}