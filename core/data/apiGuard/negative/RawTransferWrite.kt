package consumer

import com.paherdin.record.core.data.RecordRepository

/**
 * Negative fixture: no raw/untyped transfer write path exists, and no
 * `Float`/`Double` money signature may appear on the public surface. The
 * exact bidirectional allowlist is the authoritative proof; this fixture is
 * defense in depth for the specific hypothetical bypass.
 */
class RawTransferWrite {
    suspend fun use(repo: RecordRepository) {
        repo.saveRawTransfer("src-account", 12.5)
    }
}
