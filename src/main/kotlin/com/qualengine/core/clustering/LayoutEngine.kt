package com.qualengine.core.clustering

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.VectorPoint
import com.qualengine.data.model.VirtualPoint
import java.util.Random
import kotlin.math.*

object LayoutEngine {

    private val vectorMath = DependencyRegistry.vectorMath

    // === TUNING ===
    private const val PHYSICS_ITERATIONS = 300
    private const val CLUSTER_RADIUS = 50.0
    private const val CORE_PADDING = 10.0      // Gap inside the blob
    private const val MOAT_SIZE = 300.0        // The empty space between Blob and Outliers

    // What % of clusters count as the "Dense Core"?
    // 0.6 = The closest 60% of clusters are treated as the blob.
    private const val CORE_RATIO = 0.6

    private class PhysicsNode(
        val id: Int,
        var x: Double,
        var y: Double,
        var vx: Double = 0.0,
        var vy: Double = 0.0,
        val radius: Double
    )

    data class LayoutResult(
        val positions: Map<Int, VirtualPoint> = emptyMap(),
        val coreIds: Set<Int> = emptySet(),
        val outlierIds: List<Set<Int>> = emptyList()
    )

    fun computeLayout(
        points: List<VectorPoint>,
        clusterIds: IntArray
    ): LayoutResult {

        // 1. INITIALIZE (Random Scatter)
        // We start random so the physics can find the TRUE relative positions.
        val nodes = initializeNodesRandomly(clusterIds)
        if (nodes.isEmpty())
            return LayoutResult()

        // 2. INTELLIGENCE (Centroids & Similarity)
        val centroids = calculateCentroids(points, clusterIds, nodes.keys)
        val similarityMatrix = calculateSimilarityMatrix(nodes.keys, centroids)

        // 3. PHASE 1: NATURAL PHYSICS
        // Let the clusters find their natural semantic positions (Blob Right / Outliers Left)
        val nodeList = nodes.values.toList()
        repeat(PHYSICS_ITERATIONS) {
            applyForces(nodeList, similarityMatrix)
            updatePositions(nodeList)
        }

        // 4. PHASE 2: CORE INFLATION (The "Magnifying Glass")
        // Now that we know WHERE they are, we fix the CROWDING.
        val (coreIds, outlierNodes) = inflateCoreAndPushOutliers(nodeList)

        val outlierIds = identifyIslands(outlierNodes, gapThreshold = 300.0)

        // 5. PHASE 3: FINAL RESOLVE
        // Ensure nothing is overlapping after the shift
        resolveCollisions(nodeList)

        val positionsMap = nodes.mapValues { (id, node) ->
            VirtualPoint(id, node.x, node.y, node.radius, "Processing")
        }

        return LayoutResult(
            positions = positionsMap,
            coreIds = coreIds,
            outlierIds = outlierIds
        )
    }

    // ========================================================
    // THE NEW LOGIC: INFLATION
    // ========================================================
    private fun inflateCoreAndPushOutliers(nodes: List<PhysicsNode>): Pair<Set<Int>, List<PhysicsNode>> {
        // 1. Find the Universe Center
        val centerX = nodes.map { it.x }.average()
        val centerY = nodes.map { it.y }.average()

        // 2. Sort by distance from center to identify Core vs Outliers
        val sortedByDist = nodes.sortedBy {
            val dx = it.x - centerX
            val dy = it.y - centerY
            dx * dx + dy * dy
        }

        val splitIndex = (nodes.size * CORE_RATIO).toInt().coerceAtLeast(1)
        val coreNodes = sortedByDist.take(splitIndex)
        val outlierNodes = sortedByDist.drop(splitIndex)

        // 3. Measure the Core's Natural Size
        // How far out does the blob currently extend?
        val currentCoreRadius = coreNodes.maxOfOrNull {
            hypot(it.x - centerX, it.y - centerY)
        } ?: 100.0

        // 4. Calculate Required Size
        // If we packed them perfectly, how much space do we actually need?
        // Area = Count * (Radius + Padding)^2
        val requiredArea = coreNodes.size * (CLUSTER_RADIUS * 2 + CORE_PADDING).pow(2)
        val requiredRadius = sqrt(requiredArea) * 1.2 // Add 20% breathing room

        // 5. Calculate Scale Factor
        // "We need to be 3x bigger"
        val scaleFactor = (requiredRadius / currentCoreRadius).coerceAtLeast(1.0)

        // 6. APPLY TRANSFORMATION

        // A. EXPAND THE CORE
        // We push core nodes out to give them space, but keep relative angles.
        for (node in coreNodes) {
            val dx = node.x - centerX
            val dy = node.y - centerY

            // Linear expansion
            node.x = centerX + (dx * scaleFactor)
            node.y = centerY + (dy * scaleFactor)
        }

        // B. PUSH THE OUTLIERS
        // We push them so they stay OUTSIDE the new core radius + MOAT
        val newCoreEdge = requiredRadius

        for (node in outlierNodes) {
            val dx = node.x - centerX
            val dy = node.y - centerY
            val dist = hypot(dx, dy)
            val angle = atan2(dy, dx)

            // The outlier's new distance is:
            // The New Core Edge + The Moat + Its Original "Extra" Distance
            val originalGap = (dist - currentCoreRadius).coerceAtLeast(0.0)
            val newDist = newCoreEdge + MOAT_SIZE + (originalGap * 1.5) // 1.5x to exaggerate distance slightly

            node.x = centerX + cos(angle) * newDist
            node.y = centerY + sin(angle) * newDist
        }

        val coreIds = coreNodes.map { it.id }.toSet()
        return Pair(coreIds, outlierNodes)
    }

