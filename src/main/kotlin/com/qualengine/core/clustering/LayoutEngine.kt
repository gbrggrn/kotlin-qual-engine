package com.qualengine.core.clustering

import com.qualengine.app.DependencyRegistry
import com.qualengine.data.model.VectorPoint
import com.qualengine.data.model.VirtualPoint
import kotlin.math.*

object LayoutEngine {

    // NOTE: This was implemented with the help of Gemini.

    private val vectorMath = DependencyRegistry.vectorMath

    // === TUNING ===
    private const val MAX_BLOB_SIZE = 6      // Slightly larger groups
    private const val BLOB_MERGE_THRESHOLD = 0.35 // Lower = blobs merge easier
    private const val CONNECTION_THRESHOLD = 0.85 // Lower = connections form easier

    private const val MAP_SCALE = 1800.0     // Set the map scale
    private const val BLOB_PADDING = 100.0   // Set the gap between blobs
    private const val CLUSTER_PADDING = 15.0 // Gap between clusters inside a blob

    // DATA HOLDERS
    private data class Blob(
        val id: Int,
        val clusterIds: MutableSet<Int>,
        var x: Double = 0.0,
        var y: Double = 0.0,
        var radius: Double = 0.0 // Will be calculated dynamically
    )

    // Internal helper for the spiral algorithm
    private data class PlacedItem<T>(
        val item: T,
        val x: Double,
        val y: Double,
        val radius: Double
    )

    data class LayoutResult(
        val positions: Map<Int, VirtualPoint> = emptyMap(),
        val blobMap: Map<Int, List<Int>> = emptyMap(),
        val clusterIds: List<Int> = emptyList(),
        val clusterConnections: Map<Int, List<Int>> = emptyMap()
    )

    fun computeLayout(
        points: List<VectorPoint>,
        clusterIds: IntArray
    ): LayoutResult {

        // === DATA PREP
        val counts = clusterIds.filter { it != -1 }.groupBy { it }.mapValues { it.value.size }
        val uniqueIds = counts.keys.sorted()
        if (uniqueIds.isEmpty()) return LayoutResult()

        val centroids = calculateCentroids(points, clusterIds, uniqueIds.toSet())
        val similarityMatrix = calculateSimilarityMatrix(uniqueIds.toSet(), centroids)

        // === BLOBIFY
        val blobs = formSmallBlobs(uniqueIds, similarityMatrix)
        val blobCentroids = calculateBlobCentroids(blobs, centroids)

        // === CALCULATE SIZES
        // We must know how big every cluster and blob is BEFORE we place them.
        val clusterRadii = uniqueIds.associateWith { id ->
            val count = counts[id] ?: 10
            sqrt(count.toDouble()) * 8.0 + 20.0
        }

        // Blob Radius = Size needed to hold its children + padding
        blobs.forEach { blob ->
            val totalArea = blob.clusterIds.sumOf { id ->
                val r = clusterRadii[id]!!
                PI * r * r
            }
            // Add 40% buffer for packing inefficiency
            blob.radius = sqrt((totalArea * 1.4) / PI)
        }

        println("LayoutEngine: Placing ${blobs.size} blobs...")

        // === GLOBAL LAYOUT (Blobs)
        // Place blobs on the map. If they overlap, spiral them out.
        projectAndPlaceSpiral(
            items = blobs,
            itemCentroids = blobCentroids,
            scale = MAP_SCALE,
            padding = BLOB_PADDING,
            getRadius = { it.radius },
            getId = { it.id },
            updatePosition = { blob, x, y -> blob.x = x; blob.y = y }
        )

        // === LOCAL LAYOUT (Clusters)
        val finalPositions = mutableMapOf<Int, VirtualPoint>()

        for (blob in blobs) {
            // Create wrappers for the clusters inside this blob
            val clusterItems = blob.clusterIds.toList()

            // Place clusters inside the blob (Relative to 0,0)
            projectAndPlaceSpiral(
                items = clusterItems,
                itemCentroids = centroids, // Use raw cluster centroids
                scale = blob.radius * 0.5, // Constrain projection to blob interior
                padding = CLUSTER_PADDING,
                getRadius = { clusterRadii[it]!! },
                getId = { it },
                updatePosition = { clusterId, relX, relY ->
                    finalPositions[clusterId] = VirtualPoint(
                        clusterId,
                        blob.x + relX, // Absolute X
                        blob.y + relY, // Absolute Y
                        clusterRadii[clusterId]!!,
                        "Processing"
                    )
                }
            )
        }

        // === FINALIZE
        val sortedBlobs = blobs.sortedByDescending { it.clusterIds.sumOf { cid -> counts[cid] ?: 0 } }
        val blobMap = blobs.associate { blob ->
            blob.id to blob.clusterIds.toList()
        }
        val connections = calculateConnections(similarityMatrix, CONNECTION_THRESHOLD)

        return LayoutResult(finalPositions, blobMap, uniqueIds, connections)
    }

