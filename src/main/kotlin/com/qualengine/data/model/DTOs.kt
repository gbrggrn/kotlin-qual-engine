package com.qualengine.data.model

import java.util.UUID

data class SentenceAtom(
    val id: String = UUID.randomUUID().toString(),
    val docId: String,
    val content: String,
    val index: Int
)

data class ParagraphMolecule (
    val id: String = UUID.randomUUID().toString(),
    val docId: String,
    val content: String,
    val index: Int
)