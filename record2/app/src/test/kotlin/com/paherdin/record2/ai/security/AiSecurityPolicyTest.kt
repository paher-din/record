package com.paherdin.record2.ai.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSecurityPolicyTest {
    private val receiver = ProviderReceiver("deepseek.api.deepseek.com", 1, 1)
    private val alternate = ProviderReceiver("alternate.receiver.example", 2, 3)
    private val catalog = ProviderReceiverCatalog(listOf(receiver, alternate))

    @Test fun offModeBlocksEvenWithGrantAndCredential() {
        val state = grantedState(mode = AiMode.OFF)
        assertDenied(
            AiDenial.AI_DISABLED,
            AiSecurityPolicy.eligibility(state, receiver, catalog),
        )
        assertFalse(
            AiSecurityPolicy.permitIsCurrent(state, AiSendPermit(receiver, state.authEpoch), catalog),
        )
    }

    @Test fun fullModeRequiresSelectionGrantVersionsAndCredentialInOrder() {
        assertDenied(
            AiDenial.RECEIVER_NOT_SELECTED,
            AiSecurityPolicy.eligibility(
                state(mode = AiMode.FULL),
                receiver,
                catalog,
            ),
        )
        assertDenied(
            AiDenial.RECEIVER_UNAUTHORIZED,
            AiSecurityPolicy.eligibility(
                state(mode = AiMode.FULL, selected = receiver.identity),
                receiver,
                catalog,
            ),
        )
        assertDenied(
            AiDenial.RECEIVER_UNAUTHORIZED,
            AiSecurityPolicy.eligibility(
                state(
                    mode = AiMode.FULL, selected = alternate.identity, grant = receiver.identity,
                    scope = receiver.scopeVersion, disclosure = receiver.disclosureVersion,
                    grantedAt = 1_700_000_000_000L,
                ),
                receiver,
                catalog,
            ),
        )
        assertDenied(
            AiDenial.GRANT_VERSION_MISMATCH,
            AiSecurityPolicy.eligibility(
                state(
                    mode = AiMode.FULL, selected = receiver.identity, grant = receiver.identity,
                    scope = receiver.scopeVersion, disclosure = receiver.disclosureVersion + 1,
                    grantedAt = 1_700_000_000_000L,
                ),
                receiver,
                catalog,
            ),
        )
        assertDenied(
            AiDenial.CREDENTIAL_REQUIRED,
            AiSecurityPolicy.eligibility(grantedState(), receiver, catalog),
        )
        assertDenied(
            AiDenial.CREDENTIAL_UNREADABLE,
            AiSecurityPolicy.eligibility(
                grantedState(credential = CredentialStatus.UNREADABLE),
                receiver,
                catalog,
            ),
        )
        assertEquals(
            AiSendEligibility.Ready,
            AiSecurityPolicy.eligibility(
                grantedState(credential = CredentialStatus.AVAILABLE),
                receiver,
                catalog,
            ),
        )
    }

    @Test fun onlyTheExactConfiguredReceiverIsAuthorized() {
        assertDenied(
            AiDenial.UNSUPPORTED_RECEIVER,
            AiSecurityPolicy.eligibility(
                grantedState(credential = CredentialStatus.AVAILABLE),
                ProviderReceiver("https://untrusted.example", 1, 1),
                catalog,
            ),
        )
        assertDenied(
            AiDenial.UNSUPPORTED_RECEIVER,
            AiSecurityPolicy.eligibility(
                grantedState(credential = CredentialStatus.AVAILABLE),
                ProviderReceiver(receiver.identity, receiver.scopeVersion, receiver.disclosureVersion + 1),
                catalog,
            ),
        )
    }

    @Test fun grantAndSelectionMustAddressTheSameReceiver() {
        assertDenied(
            AiDenial.RECEIVER_UNAUTHORIZED,
            AiSecurityPolicy.eligibility(
                state(
                    mode = AiMode.FULL, selected = alternate.identity, grant = receiver.identity,
                    scope = receiver.scopeVersion, disclosure = receiver.disclosureVersion,
                    grantedAt = 1_700_000_000_000L,
                ),
                alternate,
                catalog,
            ),
        )
        assertEquals(
            AiSendEligibility.Ready,
            AiSecurityPolicy.eligibility(
                state(
                    mode = AiMode.FULL, selected = alternate.identity, grant = alternate.identity,
                    scope = alternate.scopeVersion, disclosure = alternate.disclosureVersion,
                    grantedAt = 1_700_000_000_000L,
                    credential = CredentialStatus.AVAILABLE,
                ),
                alternate,
                catalog,
            ),
        )
    }

    @Test fun malformedControlStateIsRejectedBeforeAnySend() {
        assertDenied(
            AiDenial.STATE_INVALID,
            AiSecurityPolicy.eligibility(
                grantedState(credential = CredentialStatus.AVAILABLE, epoch = -1),
                receiver,
                catalog,
            ),
        )
        assertDenied(
            AiDenial.STATE_INVALID,
            AiSecurityPolicy.eligibility(
                state(
                    mode = AiMode.FULL, selected = receiver.identity, grant = receiver.identity,
                    scope = 1, disclosure = null, credential = CredentialStatus.AVAILABLE,
                ),
                receiver,
                catalog,
            ),
        )
        assertDenied(
            AiDenial.STATE_INVALID,
            AiSecurityPolicy.eligibility(
                state(
                    mode = AiMode.FULL, selected = receiver.identity, grant = receiver.identity,
                    scope = 0, disclosure = 1, credential = CredentialStatus.AVAILABLE,
                ),
                receiver,
                catalog,
            ),
        )
        assertNull(
            AiSecurityPolicy.validate(grantedState(credential = CredentialStatus.AVAILABLE)),
        )
    }

    @Test fun permitIsBoundToTheAuthorizationEpoch() {
        val current = grantedState(credential = CredentialStatus.AVAILABLE, epoch = 7)
        val permit = AiSendPermit(receiver, 7)
        assertTrue(AiSecurityPolicy.permitIsCurrent(current, permit, catalog))
        assertFalse(
            AiSecurityPolicy.permitIsCurrent(current.copy(authEpoch = 8), permit, catalog),
        )
        assertFalse(
            AiSecurityPolicy.permitIsCurrent(
                current.copy(credentialStatus = CredentialStatus.UNREADABLE),
                permit,
                catalog,
            ),
        )
        assertFalse(
            AiSecurityPolicy.permitIsCurrent(
                current.copy(mode = AiMode.OFF),
                permit,
                catalog,
            ),
        )
        assertFalse(
            AiSecurityPolicy.permitIsCurrent(
                current.copy(grantReceiverIdentity = alternate.identity),
                permit,
                catalog,
            ),
        )
    }

    private fun grantedState(
        mode: AiMode = AiMode.FULL,
        credential: CredentialStatus = CredentialStatus.NOT_CONFIGURED,
        epoch: Long = 1,
    ) = state(
        mode = mode,
        selected = receiver.identity,
        grant = receiver.identity,
        scope = receiver.scopeVersion,
        disclosure = receiver.disclosureVersion,
        grantedAt = 1_700_000_000_000L,
        credential = credential,
        epoch = epoch,
    )

    private fun state(
        mode: AiMode = AiMode.OFF,
        selected: String? = null,
        grant: String? = null,
        scope: Int? = null,
        disclosure: Int? = null,
        grantedAt: Long? = null,
        epoch: Long = 0,
        credential: CredentialStatus = CredentialStatus.NOT_CONFIGURED,
    ) = AiSecurityState(
        mode = mode,
        selectedReceiverIdentity = selected,
        grantReceiverIdentity = grant,
        grantScopeVersion = scope,
        grantDisclosureVersion = disclosure,
        grantedAt = grantedAt,
        authEpoch = epoch,
        credentialStatus = credential,
    )

    private fun assertDenied(expected: AiDenial, actual: AiSendEligibility) {
        assertEquals(AiSendEligibility.Denied(expected), actual)
    }
}