    // Group nodes into islands based on spatial proximity
    private fun identifyIslands(nodes: List<PhysicsNode>, gapThreshold: Double): List<Set<Int>> {
        val visited = mutableSetOf<Int>()
        val islands = mutableListOf<Set<Int>>()

        for (node in nodes) {
            if (node.id in visited) continue

            // Start a new Island
            val currentIsland = mutableSetOf<Int>()
            val queue = ArrayDeque<PhysicsNode>()
            queue.add(node)
            visited.add(node.id)

            while (!queue.isEmpty()) {
                val current = queue.removeFirst()
                currentIsland.add(current.id)

                // Find neighbors
                for (other in nodes) {
                    if (other.id !in visited) {
                        val dist = hypot(current.x - other.x, current.y - other.y)
                        // If visually close enough to be considered a "Group"
                        if (dist < gapThreshold) {
                            visited.add(other.id)
                            queue.add(other)
                        }
                    }
                }
            }
            islands.add(currentIsland)
        }
        return islands
    }


    // ========================================================
    // STANDARD PHYSICS (Preserved)
    // ========================================================
    private fun initializeNodesRandomly(ids: IntArray): Map<Int, PhysicsNode> {
        val validIds = ids.filter { it != -1 }.distinct()
        val rng = Random(123) // Deterministic Seed for "Left is Left" consistency
        return validIds.associateWith { id ->
            PhysicsNode(
                id,
                (rng.nextDouble() - 0.5) * 100.0,
                (rng.nextDouble() - 0.5) * 100.0,
                radius = CLUSTER_RADIUS
            )
        }
    }

    private fun applyForces(nodes: List<PhysicsNode>, similarityMatrix: Map<Int, Map<Int, Double>>) {
        for (i in nodes.indices) {
            val n1 = nodes[i]
            // Gravity
            n1.vx -= n1.x * 0.01
            n1.vy -= n1.y * 0.01

            for (j in i + 1 until nodes.size) {
                val n2 = nodes[j]
                val dx = n1.x - n2.x
                val dy = n1.y - n2.y
                val distSq = dx*dx + dy*dy
                val dist = sqrt(distSq).coerceAtLeast(1.0)
                val sim = similarityMatrix[n1.id]!![n2.id] ?: 0.0

                // Repulsion
                val repulsion = (800.0 * (1.0 - sim)) / dist
                n1.vx += (dx/dist) * repulsion
                n1.vy += (dy/dist) * repulsion
                n2.vx -= (dx/dist) * repulsion
                n2.vy -= (dy/dist) * repulsion

                // Attraction
                if (sim > 0.6) {
                    val attraction = (dist * 0.05) * sim
                    n1.vx -= (dx/dist) * attraction
                    n1.vy -= (dy/dist) * attraction
                    n2.vx += (dx/dist) * attraction
                    n2.vy += (dy/dist) * attraction
                }
            }
        }
    }

    private fun updatePositions(nodes: List<PhysicsNode>) {
        for (n in nodes) {
            n.x += n.vx
            n.y += n.vy
            n.vx *= 0.8 // Damping
            n.vy *= 0.8
        }
    }

    private fun resolveCollisions(nodes: List<PhysicsNode>) {
        repeat(50) {
            for (i in nodes.indices) {
                val n1 = nodes[i]
                for (j in i + 1 until nodes.size) {
                    val n2 = nodes[j]
                    val dx = n1.x - n2.x
                    val dy = n1.y - n2.y
                    val dist = hypot(dx, dy)
                    val minDist = n1.radius + n2.radius + CORE_PADDING

                    if (dist < minDist && dist > 0.001) {
                        val overlap = minDist - dist
                        val pushX = (dx/dist) * overlap * 0.5
                        val pushY = (dy/dist) * overlap * 0.5
                        n1.x += pushX
                        n1.y += pushY
                        n2.x -= pushX
                        n2.y -= pushY
                    }
                }
            }
        }
    }

    // (Helper methods calculateCentroids, calculateSimilarityMatrix, normalize remain the same)
    private fun calculateCentroids(
        points: List<VectorPoint>,
        ids: IntArray,
        nodeIds: Set<Int>
    ): Map<Int, DoubleArray> {
        val pointsByCluster = points.indices.groupBy { ids[it] }

        return nodeIds.associateWith { id ->
            val indices = pointsByCluster[id] ?: emptyList()
            val vectors = indices.map { points[it].embedding }
            val raw = vectorMath.calculateCentroid(vectors)
            normalize(raw)
        }
    }

    private fun calculateSimilarityMatrix(
        nodeIds: Set<Int>,
        centroids: Map<Int, DoubleArray>
    ): Map<Int, Map<Int, Double>> {
        val list = nodeIds.toList()
        val matrix = mutableMapOf<Int, MutableMap<Int, Double>>()

        for (id in list) matrix[id] = mutableMapOf()

        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val idA = list[i]
                val idB = list[j]

                val sim = vectorMath.dotProduct(centroids[idA]!!, centroids[idB]!!)
                val cleanSim = sim.coerceAtLeast(0.0)

                matrix[idA]!![idB] = cleanSim
                matrix[idB]!![idA] = cleanSim
            }
        }
        return matrix
    }

    private fun normalize(v: DoubleArray): DoubleArray {
        var sum = 0.0
        for (d in v) sum += d * d
        val mag = kotlin.math.sqrt(sum)
        if (mag == 0.0) return v
        return DoubleArray(v.size) { i -> v[i] / mag }
    }
}