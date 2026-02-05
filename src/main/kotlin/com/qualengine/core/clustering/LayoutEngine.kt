package com.qualengine.core.clustering

import com.qualengine.data.model.VectorPoint
import kotlin.collections.emptyMap
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.math.min

object LayoutEngine {

    fun createArchipelagoLayout(
        rawPoints: List<VectorPoint>,
        clusterIds: IntArray
    ): Pair<List<VectorPoint>, Map<Int, VectorPoint>> {

        // 1. Identify Valid Clusters
        val validClusters = clusterIds.filter { it != -1 }.distinct().sorted()
        if (validClusters.isEmpty()) return Pair(rawPoints, emptyMap())

        val count = validClusters.size

        // 2. GRID MATH
        val cols = ceil(sqrt(count.toDouble())).toInt()
        val rows = ceil(count.toDouble() / cols).toInt()

        val cellWidth = 1.0 / cols
        val cellHeight = 1.0 / rows

        // 3. Define Island Centers (Virtual Anchors)
        val islandCenters = mutableMapOf<Int, VectorPoint>()
        validClusters.forEachIndexed { index, clusterId ->
            val col = index % cols
            val row = index / cols

            val cx = (col * cellWidth) + (cellWidth / 2)
            val cy = (row * cellHeight) + (cellHeight / 2)

            // Create a virtual point for the center.
            // We use an empty array for embedding since this is just a visual anchor.
            islandCenters[clusterId] = VectorPoint(
                id = (-clusterId).toString(), // Negative ID to mark it as virtual
                embedding = DoubleArray(0),
                projectedX = cx,
                projectedY = cy,
                clusterId = clusterId,
                metaData = "",
                layer = 2, // TODO: Implement layer logic for this "anchor" when layer selection has been implemented
                parentId = null

            )
        }

        // 4. Map Points (Gravity & Compression)
        // We make a mutable copy of the LIST, but the POINTS inside are still immutable data classes.
        // We will replace them with .copy() versions as we go.
        val newPoints = rawPoints.toMutableList()

        validClusters.forEach { clusterId ->
            val indices = rawPoints.indices.filter { clusterIds[it] == clusterId }
            if (indices.isEmpty()) return@forEach

            // A. Centroid (Using projected coordinates)
            val sumX = indices.sumOf { rawPoints[it].projectedX }
            val sumY = indices.sumOf { rawPoints[it].projectedY }
            val centroidX = sumX / indices.size
            val centroidY = sumY / indices.size

            // B. Radius Calculation
            var currentRadius = 0.0
            indices.forEach { i ->
                val p = rawPoints[i]
                val dx = p.projectedX - centroidX
                val dy = p.projectedY - centroidY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > currentRadius) currentRadius = dist
            }
            if (currentRadius < 0.001) currentRadius = 0.001

            // C. Move & Compress
            val islandCenter = islandCenters[clusterId]!!
            val semanticScale = 0.15

            indices.forEach { i ->
                val original = rawPoints[i]

                val offsetX = (original.projectedX - centroidX) * semanticScale
                val offsetY = (original.projectedY - centroidY) * semanticScale

                newPoints[i] = original.copy(
                    projectedX = islandCenter.projectedX + offsetX,
                    projectedY = islandCenter.projectedY + offsetY
                )
            }
        }

        return Pair(newPoints, islandCenters)
    }

    fun createConstellationLayout(points: List<VectorPoint>): Pair<List<VectorPoint>, Map<Int, VectorPoint>> {
        return Pair(points, emptyMap())
    }

    /**
     * Squishes raw PCA coordinates (e.g. -50 to +50) into the Unit Square (0.0 to 1.0).
     * Essential for mapping to the screen canvas.
     */
    fun normalizeToUnitSquare(points: List<VectorPoint>): List<VectorPoint> {
        if (points.isEmpty()) return points

        // 1. Find the bounds of the raw math data
        val minX = points.minOf { it.projectedX }
        val maxX = points.maxOf { it.projectedX }
        val minY = points.minOf { it.projectedY }
        val maxY = points.maxOf { it.projectedY }

        // 2. Calculate range (Avoid divide-by-zero)
        val rangeX = (maxX - minX).coerceAtLeast(0.0001)
        val rangeY = (maxY - minY).coerceAtLeast(0.0001)

        // 3. Map to 0..1 and return updated VectorPoints
        return points.map { p ->
            p.copy(
                projectedX = (p.projectedX - minX) / rangeX,
                projectedY = (p.projectedY - minY) / rangeY
            )
        }
    }
}