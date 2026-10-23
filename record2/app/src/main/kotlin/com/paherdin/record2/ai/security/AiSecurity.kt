package com.paherdin.record2.ai.security

internal enum class AiMode { OFF, FULL }

internal data class ProviderReceiver(
    val identity: String,
    val scopeVersion: Int,
    val disclosureVersion: Int,
) {
    init {
        require(identity.isNotBlank()) { "Blank receiver identity" }
        require(scopeVersion >= 1) { "Invalid scope version" }
        require(disclosureVersion >= 1) { "Invalid disclosure version" }
    }
}

internal class ProviderReceiverCatalog(receivers: List<ProviderReceiver>) {
    private val byIdentity: Map<String, ProviderReceiver> = receivers.associateBy { it.identity }

    init {
        require(receivers.isNotEmpty()) { "At least one receiver is required" }
        require(byIdentity.size == receivers.size) { "Duplicate receiver identity" }
    }

    fun find(identity: String): ProviderReceiver? = byIdentity[identity]
    fun contains(receiver: ProviderReceiver): Boolean = byIdentity[receiver.identity] == receiver
    val all: List<ProviderReceiver> get() = byIdentity.values.toList()
}

internal object RecordProviderReceivers {
    // Recipient identity, not a model or provider label supplied by the agent.
    val deepSeek = ProviderReceiver(
        identity = "deepseek.api.deepseek.com",
        scopeVersion = 1,
        disclosureVersion = 1,
    )
    val catalog = ProviderReceiverCatalog(listOf(deepSeek))
}

internal enum class CredentialStatus { NOT_CONFIGURED, AVAILABLE, UNREADABLE }

internal enum class AiDenial {
    AI_DISABLED,
    RECEIVER_NOT_SELECTED,
    RECEIVER_UNAUTHORIZED,
    UNSUPPORTED_RECEIVER,
    GRANT_VERSION_MISMATCH,
    CREDENTIAL_REQUIRED,
    CREDENTIAL_UNREADABLE,
    STATE_INVALID,
}

internal sealed interface AiSendEligibility {
    data object Ready : AiSendEligibility
    data class Denied(val reason: AiDenial) : AiSendEligibility
}

internal data class AiSecurityState(
    val mode: AiMode,
    val selectedReceiverIdentity: String?,
    val grantReceiverIdentity: String?,
    val grantScopeVersion: Int?,
    val grantDisclosureVersion: Int?,
    val grantedAt: Long?,
    val authEpoch: Long,
    val credentialStatus: CredentialStatus,
)

internal data class AiSendPermit(val receiver: ProviderReceiver, val authEpoch: Long)

internal object AiSecurityPolicy {
    fun validate(state: AiSecurityState): AiDenial? {
        if (state.authEpoch < 0) return AiDenial.STATE_INVALID
        if (state.grantedAt != null && state.grantedAt < 0) return AiDenial.STATE_INVALID
        val grantFields = listOf(
            state.grantReceiverIdentity,
            state.grantScopeVersion,
            state.grantDisclosureVersion,
            state.grantedAt,
        )
        val present = grantFields.count { it != null }
        if (present != 0 && present != grantFields.size) return AiDenial.STATE_INVALID
        if (state.grantReceiverIdentity != null) {
            if (state.grantReceiverIdentity.isBlank()) return AiDenial.STATE_INVALID
            if ((state.grantScopeVersion ?: 0) < 1) return AiDenial.STATE_INVALID
            if ((state.grantDisclosureVersion ?: 0) < 1) return AiDenial.STATE_INVALID
        }
        if (state.selectedReceiverIdentity?.isBlank() == true) return AiDenial.STATE_INVALID
        return null
    }

    fun eligibility(
        state: AiSecurityState,
        receiver: ProviderReceiver,
        catalog: ProviderReceiverCatalog,
    ): AiSendEligibility {
        if (validate(state) != null) return AiSendEligibility.Denied(AiDenial.STATE_INVALID)
        if (!catalog.contains(receiver)) return AiSendEligibility.Denied(AiDenial.UNSUPPORTED_RECEIVER)
        if (state.mode == AiMode.OFF) return AiSendEligibility.Denied(AiDenial.AI_DISABLED)
        val selected = state.selectedReceiverIdentity
            ?: return AiSendEligibility.Denied(AiDenial.RECEIVER_NOT_SELECTED)
        if (selected != receiver.identity) return AiSendEligibility.Denied(AiDenial.RECEIVER_UNAUTHORIZED)
        if (state.grantReceiverIdentity != receiver.identity) {
            return AiSendEligibility.Denied(AiDenial.RECEIVER_UNAUTHORIZED)
        }
        if (state.grantScopeVersion != receiver.scopeVersion ||
            state.grantDisclosureVersion != receiver.disclosureVersion
        ) {
            return AiSendEligibility.Denied(AiDenial.GRANT_VERSION_MISMATCH)
        }
        return when (state.credentialStatus) {
            CredentialStatus.NOT_CONFIGURED -> AiSendEligibility.Denied(AiDenial.CREDENTIAL_REQUIRED)
            CredentialStatus.UNREADABLE -> AiSendEligibility.Denied(AiDenial.CREDENTIAL_UNREADABLE)
            CredentialStatus.AVAILABLE -> AiSendEligibility.Ready
        }
    }

    fun permitIsCurrent(
        state: AiSecurityState,
        permit: AiSendPermit,
        catalog: ProviderReceiverCatalog,
    ): Boolean {
        if (validate(state) != null) return false
        if (state.authEpoch != permit.authEpoch) return false
        return eligibility(state, permit.receiver, catalog) == AiSendEligibility.Ready
    }
}

internal enum class AiSecurityError {
    Validation,
    UnsupportedReceiver,
    AuthorizationRequired,
    CredentialUnavailable,
    RunInvalidated,
    ConcurrentUpdate,
    NotInitialized,
    RecoveryRequired,
    RecoverableIO,
    StorageFull,
}

internal class AiSecurityException(
    val code: AiSecurityError,
    cause: Throwable? = null,
) : Exception(code.name, cause)

internal class CredentialUnavailableException(cause: Throwable? = null) :
    Exception("CredentialUnavailable", cause)

internal interface CredentialVault {
    fun status(): CredentialStatus
    fun store(secret: CharArray)
    fun clear()
    fun <T> use(block: (CharArray) -> T): T
}

internal interface AiSecurityService {
    suspend fun state(): AiSecurityState
    suspend fun setMode(mode: AiMode): AiSecurityState
    suspend fun selectReceiver(receiver: ProviderReceiver): AiSecurityState
    suspend fun grant(receiver: ProviderReceiver): AiSecurityState
    suspend fun revoke(): AiSecurityState
    suspend fun replaceCredential(secret: CharArray): AiSecurityState
    suspend fun clearCredential(): AiSecurityState
    suspend fun eligibility(receiver: ProviderReceiver): AiSendEligibility
    suspend fun requirePermit(receiver: ProviderReceiver): AiSendPermit
    suspend fun stillValid(permit: AiSendPermit): Boolean
    suspend fun <T> useCredential(permit: AiSendPermit, block: (CharArray) -> T): T
}
