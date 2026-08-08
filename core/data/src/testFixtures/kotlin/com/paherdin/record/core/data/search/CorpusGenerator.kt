package com.paherdin.record.core.data.search

import java.lang.Math.floorMod
import java.time.Instant
import java.util.UUID

/**
 * Tech PoC P4 — `corpus-v1`: the frozen 10,000-record mixed Chinese/English
 * corpus, per plan Task 3. Deterministic by construction: vocabulary choice
 * is arithmetic (`floorMod` over the index), never call-order randomness.
 */
object CorpusGenerator {

    const val corpusVersion = "corpus-v1"
    const val seed = 2026080804L
    const val recordCount = 10_000

    private val CHINESE_VOCABULARY = listOf(
        "早餐", "午餐", "晚餐", "交通", "地铁", "咖啡", "会议", "项目",
        "旅行", "书店", "周末", "预算", "记录",
    )
    private val ENGLISH_VOCABULARY = listOf(
        "expense", "Expenses", "transport", "cart", "project notes", "coffee",
        "meeting", "budget", "weekend", "trip", "ledger", "bookstore",
    )
    private val MIXED_VOCABULARY = listOf(
        "咖啡 coffee", "项目 project", "地铁 metro", "会议 notes", "周末 trip",
    )

    private const val SALT_CHINESE = 11L
    private const val SALT_ENGLISH = 23L
    private const val SALT_MIXED = 37L

    private val BASE_EPOCH_MS = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()

    fun generate(): List<SearchFixtureRecord> = (0 until recordCount).map(::record)

    private fun record(i: Int): SearchFixtureRecord {
        val language = languageOf(i)
        val vocabulary = when (language) {
            Language.CHINESE -> CHINESE_VOCABULARY
            Language.ENGLISH -> ENGLISH_VOCABULARY
            Language.MIXED -> MIXED_VOCABULARY
        }
        val salt = when (language) {
            Language.CHINESE -> SALT_CHINESE
            Language.ENGLISH -> SALT_ENGLISH
            Language.MIXED -> SALT_MIXED
        }
        val firstIndex = floorMod(seed + 31L * i + salt, vocabulary.size)
        val secondIndex = floorMod(seed + 17L * i + 3 + salt, vocabulary.size)
        val firstPhrase = vocabulary[firstIndex]
        val secondPhrase = vocabulary[secondIndex]

        val isTransaction = i < 7000
        // Transactions always carry a title; Note title is present exactly when i % 5 < 2.
        val title = if (!isTransaction && i % 5 >= 2) null else when (i) {
            0 -> firstPhrase + " ＡＢＣ"
            1 -> firstPhrase + " 50%"
            2 -> firstPhrase + " under_score"
            3 -> firstPhrase + " 𠀀"
            4 -> firstPhrase + " 﨑"
            else -> firstPhrase
        }
        val body = when {
            isTransaction && i % 5 != 0 -> secondPhrase
            !isTransaction -> if (i % 3 == 0) "$firstPhrase\n$secondPhrase" else firstPhrase
            else -> null
        }
        val auxiliary = auxiliaryFor(isTransaction, language)

        val normalized = SearchNormalizer.normalize(
            listOfNotNull(title, body, auxiliary).joinToString("\n"),
        )
        val occurredAtMs = BASE_EPOCH_MS - (i / 3) * 60_000L
        val updatedAtMs = occurredAtMs + (i % 1440) * 60_000L

        return SearchFixtureRecord(
            entityRefId = UUID.nameUUIDFromBytes(
                "corpus-v1:2026080804:$i".toByteArray(Charsets.UTF_8),
            ).toString(),
            type = if (isTransaction) "Transaction" else "Note",
            language = language,
            title = title,
            body = body,
            auxiliary = auxiliary,
            normalizedText = normalized,
            occurredAtMs = occurredAtMs,
            updatedAtMs = updatedAtMs,
        )
    }

    /** Index modulo 20: 11 Chinese slots, 6 English slots, 3 mixed slots. */
    private fun languageOf(i: Int): Language = when (floorMod(i, 20)) {
        in 0..10 -> Language.CHINESE
        in 11..16 -> Language.ENGLISH
        else -> Language.MIXED
    }

    private fun auxiliaryFor(isTransaction: Boolean, language: Language): String = when {
        isTransaction && language == Language.CHINESE -> "日常 account"
        isTransaction && language == Language.ENGLISH -> "daily account"
        isTransaction -> "日常 daily"
        language == Language.CHINESE -> "记录 tag"
        language == Language.ENGLISH -> "notes tag"
        else -> "记录 notes"
    }
}

enum class Language { CHINESE, ENGLISH, MIXED }

data class SearchFixtureRecord(
    val entityRefId: String,
    val type: String,
    val language: Language,
    val title: String?,
    val body: String?,
    val auxiliary: String,
    val normalizedText: String,
    val occurredAtMs: Long,
    val updatedAtMs: Long,
)

/**
 * Canonical UTF-8 serialization for the `corpus-v1` hash: field order
 * entityRefId,type,title,body,auxiliary,normalizedText,occurredAt,updatedAt;
 * every non-null field is byte-length:raw bytes, null is `-1:`; one tab
 * separates fields, one LF terminates each record.
 */
fun List<SearchFixtureRecord>.canonicalUtf8(): ByteArray {
    val out = StringBuilder()
    for (r in this) {
        val fields = listOf(
            r.entityRefId, r.type, r.title, r.body, r.auxiliary, r.normalizedText,
            r.occurredAtMs.toString(), r.updatedAtMs.toString(),
        )
        out.append(fields.joinToString("\t") { f ->
            if (f == null) "-1:" else "${f.toByteArray(Charsets.UTF_8).size}:$f"
        })
        out.append("\n")
    }
    return out.toString().toByteArray(Charsets.UTF_8)
}
