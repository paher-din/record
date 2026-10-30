package com.paherdin.record2.backup

import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotKeysTest {
    @Test
    fun numericAndUuidOrderingMatchesLogicalContract() {
        val sorter = SnapshotKeys(File("unused")) {}
        val random = java.util.Random(42)
        val keys = (0..500).map {
            SnapshotKeys.Key((it % 7 - 3).toLong(), random.nextLong(), random.nextLong(), random.nextLong(), it.toLong())
        }
        fun uuid(a: Long, b: Long) = UUID(a, b).toString()
        for (s in LogicalSegment.entries) {
            fun tuple(k: SnapshotKeys.Key): List<Comparable<*>> = when (s) {
                LogicalSegment.TRANSACTION, LogicalSegment.NOTE, LogicalSegment.PROPOSAL,
                LogicalSegment.OPERATION, LogicalSegment.SESSION, LogicalSegment.RUN -> listOf(k.a, uuid(k.b, k.c), k.row)
                LogicalSegment.LINK -> listOf(uuid(k.a, k.b), uuid(k.c, k.d), k.row)
                LogicalSegment.MESSAGE, LogicalSegment.REFERENCE -> listOf(uuid(k.a, k.b), k.c, k.row)
                LogicalSegment.DRAFT -> listOf(k.a, k.row)
                else -> listOf(uuid(k.a, k.b), k.row)
            }
            val expected = keys.sortedWith { x, y ->
                val a = tuple(x); val b = tuple(y)
                a.indices.asSequence().map { i ->
                    if (a[i] is Long) (a[i] as Long).compareTo(b[i] as Long)
                    else (a[i] as String).compareTo(b[i] as String)
                }.firstOrNull { it != 0 } ?: 0
            }
            assertEquals(s.name, expected, keys.sortedWith(sorter.comparator(s)))
        }
    }
}
