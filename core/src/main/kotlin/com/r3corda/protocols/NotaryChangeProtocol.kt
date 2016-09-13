package com.r3corda.protocols

import co.paralleluniverse.fibers.Suspendable
import com.r3corda.core.contracts.ContractState
import com.r3corda.core.contracts.StateAndRef
import com.r3corda.core.contracts.StateRef
import com.r3corda.core.contracts.TransactionType
import com.r3corda.core.crypto.Party
import com.r3corda.core.transactions.SignedTransaction
import com.r3corda.core.utilities.ProgressTracker
import com.r3corda.core.utilities.UntrustworthyData
import com.r3corda.protocols.NotaryChangeProtocol.Acceptor
import com.r3corda.protocols.NotaryChangeProtocol.Instigator
import java.security.PublicKey

/**
 * A protocol to be used for changing a state's Notary. This is required since all input states to a transaction
 * must point to the same notary.
 *
 * The [Instigator] assembles the transaction for notary replacement and sends out change proposals to all participants
 * ([Acceptor]) of that state. If participants agree to the proposed change, they each sign the transaction.
 * Finally, [Instigator] sends the transaction containing all signatures back to each participant so they can record it and
 * use the new updated state for future transactions.
 */
object NotaryChangeProtocol: AbstractStateReplacementProtocol<Party>() {

    val TOPIC = "platform.notary.change"

    data class Proposal(override val stateRef: StateRef,
                        override val modification: Party,
                        override val stx: SignedTransaction) : AbstractStateReplacementProtocol.Proposal<Party>

    class Instigator<T : ContractState>(originalState: StateAndRef<T>,
                                        newNotary: Party,
                                        progressTracker: ProgressTracker = tracker())
        : AbstractStateReplacementProtocol.Instigator<T, Party>(originalState, newNotary, progressTracker) {

        override val topic: String get() = TOPIC

        override fun assembleProposal(stateRef: StateRef, modification: Party, stx: SignedTransaction): AbstractStateReplacementProtocol.Proposal<Party>
            = Proposal(stateRef, modification, stx)

        override fun assembleTx(): Pair<SignedTransaction, List<PublicKey>> {
            val state = originalState.state
            val newState = state.withNotary(modification)
            val participants = state.data.participants
            val tx = TransactionType.NotaryChange.Builder(originalState.state.notary).withItems(originalState, newState)
            tx.signWith(serviceHub.storageService.myLegalIdentityKey)

            val stx = tx.toSignedTransaction(false)
            return Pair(stx, participants)
        }
    }

    class Acceptor(otherSide: Party,
                   override val progressTracker: ProgressTracker = tracker())
    : AbstractStateReplacementProtocol.Acceptor<Party>(otherSide) {

        override val topic: String get() = TOPIC

        /**
         * Check the notary change proposal.
         *
         * For example, if the proposed new notary has the same behaviour (e.g. both are non-validating)
         * and is also in a geographically convenient location we can just automatically approve the change.
         * TODO: In more difficult cases this should call for human attention to manually verify and approve the proposal
         */
        @Suspendable
        override fun verifyProposal(maybeProposal: UntrustworthyData<AbstractStateReplacementProtocol.Proposal<Party>>): AbstractStateReplacementProtocol.Proposal<Party> {
            return maybeProposal.unwrap { proposal ->
                val newNotary = proposal.modification
                val isNotary = serviceHub.networkMapCache.notaryNodes.any { it.identity == newNotary }
                require(isNotary) { "The proposed node $newNotary does not run a Notary service " }

                val state = proposal.stateRef
                val proposedTx = proposal.stx.tx
                require(state in proposedTx.inputs) { "The proposed state $state is not in the proposed transaction inputs" }
                require(proposedTx.type.javaClass == TransactionType.NotaryChange::class.java) {
                    "The proposed transaction is not a notary change transaction."
                }

                // An example requirement
                val blacklist = listOf("Evil Notary")
                require(!blacklist.contains(newNotary.name)) { "The proposed new notary $newNotary is not trusted by the party" }

                proposal
            }
        }
    }
}