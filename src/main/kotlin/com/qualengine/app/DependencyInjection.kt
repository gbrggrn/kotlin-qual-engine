package com.qualengine.app

import com.qualengine.core.AnalysisContext
import com.qualengine.core.analysis.OllamaEnricher
import com.qualengine.core.analysis.SanityFilter
import com.qualengine.core.analysis.SemanticCompressor
import com.qualengine.core.analysis.SentenceSplitter
import com.qualengine.core.analysis.TextSanitizer
import com.qualengine.core.analysis.ThematicSplitter
import com.qualengine.core.clustering.ClusterRefiner
import com.qualengine.data.client.OllamaClient
import com.qualengine.data.db.DatabaseFactory
import com.qualengine.core.clustering.LayoutEngine
import com.qualengine.core.math.VectorMath
import com.qualengine.data.pipeline.InputPipeline
import com.qualengine.data.pipeline.Refinery
import com.qualengine.ui.explorer.ExplorerController
import com.qualengine.ui.explorer.ExplorerRenderer
import com.qualengine.ui.refinery.RefineryController
import javafx.scene.canvas.Canvas

object DependencyRegistry {
    // ====================
    // SINGLETONS
    //=====================
    // - Infrastructure
    val databaseFactory by lazy { DatabaseFactory }
    val ollamaClient by lazy { OllamaClient }
    // - Analysis
    val ollamaEnricher by lazy { OllamaEnricher }
    val sanityFilter by lazy { SanityFilter }
    val semanticCompressor by lazy { SemanticCompressor }
    val sentenceSplitter by lazy { SentenceSplitter }
    val thematicSplitter by lazy { ThematicSplitter }
    // - Clustering
    val clusterRefiner by lazy { ClusterRefiner }
    val layoutEngine by lazy { LayoutEngine }
    // - Math
    val vectorMath by lazy { VectorMath }
    // - State
    val analysisContext by lazy { AnalysisContext }
    // - Data ingestion
    val refinery by lazy { Refinery }
    val textSanitizer by lazy { TextSanitizer }

    // ====================
    // UI & INTERACTION
    // ====================
    // - Controllers
    lateinit var explorerController: ExplorerController
    lateinit var refineryController: RefineryController
    // - Input pipeline
    val inputPipeline by lazy {
        InputPipeline(analysisContext, explorerController) }
    // - Renderer
    fun createRenderer(canvas: Canvas): ExplorerRenderer {
        return ExplorerRenderer(canvas)
    }
}