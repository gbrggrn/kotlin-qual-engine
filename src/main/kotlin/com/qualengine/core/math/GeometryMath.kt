package com.qualengine.core.math

import javafx.geometry.Point2D

object GeometryMath {

    /**
     * ALGORITHM: JARVIS MARCH (Gift Wrapping)
     * Guaranteed to produce a valid convex hull loop.
     */
    fun computeConvexHull(points: List<Point2D>): List<Point2D> {
        if (points.size < 3) return points

        val hull = mutableListOf<Point2D>()

        // 1. Find the Leftmost point (Guaranteed to be on hull)
        val start = points.minWithOrNull(compareBy { it.x }) ?: return points
        var current = start

        // 2. Wrap around the points like a string
        do {
            hull.add(current)
            var next = points[0]

            for (p in points) {
                // If 'p' is more to the left of 'current->next' than 'next' is, pick 'p'
                if (next == current || crossProduct(current, next, p) > 0) {
                    next = p
                }
            }
            current = next
        } while (current != start)

        return hull
    }

    // Returns positive if O->A->B is a "Left Turn" (or CCW depending on coords)
    private fun crossProduct(o: Point2D, a: Point2D, b: Point2D): Double {
        return (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    }

    /*
    // === MONOTONE CHAIN
    // Wraps sets of points (clusters) in a tight polygon
    fun computeConvexHull(points: List<Point2D>): List<Point2D> {
        // This means it's not clustered, just for safety
        if (points.size < 3)
            return points

        // Sort by x, then y
        val sorted = points.sortedWith(compareBy<Point2D> { it.x }.thenBy { it.y })

        val upper = mutableListOf<Point2D>()
        for(p in sorted) {
            while(upper.size >= 2 && crossProduct(upper[upper.size - 2], upper.last(), p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }

        val lower = mutableListOf<Point2D>()
        for(p in sorted.reversed()) {
            while(lower.size >= 2 && crossProduct(lower[lower.size - 2], lower.last(), p) >= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }

        return upper.dropLast(1) + lower.dropLast(1)
    }

    private fun crossProduct(a: Point2D, b: Point2D, c: Point2D): Double {
        return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
    }

     */

    // === CHAIKINS SMOOTHING
    // Cuts the corners of a polygon to make it round and smoooooth
    fun smoothPolygon(points: List<Point2D>, iterations: Int = 3, tension: Double = 0.25): List<Point2D> {
        if (points.size < 3)
            return points

        var current = points

        repeat(iterations) {
            val next = mutableListOf<Point2D>()
            for(i in current.indices) {
                val p0 = current[i]
                val p1 = current[(i + 1) % current.size]

                val qx = p0.x * (1 - tension) + p1.x * tension
                val qy = p0.y * (1 - tension) + p1.y * tension

                val rx = p0.x * tension + p1.x * (1 - tension)
                val ry = p0.y * tension + p1.y * (1 - tension)

                next.add(Point2D(qx, qy))
                next.add(Point2D(rx, ry))
            }
            current = next
        }
        return current
    }
}