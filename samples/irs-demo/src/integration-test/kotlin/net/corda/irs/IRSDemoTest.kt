package net.corda.irs

import com.google.common.net.HostAndPort
import com.typesafe.config.Config
import net.corda.core.getOrThrow
import net.corda.core.node.services.ServiceInfo
import net.corda.irs.api.NodeInterestRates
import net.corda.irs.contract.InterestRateSwap
import net.corda.irs.utilities.postJson
import net.corda.irs.utilities.putJson
import net.corda.irs.utilities.uploadFile
import net.corda.node.driver.driver
import net.corda.node.services.User
import net.corda.node.services.config.FullNodeConfiguration
import net.corda.node.services.messaging.CordaRPCClient
import net.corda.node.services.transactions.SimpleNotaryService
import net.corda.testing.IntegrationTestCategory
import org.apache.commons.io.IOUtils
import org.junit.Test
import rx.observables.BlockingObservable
import java.net.URL
import java.time.LocalDate

class IRSDemoTest : IntegrationTestCategory {
    fun Config.getHostAndPort(name: String): HostAndPort = HostAndPort.fromString(getString(name))!!
    val rpcUser = User("user", "password", emptySet())
    val currentDate = LocalDate.now()
    val futureDate = currentDate.plusMonths(6)

    @Test fun `runs IRS demo`() {
        driver(useTestClock = true, isDebug = true) {
            val controller = startNode("Notary", setOf(ServiceInfo(SimpleNotaryService.type), ServiceInfo(NodeInterestRates.type))).getOrThrow()
            val nodeA = startNode("Bank A", rpcUsers = listOf(rpcUser)).getOrThrow()
            val nodeB = startNode("Bank B").getOrThrow()
            val nextFixingDates = getFixingDateObservable(nodeA.config)

            runUploadRates(controller.config.getHostAndPort("webAddress"))
            runTrade(nodeA.config.getHostAndPort("webAddress"))
            // Wait until the initial trade and all scheduled fixings up to the current date have finished
            nextFixingDates.first { it == null || it > currentDate }

            runDateChange(nodeB.config.getHostAndPort("webAddress"))
            nextFixingDates.first { it == null || it > futureDate }
        }
    }

    fun getFixingDateObservable(nodeConfig: Config): BlockingObservable<LocalDate?> {
        val sslConfig = FullNodeConfiguration(nodeConfig)
        val client = CordaRPCClient(FullNodeConfiguration(nodeConfig).artemisAddress, sslConfig)
        client.start("user", "password")
        val proxy = client.proxy()
        val vaultUpdates = proxy.vaultAndUpdates().second

        val fixingDates = vaultUpdates.map { update ->
            val irsStates = update.produced.map { it.state.data }.filterIsInstance<InterestRateSwap.State>()
            irsStates.mapNotNull { it.calculation.nextFixingDate() }.max()
        }.cache().toBlocking()

        return fixingDates
    }

    private fun runDateChange(nodeAddr: HostAndPort) {
        val url = URL("http://$nodeAddr/api/irs/demodate")
        assert(putJson(url, "\"$futureDate\""))
    }

    private fun runTrade(nodeAddr: HostAndPort) {
        val fileContents = IOUtils.toString(Thread.currentThread().contextClassLoader.getResourceAsStream("example-irs-trade.json"))
        val tradeFile = fileContents.replace("tradeXXX", "trade1")
        val url = URL("http://$nodeAddr/api/irs/deals")
        assert(postJson(url, tradeFile))
    }

    private fun runUploadRates(host: HostAndPort) {
        val fileContents = IOUtils.toString(Thread.currentThread().contextClassLoader.getResourceAsStream("example.rates.txt"))
        val url = URL("http://$host/upload/interest-rates")
        assert(uploadFile(url, fileContents))
    }
}