    // ========================================================
    // LOGIC: SPIRAL PLACEMENT
    // ========================================================

    // ==================================================================
    // Projects items to their "Ideal" semantic position.
    // If that spot is taken, spirals outward until a free spot is found.
    // ==================================================================
    private fun <T> projectAndPlaceSpiral(
        items: List<T>,
        itemCentroids: Map<Int, DoubleArray>,
        scale: Double,
        padding: Double,
        getRadius: (T) -> Double,
        getId: (T) -> Int,
        updatePosition: (T, Double, Double) -> Unit
    ) {
        if (items.isEmpty()) return

        // === Calculate Ideal Positions (The Semantic Truth)
        val idealPositions = mutableMapOf<T, Pair<Double, Double>>()

        // Find Global Center
        val allVectors = items.map { itemCentroids[getId(it)]!! }
        val globalCenter = vectorMath.calculateCentroid(allVectors)

        // Find Pivots (X and Y axes)
        val pivotX = items.maxByOrNull {
            val vec = itemCentroids[getId(it)]!!
            vectorMath.calculateCosineDistance(vec, vectorMath.getMagnitude(vec), globalCenter, vectorMath.getMagnitude(globalCenter))
        } ?: items[0]

        val vecX = itemCentroids[getId(pivotX)]!!

        val pivotY = items.maxByOrNull {
            val vec = itemCentroids[getId(it)]!!
            vectorMath.calculateCosineDistance(vec, vectorMath.getMagnitude(vec), vecX, vectorMath.getMagnitude(vecX))
        } ?: items[0]

        val vecY = itemCentroids[getId(pivotY)]!!

        for (item in items) {
            val vec = itemCentroids[getId(item)]!!
            // Project onto Semantic Axes
            val simX = vectorMath.dotProduct(vec, vecX)
            val simY = vectorMath.dotProduct(vec, vecY)

            // Map [-1, 1] range to Scale
            val rawX = (simX - 0.5) * 2.5 * scale
            val rawY = (simY - 0.5) * 2.5 * scale
            idealPositions[item] = rawX to rawY
        }

        // === Place Items (The Physical Reality)
        val placed = mutableListOf<PlacedItem<T>>()

        // Sort by distance to center. We want to place the "Core" items first
        // so they get the best spots. Outliers can float to the edges.
        val sortedItems = items.sortedBy {
            val (ix, iy) = idealPositions[it]!!
            ix*ix + iy*iy
        }

        for (item in sortedItems) {
            val (idealX, idealY) = idealPositions[item]!!
            val myRadius = getRadius(item)

            var candidateX = idealX
            var candidateY = idealY

            // SPIRAL SEARCH
            // If we collide, we start walking in a spiral until we find free space.
            var angle = 0.0
            var radius = 0.0
            var step = 10.0 // How fast we spiral out

            // Safety valve: Don't spiral forever
            var attempts = 0
            while (attempts < 5000) {

                // Check Collision against ALL previously placed items
                var collision = false
                for (p in placed) {
                    val dx = candidateX - p.x
                    val dy = candidateY - p.y
                    val distSq = dx*dx + dy*dy
                    val minDist = myRadius + p.radius + padding

                    if (distSq < minDist * minDist) {
                        collision = true
                        break
                    }
                }

                if (!collision) {
                    // Success!
                    break
                }

                // Collision! Move along the spiral
                angle += 0.5 // Radians
                radius += 5.0 // Push out

                candidateX = idealX + cos(angle) * radius
                candidateY = idealY + sin(angle) * radius
                attempts++
            }

            // Commit placement
            placed.add(PlacedItem(item, candidateX, candidateY, myRadius))
            updatePosition(item, candidateX, candidateY)
        }
    }

