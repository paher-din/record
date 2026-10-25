package com.paherdin.record2.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Cross-segment rules that the single-pass validator must preserve without retaining payloads. */
internal class StreamingValidatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun fixture(): BackupFixture =
        BackupFixtureFactory.create(temporaryFolder.newFolder("fixture"))

    private fun reject(fixture: BackupFixture, transform: (LogicalBackupContent) -> LogicalBackupContent) {
        val error = assertThrows(BackupException::class.java) {
            LogicalBackupValidator.validateContent(transform(fixture.content), fixture.now)
        }
        assertEquals(BackupError.InvalidSource, error.code)
    }

    private fun accept(fixture: BackupFixture, transform: (LogicalBackupContent) -> LogicalBackupContent) {
        LogicalBackupValidator.validateContent(transform(fixture.content), fixture.now)
    }

    @Test
    fun referencesAreSortedBeforeTheContiguityCheck() {
        val fixture = fixture()
        val second = ReferenceRow(BackupFixtureFactory.ASSISTANT_MESSAGE_ID, 1, BackupFixtureFactory.TRANSACTION_ID, null)
        accept(fixture) { it.copy(references = listOf(second, it.references.single())) }
    }

    @Test
    fun duplicateOrGappedReferenceOrdinalsAreRejected() {
        val fixture = fixture()
        val duplicate = ReferenceRow(BackupFixtureFactory.ASSISTANT_MESSAGE_ID, 0, BackupFixtureFactory.TRANSACTION_ID, null)
        reject(fixture) { it.copy(references = it.references + duplicate) }
        reject(fixture) {
            it.copy(references = it.references.map { ref -> ref.copy(ordinal = 2) })
        }
    }

    @Test
    fun missingReferenceTargetsAreRejected() {
        val fixture = fixture()
        reject(fixture) {
            it.copy(
                references = listOf(
                    ReferenceRow(
                        BackupFixtureFactory.ASSISTANT_MESSAGE_ID,
                        0,
                        null,
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                    ),
                ),
            )
        }
    }

    @Test
    fun duplicateAndGappedMessageSequencesAreRejected() {
        val fixture = fixture()
        reject(fixture) {
            it.copy(
                messages = it.messages.map { message ->
                    if (message.role == "ASSISTANT") message.copy(sequence = 0) else message
                },
            )
        }
        reject(fixture) {
            it.copy(sessions = it.sessions.map { session -> session.copy(nextMessageSequence = 3) })
        }
    }

    @Test
    fun runMustHaveExactlyOneUserAndTheCompletedAssistant() {
        val fixture = fixture()
        reject(fixture) {
            it.copy(
                messages = it.messages.map { message ->
                    if (message.role == "USER") message.copy(body = "") else message
                },
            )
        }
        reject(fixture) {
            it.copy(
                messages = it.messages.filter { message -> message.role != "ASSISTANT" },
                references = emptyList(),
                sessions = it.sessions.map { session -> session.copy(nextMessageSequence = 1) },
            )
        }
    }

    @Test
    fun proposalCreationIdentityMustMatchItsOriginRun() {
        val fixture = fixture()
        reject(fixture) {
            it.copy(
                proposals = it.proposals.map { proposal ->
                    proposal.copy(creationRequestId = "ffffffff-ffff-4fff-8fff-ffffffffffff")
                },
            )
        }
    }

    @Test
    fun proposalMustReferenceExistingRuns() {
        val fixture = fixture()
        reject(fixture) {
            it.copy(
                proposals = it.proposals.map { proposal ->
                    proposal.copy(originRunId = "ffffffff-ffff-4fff-8fff-ffffffffffff")
                },
            )
        }
    }

    @Test
    fun proposalStateAndOperationStateMustAgree() {
        val fixture = fixture()
        reject(fixture) {
            it.copy(
                proposals = it.proposals.map { proposal ->
                    proposal.copy(state = "EDITABLE", activeSlot = 1)
                },
            )
        }
    }

    @Test
    fun operationProposalIdMustBeUnique() {
        val fixture = fixture()
        reject(fixture) {
            it.copy(
                operations = it.operations + it.operations.single().copy(
                    id = "ffffffff-ffff-4fff-8fff-ffffffffffff",
                ),
            )
        }
    }

    @Test
    fun missingSessionsAndRunsAreRejected() {
        val fixture = fixture()
        reject(fixture) { it.copy(sessions = emptyList(), runs = emptyList(), messages = emptyList(), references = emptyList()) }
        reject(fixture) { it.copy(runs = emptyList(), messages = emptyList(), references = emptyList()) }
    }
}
