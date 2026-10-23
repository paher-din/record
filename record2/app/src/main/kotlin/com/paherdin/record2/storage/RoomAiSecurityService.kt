package com.paherdin.record2.storage

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import androidx.room.withTransaction
import com.paherdin.record2.ai.security.AiDenial
import com.paherdin.record2.ai.security.AiMode
import com.paherdin.record2.ai.security.AiSecurityError
import com.paherdin.record2.ai.security.AiSecurityException
import com.paherdin.record2.ai.security.AiSecurityPolicy
import com.paherdin.record2.ai.security.AiSecurityService
import com.paherdin.record2.ai.security.AiSecurityState
import com.paherdin.record2.ai.security.AiSendEligibility
import com.paherdin.record2.ai.security.AiSendPermit
import com.paherdin.record2.ai.security.CredentialUnavailableException
import com.paherdin.record2.ai.security.CredentialVault
import com.paherdin.record2.ai.security.ProviderReceiver
import com.paherdin.record2.ai.security.ProviderReceiverCatalog
import com.paherdin.record2.ai.security.RecordProviderReceivers
import java.io.IOException
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class RoomAiSecurityService(
    private val database: RecordDatabase,
    private val vault: CredentialVault,
    private val catalog: ProviderReceiverCatalog = RecordProviderReceivers.catalog,
    private val clock: Clock = Clock.systemUTC(),
) : AiSecurityService {
    private val dao = database.aiControl()
    private val records = database.records()

    override suspend fun state(): AiSecurityState = io {
        snapshot(database.withTransaction { ensureControl() })
    }

    override suspend fun setMode(mode: AiMode): AiSecurityState = io {
        val control = database.withTransaction {
            val current = requireWritableControl()
            if (current.mode == mode.name) return@withTransaction current
            val epoch = nextEpoch(current)
            if (dao.updateMode(mode.name, epoch) != 1) fail(AiSecurityError.ConcurrentUpdate)
            readControl()
        }
        snapshot(control)
    }

    override suspend fun selectReceiver(receiver: ProviderReceiver): AiSecurityState = io {
        val configured = configured(receiver)
        val control = database.withTransaction {
            val current = requireWritableControl()
            if (current.selectedReceiverIdentity == configured.identity) return@withTransaction current
            val epoch = nextEpoch(current)
            if (dao.selectReceiver(configured.identity, epoch) != 1) fail(AiSecurityError.ConcurrentUpdate)
            readControl()
        }
        snapshot(control)
    }

    override suspend fun grant(receiver: ProviderReceiver): AiSecurityState = io {
        val configured = configured(receiver)
        val control = database.withTransaction {
            val current = requireWritableControl()
            val epoch = nextEpoch(current)
            if (dao.grant(
                    configured.identity, configured.scopeVersion, configured.disclosureVersion,
                    clock.millis(), epoch,
                ) != 1
            ) {
                fail(AiSecurityError.ConcurrentUpdate)
            }
            readControl()
        }
        snapshot(control)
    }

    override suspend fun revoke(): AiSecurityState = io {
        val control = database.withTransaction {
            val current = requireWritableControl()
            val epoch = nextEpoch(current)
            if (dao.revoke(epoch) != 1) fail(AiSecurityError.ConcurrentUpdate)
            readControl()
        }
        snapshot(control)
    }

    override suspend fun replaceCredential(secret: CharArray): AiSecurityState = io {
        if (secret.none { !it.isWhitespace() }) fail(AiSecurityError.Validation)
        credentialMutation.withLock {
            val control = database.withTransaction {
                val current = requireWritableControl()
                val epoch = nextEpoch(current)
                if (dao.bumpAuthEpoch(epoch) != 1) fail(AiSecurityError.ConcurrentUpdate)
                readControl()
            }
            try {
                vault.store(secret)
            } catch (error: IllegalArgumentException) {
                throw AiSecurityException(AiSecurityError.Validation, error)
            } catch (error: CredentialUnavailableException) {
                throw AiSecurityException(AiSecurityError.CredentialUnavailable, error)
            } catch (error: IOException) {
                throw AiSecurityException(AiSecurityError.RecoverableIO, error)
            } catch (error: SecurityException) {
                throw AiSecurityException(AiSecurityError.CredentialUnavailable, error)
            }
            snapshot(control)
        }
    }

    override suspend fun clearCredential(): AiSecurityState = io {
        credentialMutation.withLock {
            val control = database.withTransaction {
                val current = requireWritableControl()
                val epoch = nextEpoch(current)
                if (dao.bumpAuthEpoch(epoch) != 1) fail(AiSecurityError.ConcurrentUpdate)
                readControl()
            }
            try {
                vault.clear()
            } catch (error: IOException) {
                throw AiSecurityException(AiSecurityError.RecoverableIO, error)
            } catch (error: SecurityException) {
                throw AiSecurityException(AiSecurityError.CredentialUnavailable, error)
            }
            snapshot(control)
        }
    }

    override suspend fun eligibility(receiver: ProviderReceiver): AiSendEligibility = io {
        val state = snapshot(database.withTransaction { ensureControl() })
        AiSecurityPolicy.eligibility(state, receiver, catalog)
    }

    override suspend fun requirePermit(receiver: ProviderReceiver): AiSendPermit = io {
        credentialMutation.withLock {
            val state = snapshot(database.withTransaction { ensureControl() })
            when (val result = AiSecurityPolicy.eligibility(state, receiver, catalog)) {
                AiSendEligibility.Ready -> AiSendPermit(receiver, state.authEpoch)
                is AiSendEligibility.Denied -> throw AiSecurityException(errorFor(result.reason))
            }
        }
    }

    override suspend fun stillValid(permit: AiSendPermit): Boolean = io {
        val state = snapshot(database.withTransaction { ensureControl() })
        AiSecurityPolicy.permitIsCurrent(state, permit, catalog)
    }

    override suspend fun <T> useCredential(permit: AiSendPermit, block: (CharArray) -> T): T = io {
        val state = snapshot(database.withTransaction { ensureControl() })
        if (!AiSecurityPolicy.permitIsCurrent(state, permit, catalog)) fail(AiSecurityError.RunInvalidated)
        try {
            vault.use(block)
        } catch (error: CredentialUnavailableException) {
            throw AiSecurityException(AiSecurityError.CredentialUnavailable, error)
        }
    }

    private fun ensureControl(): AiControlEntity {
        if (records.dataState() == null) {
            if (records.hasDataWithoutGeneration()) fail(AiSecurityError.RecoveryRequired)
            return virtualControl()
        }
        dao.control()?.let { return it }
        dao.insertControlIfAbsent(virtualControl())
        return readControl()
    }

    private fun requireWritableControl(): AiControlEntity {
        val control = ensureControl()
        if (records.dataState() == null) fail(AiSecurityError.NotInitialized)
        return control
    }

    private fun virtualControl(): AiControlEntity =
        AiControlEntity(1, AiMode.OFF.name, null, null, null, null, null, 0)

    private fun readControl(): AiControlEntity = dao.control() ?: fail(AiSecurityError.RecoveryRequired)

    private fun snapshot(control: AiControlEntity): AiSecurityState {
        val mode = try {
            AiMode.valueOf(control.mode)
        } catch (error: IllegalArgumentException) {
            throw AiSecurityException(AiSecurityError.RecoveryRequired, error)
        }
        return AiSecurityState(
            mode = mode,
            selectedReceiverIdentity = control.selectedReceiverIdentity,
            grantReceiverIdentity = control.grantReceiverIdentity,
            grantScopeVersion = control.grantScopeVersion,
            grantDisclosureVersion = control.grantDisclosureVersion,
            grantedAt = control.grantedAt,
            authEpoch = control.authEpoch,
            credentialStatus = vault.status(),
        )
    }

    private fun configured(receiver: ProviderReceiver): ProviderReceiver {
        val configured = catalog.find(receiver.identity)
        if (configured != receiver) fail(AiSecurityError.UnsupportedReceiver)
        return configured
    }

    private fun nextEpoch(control: AiControlEntity): Long {
        if (control.authEpoch < 0 || control.authEpoch == Long.MAX_VALUE) {
            fail(AiSecurityError.RecoveryRequired)
        }
        return control.authEpoch
    }

    private fun errorFor(reason: AiDenial): AiSecurityError = when (reason) {
        AiDenial.AI_DISABLED,
        AiDenial.RECEIVER_NOT_SELECTED,
        AiDenial.RECEIVER_UNAUTHORIZED,
        AiDenial.GRANT_VERSION_MISMATCH,
        -> AiSecurityError.AuthorizationRequired

        AiDenial.UNSUPPORTED_RECEIVER -> AiSecurityError.UnsupportedReceiver
        AiDenial.CREDENTIAL_REQUIRED,
        AiDenial.CREDENTIAL_UNREADABLE,
        -> AiSecurityError.CredentialUnavailable

        AiDenial.STATE_INVALID -> AiSecurityError.RecoveryRequired
    }

    private fun fail(code: AiSecurityError): Nothing = throw AiSecurityException(code)

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiSecurityException) {
            throw error
        } catch (error: SQLiteFullException) {
            throw AiSecurityException(AiSecurityError.StorageFull, error)
        } catch (error: SQLiteException) {
            throw AiSecurityException(AiSecurityError.RecoverableIO, error)
        }
    }

    private companion object {
        // Serializes credential mutation with permit issuance in this process. The E0 contract is
        // single-process; E2 still owns Run invalidation and network cancellation.
        private val credentialMutation = Mutex()
    }
}
