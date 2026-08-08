package com.paherdin.record.core.data.search

/**
 * Tech PoC P4 — the independent reference oracle: full-scan code-point
 * substring containment → stable sort (`occurredAt` DESC, `entityRefId` ASC)
 * → `take(pageSize)`. Pure JVM, derived only from the frozen corpus and the
 * frozen semantics; never from either candidate implementation.
 */
object ReferenceOracle {

    fun expectedFirstPage(
        corpus: List<SearchFixtureRecord>,
        query: String,
        pageSize: Int = 50,
    ): List<String> {
        val normalizedQuery = SearchNormalizer.normalize(query)
        return expectedAllMatches(corpus, normalizedQuery)
            .take(pageSize)
            .map { it.entityRefId }
    }

    /**
     * Diagnostic only: the complete pre-pagination match list. Never compared
     * directly with a first-page DAO result.
     */
    fun expectedAllMatches(
        corpus: List<SearchFixtureRecord>,
        normalizedQuery: String,
    ): List<SearchFixtureRecord> = corpus
        .filter { it.normalizedText.contains(normalizedQuery) }
        .sortedWith(
            compareByDescending<SearchFixtureRecord> { it.occurredAtMs }
                .thenBy { it.entityRefId },
        )
}
