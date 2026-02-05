package com.qualengine.core.clustering

import com.qualengine.data.model.VectorPoint
import com.qualengine.data.model.VirtualPoint
import kotlin.collections.emptyMap
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.sin

object LayoutEngine {
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

    fun createGalaxyLayout2(
        rawPoints: List<VectorPoint>,
        clusterIds: IntArray
    ): Pair<List<VectorPoint>, Map<Int, VirtualPoint>> {

        val validClusters = clusterIds.filter { it != -1 }.distinct()
        if (validClusters.isEmpty()) return Pair(rawPoints, emptyMap())

        // 1. Calculate Natural Centroids & Spread
        val anchors = mutableMapOf<Int, VirtualPoint>()
        validClusters.forEach { clusterId ->
            val indices = rawPoints.indices.filter { clusterIds[it] == clusterId }
            val avgX = indices.sumOf { rawPoints[it].projectedX } / indices.size
            val avgY = indices.sumOf { rawPoints[it].projectedY } / indices.size

            // Spread is the distance to the furthest point in the cluster
            val spread = indices.maxOf { i ->
                val p = rawPoints[i]
                sqrt((p.projectedX - avgX).pow(2) + (p.projectedY - avgY).pow(2))
            }.coerceAtLeast(0.05)

            anchors[clusterId] = VirtualPoint(clusterId, avgX, avgY, spread)
        }

        // 2. Repulsion Pass (Prevent Overlaps)
        // We nudge anchors away from each other if their radii collide
        repeat(3) {
            for (i in validClusters) {
                for (j in validClusters) {
                    if (i == j) continue
                    val a1 = anchors[i]!!
                    val a2 = anchors[j]!!

                    val dx = a2.x - a1.x
                    val dy = a2.y - a1.y
                    val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001)
                    val minDist = a1.radius + a2.radius + 0.05 // Radius + padding buffer

                    if (dist < minDist) {
                        val push = (minDist - dist) / 2
                        val moveX = (dx / dist) * push
                        val moveY = (dy / dist) * push

                        a1.x -= moveX
                        a1.y -= moveY
                        a2.x += moveX
                        a2.y += moveY
                    }
                }
            }
        }

        // 3. Apply Local Layout (Map points to their anchors)
        val newPoints = rawPoints.toMutableList()
        val semanticScale = 0.4 // How much to "tighten" the cluster around its anchor

        validClusters.forEach { clusterId ->
            val anchor = anchors[clusterId]!!
            val indices = rawPoints.indices.filter { clusterIds[it] == clusterId }

            // Recalculate local centroid to determine the offset
            val localX = indices.sumOf { rawPoints[it].projectedX } / indices.size
            val localY = indices.sumOf { rawPoints[it].projectedY } / indices.size

            indices.forEach { i ->
                val p = rawPoints[i]
                // Move the point based on the anchor's adjusted position
                newPoints[i] = p.copy(
                    projectedX = anchor.x + (p.projectedX - localX) * semanticScale,
                    projectedY = anchor.y + (p.projectedY - localY) * semanticScale
                )
            }
        }

        return Pair(newPoints, anchors)
    }

    fun createConstellationLayout(points: List<VectorPoint>): Pair<List<VectorPoint>, Map<Int, VectorPoint>> {
        return Pair(points, emptyMap())
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
}