    // ========================================================
    // LOGIC: BLOB FORMATION
    // ========================================================

    private fun formSmallBlobs(
        clusterIds: List<Int>,
        matrix: Map<Int, Map<Int, Double>>
    ): List<Blob> {
        var nextBlobId = 0
        val blobs = clusterIds.map { Blob(nextBlobId++, mutableSetOf(it)) }.toMutableList()

        var changed = true
        while (changed) {
            changed = false
            var bestMerge: Pair<Blob, Blob>? = null
            var maxSim = -1.0

            for (i in blobs.indices) {
                for (j in i + 1 until blobs.size) {
                    val b1 = blobs[i]
                    val b2 = blobs[j]

                    // CONSTRAINT: Max Blob Size
                    if (b1.clusterIds.size + b2.clusterIds.size > MAX_BLOB_SIZE) continue

                    val sim = calculateBlobSimilarity(b1, b2, matrix)
                    if (sim > maxSim && sim > BLOB_MERGE_THRESHOLD) {
                        maxSim = sim
                        bestMerge = b1 to b2
                    }
                }
            }

            if (bestMerge != null) {
                val (parent, child) = bestMerge
                parent.clusterIds.addAll(child.clusterIds)
                blobs.remove(child)
                changed = true
            }
        }
        return blobs
    }

    // ========================================================
    // HELPERS
    // ========================================================

    private fun calculateBlobCentroids(blobs: List<Blob>, clusterCentroids: Map<Int, DoubleArray>): Map<Int, DoubleArray> {
        return blobs.associate { blob ->
            val vectors = blob.clusterIds.map { clusterCentroids[it]!! }
            blob.id to normalize(vectorMath.calculateCentroid(vectors))
        }
    }

    private fun calculateBlobSimilarity(b1: Blob, b2: Blob, matrix: Map<Int, Map<Int, Double>>): Double {
        var sum = 0.0; var count = 0
        for (id1 in b1.clusterIds) {
            for (id2 in b2.clusterIds) {
                sum += matrix[id1]!![id2] ?: 0.0
                count++
            }
        }
        return if (count == 0) 0.0 else sum / count
    }

    private fun calculateCentroids(points: List<VectorPoint>, ids: IntArray, nodeIds: Set<Int>): Map<Int, DoubleArray> {
        val pointsByCluster = points.indices.groupBy { ids[it] }
        return nodeIds.associateWith { id ->
            val indices = pointsByCluster[id] ?: emptyList()
            val vectors = indices.map { points[it].embedding }
            val raw = vectorMath.calculateCentroid(vectors)
            normalize(raw)
        }
    }

    private fun calculateSimilarityMatrix(nodeIds: Set<Int>, centroids: Map<Int, DoubleArray>): Map<Int, Map<Int, Double>> {
        val list = nodeIds.toList()
        val matrix = mutableMapOf<Int, MutableMap<Int, Double>>()
        list.forEach { matrix[it] = mutableMapOf() }
        for (i in list.indices) {
            for (j in i + 1 until list.size) {
                val idA = list[i]; val idB = list[j]
                val sim = vectorMath.dotProduct(centroids[idA]!!, centroids[idB]!!).coerceAtLeast(0.0)
                matrix[idA]!![idB] = sim; matrix[idB]!![idA] = sim
            }
        }
        return matrix
    }

    private fun calculateConnections(matrix: Map<Int, Map<Int, Double>>, threshold: Double): Map<Int, List<Int>> {
        val connections = mutableMapOf<Int, List<Int>>()
        for ((idA, row) in matrix) {
            val friends = row.filter { it.value > threshold }.keys.toList()
            if (friends.isNotEmpty()) connections[idA] = friends
        }
        return connections
    }

    private fun normalize(v: DoubleArray): DoubleArray {
        var sum = 0.0
        for (d in v) sum += d * d
        val mag = sqrt(sum)
        return if (mag == 0.0) v else DoubleArray(v.size) { i -> v[i] / mag }
    }
}