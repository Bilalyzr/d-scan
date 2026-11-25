package com.cleansweep.app.domain.scanner.scoring

import com.cleansweep.app.domain.model.IndexedFile
import com.cleansweep.app.domain.model.ReasonCode
import com.cleansweep.app.domain.model.SuggestionDraft
import com.cleansweep.app.domain.scanner.duplicates.LocationHeuristics
import com.cleansweep.app.domain.scanner.junk.DetectionContext
import com.cleansweep.app.domain.scanner.junk.Finding
import com.cleansweep.app.domain.scanner.junk.JunkRules

/**
 * Turns what the detectors found into the suggestions that get stored.
 *
 * One file can be reported by several detectors: the same APK can be an exact copy of another installer and an
 * app that is installed. Those findings are merged into one suggestion, so a file is never offered twice, and
 * the merged signals decide the category and the score.
 */
class SuggestionEngine(
    private val scorer: ConfidenceScorer = ConfidenceScorer(),
) {
    fun build(
        findings: Iterable<Finding>,
        context: DetectionContext,
    ): List<SuggestionDraft> = findings
        .groupBy { it.file.id }
        .mapNotNull { (_, group) ->
            val file = group.first().file
            val signals = group.flatMap { it.reasons } + modifyingSignals(file, context.now)
            val reasons = scorer.orderByWeight(signals)
            val category = scorer.categoryOf(reasons) ?: return@mapNotNull null
            SuggestionDraft(
                fileId = file.id,
                category = category,
                confidenceScore = scorer.score(reasons),
                reasons = reasons,
            )
        }
        .sortedWith(compareBy<SuggestionDraft> { it.category }.thenBy { it.fileId })

    /**
     * The signals that apply to whatever a file is: where it lies, and how long it has sat untouched. The
     * detectors need no knowledge of paths or of the 90-day rule because of this.
     */
    private fun modifyingSignals(
        file: IndexedFile,
        now: Long,
    ): List<ReasonCode> = buildList {
        if (LocationHeuristics.isInDownloads(file.path)) add(ReasonCode.IN_DOWNLOADS)
        if (LocationHeuristics.isInMessagingSentFolder(file.path)) add(ReasonCode.IN_MESSAGING_SENT)
        if (JunkRules.isStale(file.lastModified, now)) add(ReasonCode.STALE_90_DAYS)
    }
}
