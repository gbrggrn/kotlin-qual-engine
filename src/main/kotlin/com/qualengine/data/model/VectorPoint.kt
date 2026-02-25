package com.qualengine.data.model

data class VectorPoint(
    val id: String,
    val embedding: DoubleArray,
    val projectedX: Double = 0.0,
    val projectedY: Double = 0.0,
    val clusterId: Int = -1,
    val metaData: String,
    val layer: Int, // 1: Sentence, 2: Paragraph 3: Document
    val parentId: String?,
    val enrichedMetaData: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorPoint
        if (id != other.id) return false // Strings compare fine
        if (!embedding.contentEquals(other.embedding)) return false
        if (projectedX != other.projectedX) return false
        if (projectedY != other.projectedY) return false
        if (clusterId != other.clusterId) return false
        if (layer != other.layer) return false
        if (parentId != other.parentId) return false
        if (enrichedMetaData != other.enrichedMetaData) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
