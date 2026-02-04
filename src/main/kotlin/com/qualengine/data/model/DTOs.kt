package com.qualengine.data.model

import com.qualengine.core.analysis.SanityStatus
import java.util.UUID

data class SentenceAtom(
    val id: String = UUID.randomUUID().toString(),
    val docId: String,
    val content: String,
    val index: Int
)

data class Paragraph (
    val id: String = UUID.randomUUID().toString(),
    val docId: String,
    val content: String,
    val index: Int
)

data class LayeredNode (
    val content: String,
    val layer: Int,
    val status: SanityStatus,
    val parentId: String? = null,
    val docId: String,
    val index: Int
    )