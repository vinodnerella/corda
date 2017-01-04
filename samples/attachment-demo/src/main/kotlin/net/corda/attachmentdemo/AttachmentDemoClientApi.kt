package net.corda.attachmentdemo

import com.typesafe.config.Config
import net.corda.core.crypto.CompositeKey
import net.corda.core.crypto.SecureHash
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.ApiUtils
import net.corda.core.utilities.Emoji
import net.corda.core.utilities.loggerFor
import net.corda.flows.FinalityFlow
import net.corda.node.services.config.FullNodeConfiguration
import net.corda.node.services.messaging.ArtemisMessagingComponent
import net.corda.node.services.messaging.CordaRPCClient
import net.corda.testing.getHostAndPort
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals

/**
 * Interface for using the attachment demo API from a client.
 */
class AttachmentDemoClientApi(config: Config) {
    private val rpc: CordaRPCOps
    private val utils: ApiUtils

    private companion object {
        val PROSPECTUS_HASH = SecureHash.parse("decd098666b9657314870e192ced0c3519c2c9d395507a238338f8d003929de9")
        val logger = loggerFor<AttachmentDemoClientApi>()
    }

    init {
        val client = CordaRPCClient(config.getHostAndPort("artemisAddress"), FullNodeConfiguration(config))
        client.start(ArtemisMessagingComponent.NODE_USER, ArtemisMessagingComponent.NODE_USER)
        rpc = client.proxy()
        utils = ApiUtils(rpc)
    }

    fun runRecipient(): Boolean {
        val future = CompletableFuture<Boolean>()
        // Normally we would receive the transaction from a more specific flow, but in this case we let [FinalityFlow]
        // handle receiving it for us.
        rpc.verifiedTransactions().second.subscribe { event ->
            // When the transaction is received, it's passed through [ResolveTransactionsFlow], which first fetches any
            // attachments for us, then verifies the transaction. As such, by the time it hits the validated transaction store,
            // we have a copy of the attachment.
            val tx = event.tx
            val result = if (tx.attachments.isNotEmpty()) {
                assertEquals(PROSPECTUS_HASH, tx.attachments.first())
                require(rpc.attachmentExists(PROSPECTUS_HASH))

                println("File received - we're happy!\n\nFinal transaction is:\n\n${Emoji.renderIfSupported(event.tx)}")
                true
            } else {
                false
            }
            future.complete(result)
        }

        return future.get()
    }

    fun runSender(otherSide: String): Boolean {
        val partyKey = CompositeKey.parseFromBase58(otherSide)
        val party = rpc.partyFromKey(partyKey)!!
        // Make sure we have the file in storage
        // TODO: We should have our own demo file, not share the trader demo file
        if (!rpc.attachmentExists(PROSPECTUS_HASH)) {
            javaClass.classLoader.getResourceAsStream("bank-of-london-cp.jar").use {
                val id = rpc.uploadAttachment(it)
                assertEquals(PROSPECTUS_HASH, id)
            }
        }

        // Create a trivial transaction that just passes across the attachment - in normal cases there would be
        // inputs, outputs and commands that refer to this attachment.
        val ptx = net.corda.core.contracts.TransactionType.General.Builder(notary = null)
        require(rpc.attachmentExists(PROSPECTUS_HASH))
        ptx.addAttachment(PROSPECTUS_HASH)

        // Despite not having any states, we have to have at least one signature on the transaction
        ptx.signWith(net.corda.testing.ALICE_KEY)

        // Send the transaction to the other recipient
        val tx = ptx.toSignedTransaction()
        val protocolHandle = rpc.startFlow(::FinalityFlow, tx, setOf(party))
        protocolHandle.returnValue.toBlocking().first()

        return true
    }

    fun getOtherSideKey(): String {
        val myInfo = rpc.nodeIdentity()
        return rpc.networkMapUpdates().first.first { it != myInfo }.legalIdentity.owningKey.toBase58String()
    }
}
