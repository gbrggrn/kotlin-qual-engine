package com.qualengine.data.model

data class ClusterResult(
    val clusterIds: IntArray,
    val clusterCount: Int,
    val noiseCount: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ClusterResult
        if (!clusterIds.contentEquals(other.clusterIds)) return false
        if (clusterCount != other.clusterCount) return false
        return true
    }

    override fun hashCode(): Int {
        var result = clusterIds.contentHashCode()
        result = 31 * result + clusterCount
        return result
    }
}
