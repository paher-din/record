package com.paherdin.record.core.data.search

/**
 * Tech PoC P4 — `manifest-v1`: the exact 30 frozen query strings in execution
 * order, per plan Task 4. Query IDs are stable (`q01`…`q30`); the texts are
 * frozen verbatim in plan Revision 2 and must not change without an explicit
 * plan revision.
 */
object QueryManifest {

    const val manifestVersion = "manifest-v1"

    data class ManifestQuery(val id: String, val text: String)

    val queries: List<ManifestQuery> = listOf(
        ManifestQuery("q01", "餐"),
        ManifestQuery("q02", "早餐"),
        ManifestQuery("q03", "交通"),
        ManifestQuery("q04", "地铁"),
        ManifestQuery("q05", "咖啡"),
        ManifestQuery("q06", "会议"),
        ManifestQuery("q07", "项目"),
        ManifestQuery("q08", "旅行"),
        ManifestQuery("q09", "书店"),
        ManifestQuery("q10", "周末"),
        ManifestQuery("q11", "expense"),
        ManifestQuery("q12", "EXPENSE"),
        ManifestQuery("q13", "exp"),
        ManifestQuery("q14", "art"),
        ManifestQuery("q15", "transport"),
        ManifestQuery("q16", "project notes"),
        ManifestQuery("q17", "coffee"),
        ManifestQuery("q18", "meeting"),
        ManifestQuery("q19", "budget"),
        ManifestQuery("q20", "weekend"),
        ManifestQuery("q21", "咖啡 coffee"),
        ManifestQuery("q22", "项目 project"),
        ManifestQuery("q23", "地铁 metro"),
        ManifestQuery("q24", "会议 notes"),
        ManifestQuery("q25", "周末 trip"),
        ManifestQuery("q26", "量子火箭"),
        ManifestQuery("q27", "unfindabletoken"),
        ManifestQuery("q28", "火星 ledger"),
        ManifestQuery("q29", "ＡＢＣ"),
        ManifestQuery("q30", "50%"),
    )
}
