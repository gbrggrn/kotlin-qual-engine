package com.qualengine.core.math

import javafx.geometry.Point2D

object GeometryMath {
    // NOTE: This class was implemented with the help of Gemini.

    // =====================================================
    // ALGORITHM: JARVIS MARCH (Gift Wrapping)
    // Guaranteed to produce a valid convex hull loop.
    // =====================================================
    fun computeConvexHull(points: List<Point2D>): List<Point2D> {
        if (points.size < 3) return points

        val hull = mutableListOf<Point2D>()

        // === Find the Leftmost point (Guaranteed to be on hull)
        val start = points.minWithOrNull(compareBy { it.x }) ?: return points
        var current = start

        // === Wrap around the points like a string
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

    // ===================================================================
    // CHAIKINS SMOOTHING
    // Cuts the corners of a polygon to make it round and smoooooth
    // ===================================================================
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