package fr.acinq.phoenix.managers

import fr.acinq.lightning.blockchain.electrum.ElectrumClient
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.Connection
import fr.acinq.lightning.utils.ServerAddress
import fr.acinq.phoenix.PhoenixBusiness
import fr.acinq.phoenix.data.ElectrumConfig
import fr.acinq.phoenix.utils.TorHelper.connectionState
import fr.acinq.tor.Tor
import fr.acinq.tor.TorState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.*
import org.kodein.log.LoggerFactory
import org.kodein.log.newLogger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class AppConnectionsDaemon(
    loggerFactory: LoggerFactory,
    private val configurationManager: AppConfigurationManager,
    private val walletManager: WalletManager,
    private val peerManager: PeerManager,
    private val currencyManager: CurrencyManager,
    private val networkMonitor: NetworkMonitor,
    private val tcpSocketBuilder: suspend () -> TcpSocket.Builder,
    private val tor: Tor,
    private val electrumClient: ElectrumClient,
) : CoroutineScope by MainScope() {

    constructor(business: PhoenixBusiness) : this(
        loggerFactory = business.loggerFactory,
        configurationManager = business.appConfigurationManager,
        walletManager = business.walletManager,
        peerManager = business.peerManager,
        currencyManager = business.currencyManager,
        networkMonitor = business.networkMonitor,
        tcpSocketBuilder = business.tcpSocketBuilderFactory,
        tor = business.tor,
        electrumClient = business.electrumClient
    )

    private val logger = newLogger(loggerFactory)

    private var peerConnectionJob: Job? = null
    private var electrumConnectionJob: Job? = null
    private var torConnectionJob: Job? = null
    private var httpControlFlowEnabled: Boolean = false

    private data class TrafficControl(
        val walletIsAvailable: Boolean = false,
        val internetIsAvailable: Boolean = false,
        val torIsEnabled: Boolean = false,
        val torIsAvailable: Boolean = false,

        // Under normal circumstances, the connections are automatically managed based on whether
        // or not the network connection is available. However, the app may need to influence
        // this decision.
        //
        // For example, on iOS:
        // - When the app goes into background mode, it wants to force a disconnect.
        // - Unless a payment is in-flight, in which case it wants to stay connected until
        //   the payment completes.
        // - And if the app is backgrounded, but the app receives a push notification,
        //   then it wants to re-connect and handle the incoming payment,
        //   and disconnect again afterwards.
        //
        // This complexity is handled by a simple voting mechanism.
        // (Think: retainCount from manual memory-management systems)
        //
        // The rules are:
        // - if disconnectCount > 0 => triggers disconnect & prevents future connection attempts
        // - if disconnectCount <= 0 => allows connection based on network availability (as usual)
        //
        // Any part of the app that "votes" is expected to properly balance their calls.
        // For example, on iOS:
        // - When the app goes into the background, it increments the count (vote to disconnect)
        //   And when the app returns to the foreground, it decrements the count (undo vote)
        // - When an in-flight payment is detected, it decrements the count (vote to remain connected).
        //   And when the payment completes, it increments the count (undo vote).
        // - When a push notifications wakes the app, in decrements the count (vote to connect).
        //   And when it finishes processing, it increments the count (undo vote).
        //
        val disconnectCount: Int = 0,

        // If a configuration value changes, this value can be incremented to
        // force a disconnect & reconnect.
        val configVersion: Int = 0
    ) {
        val canConnect get() = if (walletIsAvailable && internetIsAvailable && disconnectCount <= 0) {
            if (torIsEnabled) torIsAvailable else true
        } else {
            false
        }

        fun incrementDisconnectCount(): TrafficControl {
            val safeInc = disconnectCount.let { if (it == Int.MAX_VALUE) it else it + 1 }
            return copy(disconnectCount = safeInc)
        }
        fun decrementDisconnectCount(): TrafficControl {
            val safeDec = disconnectCount.let { if (it == Int.MIN_VALUE) it else it - 1 }
            return copy(disconnectCount = safeDec)
        }
    }

    private val torControlFlow = MutableStateFlow(TrafficControl())
    private val torControlChanges = Channel<TrafficControl.() -> TrafficControl>()

    private val peerControlFlow = MutableStateFlow(TrafficControl())
    private val peerControlChanges = Channel<TrafficControl.() -> TrafficControl>()

    private val electrumControlFlow = MutableStateFlow(TrafficControl())
    private val electrumControlChanges = Channel<TrafficControl.() -> TrafficControl>()

    private val httpApiControlFlow = MutableStateFlow(TrafficControl())
    private val httpApiControlChanges = Channel<TrafficControl.() -> TrafficControl>()

    private var _lastElectrumServerAddress = MutableStateFlow<ServerAddress?>(null)
    val lastElectrumServerAddress: StateFlow<ServerAddress?> = _lastElectrumServerAddress

    init {
        fun enableControlFlow(
            label: String,
            controlFlow: MutableStateFlow<TrafficControl>,
            controlChanges: ReceiveChannel<TrafficControl.() -> TrafficControl>
        ) = launch {
            controlChanges.consumeEach { change ->
                val newState = controlFlow.value.change()
                logger.info { "$label = $newState" }
                controlFlow.value = newState
            }
        }

        enableControlFlow("torControlFlow", torControlFlow, torControlChanges)
        enableControlFlow("peerControlFlow", peerControlFlow, peerControlChanges)
        enableControlFlow("electrumControlFlow", electrumControlFlow, electrumControlChanges)
        enableControlFlow("httpApiControlFlow", httpApiControlFlow, httpApiControlChanges)

        // Wallet monitor
        launch {
            // Suspends until the wallet is initialized
            walletManager.keyManager.filterNotNull().first()
            logger.debug { "walletIsAvailable = true" }
            torControlChanges.send { copy(walletIsAvailable = true) }
            peerControlChanges.send { copy(walletIsAvailable = true) }
            electrumControlChanges.send { copy(walletIsAvailable = true) }
            httpApiControlChanges.send { copy(walletIsAvailable = true) }
        }

        // Internet monitor
        launch {
            networkMonitor.start()
            networkMonitor.networkState.collect {
                val newValue = it == NetworkState.Available
                logger.debug { "internetIsAvailable = $newValue" }
                torControlChanges.send { copy(internetIsAvailable = newValue) }
                peerControlChanges.send { copy(internetIsAvailable = newValue) }
                electrumControlChanges.send { copy(internetIsAvailable = newValue) }
                httpApiControlChanges.send { copy(internetIsAvailable = newValue) }
            }
        }

        // Tor enabled monitor
        launch {
            configurationManager.isTorEnabled.filterNotNull().collect { newValue ->
                logger.debug { "torIsEnabled = $newValue" }
                torControlChanges.send { copy(torIsEnabled = newValue) }
                peerControlChanges.send { copy(torIsEnabled = newValue) }
                electrumControlChanges.send { copy(torIsEnabled = newValue) }
                httpApiControlChanges.send { copy(torIsEnabled = newValue) }
            }
        }

        // Tor state monitor
        launch {
            tor.state.collect {
                val newValue = it == TorState.RUNNING
                logger.debug { "torIsAvailable = $newValue" }
                torControlChanges.send { copy(torIsAvailable = newValue) }
                peerControlChanges.send { copy(torIsAvailable = newValue) }
                electrumControlChanges.send { copy(torIsAvailable = newValue) }
                httpApiControlChanges.send { copy(torIsAvailable = newValue) }
            }
        }

        // Tor
        launch {
            torControlFlow.collect {
                when {
                    it.internetIsAvailable && it.disconnectCount <= 0 && it.torIsEnabled -> {
                        if (torConnectionJob == null) {
                            logger.info { "starting tor" }
                            torConnectionJob = connectionLoop(
                                "Tor",
                                tor.state.connectionState(this)
                            ) {
                                try {
                                    tor.startInProperScope(this)
                                } catch (t: Throwable) {
                                    logger.error(t) { "tor cannot be started: ${t.message}" }
                                }
                            }
                        }
                    }
                    else -> {
                        torConnectionJob?.let {
                            logger.info { "shutting down tor" }
                            it.cancel()
                            tor.stop()
                            torConnectionJob = null
                            // Tor runs it's own process, and needs time to shutdown before restarting.
                            delay(500)
                        }
                    }
                }
            }
        }

        // Peer
        launch {
            var configVersion = 0
            var torIsEnabled = false
            peerControlFlow.collect {
                val peer = peerManager.getPeer()
                val forceDisconnect: Boolean =
                    if (configVersion != it.configVersion || torIsEnabled != it.torIsEnabled) {
                        configVersion = it.configVersion
                        torIsEnabled = it.torIsEnabled
                        true
                    } else {
                        false
                    }
                if (forceDisconnect || !it.canConnect) {
                    peerConnectionJob?.let { job ->
                        logger.info { "disconnecting from peer" }
                        job.cancel()
                        peer.disconnect()
                        peerConnectionJob = null
                    }

                }
                if (it.canConnect) {
                    if (peerConnectionJob == null) {
                        logger.info { "connecting to peer" }
                        peerConnectionJob = connectionLoop(
                            name = "Peer",
                            statusStateFlow = peer.connectionState
                        ) {
                            peer.socketBuilder = tcpSocketBuilder()
                            peer.connect()
                        }
                    }
                }
            }
        }

        // Electrum
        launch {
            var configVersion = 0
            var torIsEnabled = false
            electrumControlFlow.collect {
                val forceDisconnect: Boolean =
                    if (configVersion != it.configVersion || torIsEnabled != it.torIsEnabled) {
                        configVersion = it.configVersion
                        torIsEnabled = it.torIsEnabled
                        true
                    } else {
                        false
                    }
                if (forceDisconnect || !it.canConnect) {
                    electrumConnectionJob?.let { job ->
                        logger.info { "disconnecting from electrum" }
                        job.cancel()
                        electrumClient.disconnect()
                        electrumConnectionJob = null
                    }
                }
                if (it.canConnect) {
                    if (electrumConnectionJob == null) {
                        logger.info { "connecting to electrum" }
                        logger.info { "electrum socket builder=${electrumClient.socketBuilder}" }
                        electrumConnectionJob = connectionLoop(
                            name = "Electrum",
                            statusStateFlow = electrumClient.connectionState
                        ) {
                            val electrumServerAddress : ServerAddress? = configurationManager.electrumConfig.value?.let { electrumConfig ->
                                when (electrumConfig) {
                                    is ElectrumConfig.Custom -> electrumConfig.server
                                    is ElectrumConfig.Random -> configurationManager.randomElectrumServer()
                                }
                            }
                            if (electrumServerAddress == null) {
                                logger.info { "ignored electrum connection opportunity because no server is configured yet" }
                            } else {
                                logger.info { "connecting to electrum server=$electrumServerAddress" }
                                electrumClient.socketBuilder = tcpSocketBuilder()
                                electrumClient.connect(electrumServerAddress)
                            }
                            _lastElectrumServerAddress.value = electrumServerAddress
                        }
                    }
                }
            }
        }

        // HTTP APIs
        launch {
            httpApiControlFlow.collect {
                when {
                    it.internetIsAvailable && it.disconnectCount <= 0 -> {
                        if (!httpControlFlowEnabled) {
                            httpControlFlowEnabled = true
                            configurationManager.enableNetworkAccess()
                            currencyManager.enableNetworkAccess()
                        }
                    }
                    else -> {
                        if (httpControlFlowEnabled) {
                            httpControlFlowEnabled = false
                            configurationManager.disableNetworkAccess()
                            currencyManager.disableNetworkAccess()
                        }
                    }
                }
            }
        }

        // Listen to electrum configuration changes and reconnect when needed.
        launch {
            var previousElectrumConfig: ElectrumConfig? = null
            configurationManager.electrumConfig.collect { newElectrumConfig ->
                logger.info { "electrum config changed from=$previousElectrumConfig to $newElectrumConfig" }
                val changed = when (val oldElectrumConfig = previousElectrumConfig) {
                    null -> newElectrumConfig != null
                    else -> newElectrumConfig != oldElectrumConfig
                }
                if (changed) {
                    logger.info { "electrum config changed: reconnecting..." }
                    electrumControlChanges.send { copy(configVersion = configVersion + 1) }
                } else {
                    logger.info { "electrum config: no changes" }
                }
                previousElectrumConfig = newElectrumConfig
            }
        }
    }

    data class ControlTarget(val flags: Int) { // <- bitmask

        companion object {
            val Peer = ControlTarget(0b0001)
            val Electrum = ControlTarget(0b0010)
            val Http = ControlTarget(0b0100)
            val Tor = ControlTarget(0b1000)
            val All = ControlTarget(0b1111)
        }

        /* The `+` operator is implemented, so it can be used like so:
         * `val options = ControlTarget.Peer + ControlTarget.Electrum`
         */
        operator fun plus(other: ControlTarget): ControlTarget {
            return ControlTarget(this.flags or other.flags)
        }

        fun contains(options: ControlTarget): Boolean {
            return (this.flags and options.flags) != 0
        }

        val containsPeer get() = contains(Peer)
        val containsElectrum get() = contains(Electrum)
        val containsHttp get() = contains(Http)
        val containsTor get() = contains(Tor)
    }

    fun incrementDisconnectCount(target: ControlTarget = ControlTarget.All) {
        launch {
            if (target.containsPeer) {
                peerControlChanges.send { incrementDisconnectCount() }
            }
            if (target.containsElectrum) {
                electrumControlChanges.send { incrementDisconnectCount() }
            }
            if (target.containsHttp) {
                httpApiControlChanges.send { incrementDisconnectCount() }
            }
            if (target.containsTor) {
                torControlChanges.send { incrementDisconnectCount() }
            }
        }
    }

    fun decrementDisconnectCount(target: ControlTarget = ControlTarget.All) {
        launch {
            if (target.containsPeer) {
                peerControlChanges.send { decrementDisconnectCount() }
            }
            if (target.containsElectrum) {
                electrumControlChanges.send { decrementDisconnectCount() }
            }
            if (target.containsHttp) {
                httpApiControlChanges.send { decrementDisconnectCount() }
            }
            if (target.containsTor) {
                torControlChanges.send { decrementDisconnectCount() }
            }
        }
    }

    private fun connectionLoop(
        name: String,
        statusStateFlow: StateFlow<Connection>,
        connect: suspend () -> Unit
    ) = launch {
        var pause = Duration.ZERO
        statusStateFlow.collect {
            if (it is Connection.CLOSED) {
                logger.info { "next $name connection attempt in $pause" }
                delay(pause)
                val minPause = 0.25.seconds
                val maxPause = 60.seconds
                pause = (pause.coerceAtLeast(minPause) * 2).coerceAtMost(maxPause)
                connect()
            } else if (it == Connection.ESTABLISHED) {
                pause = 0.5.seconds
            }
        }
    }
}

/** The start function must run on a different dispatcher depending on the platform. */
expect suspend fun Tor.startInProperScope(scope: CoroutineScope)
