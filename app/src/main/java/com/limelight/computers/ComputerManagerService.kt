@file:Suppress("DEPRECATION")
package com.limelight.computers

import java.io.IOException
import java.io.StringReader
import java.net.Inet4Address
import java.net.InetAddress
import java.util.HashSet
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

import com.limelight.LimeLog
import com.limelight.binding.PlatformBinding
import com.limelight.discovery.DiscoveryService
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.HostHttpResponseException
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.http.PairStateTrust
import com.limelight.nvstream.mdns.MdnsComputer
import com.limelight.nvstream.mdns.MdnsDiscoveryListener
import com.limelight.preferences.PreferenceConfiguration
import com.limelight.utils.CacheHelper
import com.limelight.utils.HostCacheKey
import com.limelight.utils.NetHelper
import com.limelight.utils.ServerHelper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.preference.PreferenceManager

import org.xmlpull.v1.XmlPullParserException

class ComputerManagerService : Service() {
    enum class PairStateVerificationResult {
        VERIFIED_PAIRED,
        NOT_PAIRED,
        UNKNOWN
    }

    private val binder = ComputerManagerBinder()

    private lateinit var dbManager: ComputerDatabaseManager
    private val dbRefCount = AtomicInteger(0)

    private lateinit var idManager: IdentityManager
    private val pollingTuples = LinkedList<PollingTuple>()

    /** Guards against overlapping USB card probes; see requestUsbLinkCard(). */
    private val usbCardJobActive = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Last successful USB probe, and when it ran.
     *
     * The post-poll hook fires on every poll of every card, i.e. several times a
     * second. Without this the TCP probe to the PC would run just as often, which
     * is pointless traffic and shows up as a burst of connects every polling
     * period. The DHCP lease cannot move faster than this anyway.
     */
    private var lastUsbProbeMs = 0L
    private var lastUsbPeerAddress: String? = null
    private val activePollFlights = ConcurrentHashMap<String, PollFlight>()
    private val recentPollResults = ConcurrentHashMap<String, RecentPoll>()

    // Flow 版数据流。所有计算机状态更新从这里发射。
    private val _computerUpdates = MutableSharedFlow<ComputerDetails>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val computerUpdates: SharedFlow<ComputerDetails> = _computerUpdates.asSharedFlow()

    private val activePolls = AtomicInteger(0)
    @Volatile
    private var pollingActive = false
    @Volatile
    private var foregroundComputerUuid: String? = null
    private val stunCache = StunQueryCache<Network>()
    @Volatile private var stunJob: Job? = null
    private val preferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val stunPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == PreferenceConfiguration.ENABLE_STUN_PREF_STRING) {
            invalidateStunQuery()
        }
    }

    // Service 生命周期作用域，用于所有后台协程（轮询、STUN 等）。onDestroy 时 cancel。
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    // 网络诊断和动态超时管理
    private lateinit var networkDiagnostics: NetworkDiagnostics
    private var timeoutManager: DynamicTimeoutManager? = null

    private var discoveryBinder: DiscoveryService.DiscoveryBinder? = null
    private val discoveryServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            synchronized(this) {
                val privateBinder = binder as DiscoveryService.DiscoveryBinder
                privateBinder.setListener(createDiscoveryListener())
                discoveryBinder = privateBinder
                // startPollingInternal() 在 DiscoveryService 连接完成前运行时（首次冷启动的常态，
                // PcView 为加速首帧不再等 waitForReady），那里的 discoveryBinder?.startDiscovery()
                // 会静默跳过。binder 到达时轮询已在进行，就必须在这里补上，否则整个会话
                // 都不会触发 mDNS 自动发现（onResume 的 startPolling 因 polling 已激活而早退）。
                if (pollingActive) {
                    LimeLog.info("DiscoveryService connected after polling active; starting deferred discovery")
                    privateBinder.startDiscovery(MDNS_QUERY_PERIOD_MS)
                }
                (this as Object).notifyAll()
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            discoveryBinder = null
        }
    }

    // Returns true if the details object was modified
    @Throws(InterruptedException::class)
    private fun runPoll(details: ComputerDetails, newPc: Boolean, offlineCount: Int): Boolean {
        if (!getLocalDatabaseReference()) {
            return false
        }

        val pollTriesBeforeOffline = if (details.state == ComputerDetails.State.UNKNOWN)
            INITIAL_POLL_TRIES else OFFLINE_POLL_TRIES

        activePolls.incrementAndGet()

        try {
            if (!pollComputer(details)) {
                if (!newPc && offlineCount < pollTriesBeforeOffline) {
                    releaseLocalDatabaseReference()
                    return false
                }
                details.state = ComputerDetails.State.OFFLINE
            }
        } catch (e: InterruptedException) {
            releaseLocalDatabaseReference()
            throw e
        } finally {
            activePolls.decrementAndGet()
        }

        if (details.state == ComputerDetails.State.ONLINE) {
            val existingComputer = dbManager.getComputerByUUID(details.uuid!!)

            if (!newPc && existingComputer == null) {
                releaseLocalDatabaseReference()
                return false
            }

            val updatedComputer = existingComputer ?: details
            existingComputer?.update(details)
            // The normal poll owns host updates; the STUN worker publishes only an immutable cache value.
            if (updatedComputer.canRefreshRemoteAddressWithStun) {
                cachedStunAddress(details)?.let(updatedComputer::updateStunRemoteAddress)
            }
            details.remoteAddress = updatedComputer.remoteAddress?.let { ComputerDetails.AddressTuple(it.address, it.port) }
            details.remoteAddressFromStun = updatedComputer.remoteAddressFromStun
            dbManager.updateComputer(updatedComputer)
        }

        if (!newPc || details.state == ComputerDetails.State.ONLINE) {
            emitComputerUpdate(details)
        }

        // A poll is what first reveals the host's uuid, and the USB card is derived
        // from it. So this is the earliest point the USB card can be built for a
        // host we did not already know about.
        //
        // Dispatched rather than run inline: UsbTetherProbe.probe() opens a TCP
        // connection with a 1.5s timeout, and runPoll() is on the caller's critical
        // path (it runs inside a runInterruptible that holds the tuple's network
        // lock). Blocking there would stall every other card's polling too.
        if (!details.isUsbLink && details.uuid != null && details.state == ComputerDetails.State.ONLINE) {
            requestUsbLinkCard()
        }

        releaseLocalDatabaseReference()
        return true
    }

    private fun startPollingInternal() {
        pollingActive = true
        discoveryBinder?.startDiscovery(MDNS_QUERY_PERIOD_MS)

        // Before polling starts, give the USB tethering link its own card. Doing it
        // here (rather than in the mDNS callback) means the card exists on a cold
        // start, before any discovery has run.
        requestUsbLinkCard()

        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (SystemClock.elapsedRealtime() - tuple.lastSuccessfulPollMs > POLL_DATA_TTL_MS) {
                    LimeLog.info("Timing out polled state for ${tuple.computer.name}")
                    tuple.computer.state = ComputerDetails.State.UNKNOWN
                }
                emitComputerUpdate(tuple.computer)
                if (tuple.job == null) {
                    tuple.job = createPollingJob(tuple)
                }
            }
        }
    }

    /**
     * Runs [maybeAddUsbLinkComputer] off the caller's thread and coalesces bursts.
     *
     * Two callers (startPolling and post-poll) can fire in quick succession; without
     * coalescing the second run would probe the same address again and could race
     * with the first over the same tuple.
     */
    private fun requestUsbLinkCard() {
        if (!usbCardJobActive.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    maybeAddUsbLinkComputer()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                LimeLog.warning("USB link card update failed: ${e.message}")
            } finally {
                usbCardJobActive.set(false)
            }
        }
    }

    /**
     * Creates (or refreshes) a second card for the PC reachable over USB.
     *
     * Why a derived uuid is required: addTuple() de-duplicates on uuid, and
     * runPoll() looks the row up by uuid too. A USB card carrying the host's real
     * uuid would therefore be folded into the WiFi card -- which is exactly what
     * an earlier attempt did, producing one card whose address silently flipped
     * between the two links.
     *
     * The derived id keeps the two apart while remaining deterministic, so the USB
     * card is stable across restarts and keeps its own database row (and therefore
     * its own certificate) without a second pairing.
     *
     * The card holds ONLY the USB address. That matters: parallelPollPc() races
     * every candidate address and keeps the fastest, so a USB card that also
     * carried the WiFi address could quietly fall back to WiFi -- and the user
     * would be back to the 40 MB/s problem while looking at a card labelled USB.
     */
    private fun maybeAddUsbLinkComputer() {
        val now = SystemClock.elapsedRealtime()
        val cachedAddress = lastUsbPeerAddress

        // How long to wait before probing again. The full window applies only once
        // the card actually exists: the probe succeeds on the very first call of a
        // cold start (the tether link is up) but the card cannot be built yet,
        // because the host uuid only arrives with the first successful poll. Using
        // the long window there would leave the USB card missing for 15s with no
        // explanation, so an unbuilt card retries on the poll cadence instead.
        val cardExists = synchronized(pollingTuples) {
            pollingTuples.any { it.computer.isUsbLink }
        }
        val minInterval = if (cardExists) USB_PROBE_MIN_INTERVAL_MS else USB_PROBE_RETRY_MS

        if (cachedAddress != null && now - lastUsbProbeMs < minInterval) {
            if (cachedAddress.isNotEmpty()) {
                applyUsbLinkCard(ComputerDetails.AddressTuple(cachedAddress, NvHTTP.DEFAULT_HTTP_PORT))
            }
            return
        }
        lastUsbProbeMs = now

        val usbPeer = UsbTetherProbe.probe(this)
        lastUsbPeerAddress = usbPeer?.address

        if (usbPeer == null || usbPeer.linkUpButNoPeer) {
            removeStaleUsbLinkComputer()
            return
        }

        applyUsbLinkCard(ComputerDetails.AddressTuple(usbPeer.address, NvHTTP.DEFAULT_HTTP_PORT))
    }

    /**
     * Creates or refreshes the USB card for [usbAddress].
     *
     * Split out of the probe so the rate-limited path can rebuild the card without
     * touching the network again.
     */
    private fun applyUsbLinkCard(usbAddress: ComputerDetails.AddressTuple) {

        // Find the WiFi/primary card for this host. The USB card mirrors it, so it
        // needs a host to mirror: without one there is nothing to be a second card
        // *of*, and the user has not added this PC manually yet.
        val primary = synchronized(pollingTuples) {
            pollingTuples.firstOrNull { !it.computer.isUsbLink }
        }

        // The host uuid is null until the first poll returns (it comes from the
        // server), so on a cold start there is nothing to derive from yet. Deriving
        // from a placeholder would mint "usb:unknown-host" and, because the derived
        // uuid must stay stable, never fix it afterwards. Wait for the real one --
        // the next startPolling() calls this again, by which point it is known.
        val hostUuid = primary?.computer?.uuid
        if (hostUuid.isNullOrEmpty()) {
            LimeLog.info("USB link present (${usbAddress.address}) but the host uuid is not known yet; deferring USB card")
            return
        }

        val usbDetails = ComputerDetails()
        usbDetails.uuid = usbUuidFor(hostUuid)
        usbDetails.name = primary?.computer?.name ?: "PC (USB)"
        usbDetails.isUsbLink = true
        // Only the USB address. See the note above: adding more would let
        // parallelPollPc() pick a slower address and defeat the point.
        usbDetails.localAddress = usbAddress
        usbDetails.manualAddress = usbAddress
        usbDetails.activeAddress = usbAddress
        usbDetails.availableAddresses = ArrayList(listOf(usbAddress))
        // Same host, same certificate, no second pairing.
        usbDetails.serverCert = primary?.computer?.serverCert
        usbDetails.httpsPort = primary?.computer?.httpsPort
            ?: NvHTTP.DEFAULT_HTTPS_PORT
        usbDetails.state = ComputerDetails.State.ONLINE

        val existingUsb = synchronized(pollingTuples) {
            pollingTuples.firstOrNull { it.computer.isUsbLink }
        }

        if (existingUsb != null) {
            // Keep the live certificate/pair state; only the address can change
            // (the DHCP lease moves when the cable is replugged).
            existingUsb.computer.update(usbDetails)
            existingUsb.computer.serverCert = usbDetails.serverCert ?: existingUsb.computer.serverCert
            existingUsb.computer.isUsbLink = true
            emitComputerUpdate(existingUsb.computer)
            return
        }

        // Persist before polling so runPoll() finds a row by uuid and does not
        // treat the card as unknown.
        if (!getLocalDatabaseReference()) return
        try {
            if (primary != null && primary.computer.serverCert != null &&
                usbDetails.serverCert == null) {
                usbDetails.serverCert = primary.computer.serverCert
            }
            dbManager.updateComputer(usbDetails)
        } finally {
            releaseLocalDatabaseReference()
        }

        LimeLog.info("Added USB link card for ${usbDetails.name} at ${usbAddress.address} (uuid ${usbDetails.uuid})")
        addTuple(usbDetails)
    }

    /**
     * Deterministic uuid for the USB twin of [hostUuid].
     *
     * Derived rather than random so the card keeps its identity (and its stored
     * certificate) across app restarts and cable replugs. The "usb:" prefix also
     * makes these rows obvious in a database dump.
     */
    private fun usbUuidFor(hostUuid: String?): String {
        val base = hostUuid ?: "unknown-host"
        return USB_UUID_PREFIX + base
    }

    /**
     * Whether a polled host uuid belongs to the card identified by [cardUuid].
     *
     * A USB card stores a derived id ("usb:" + the host's real uuid) so it does not
     * collide with the primary card. The server, of course, only ever reports the
     * real one -- so comparing the two verbatim would fail every time and the card
     * would never come online. Strip the prefix before comparing.
     */
    private fun uuidMatchesHost(cardUuid: String, hostUuid: String?): Boolean {
        if (hostUuid == null) return false
        if (cardUuid == hostUuid) return true
        val base = cardUuid.removePrefix(USB_UUID_PREFIX)
        return base == hostUuid
    }

    /**
     * Drops the USB card when the cable is gone.
     *
     * Leaving it would show a card that can never connect, which is worse than
     * showing none -- the user would keep picking it and wondering why it fails.
     *
     * The UI has no "a computer vanished" signal: it only ever receives add/update
     * events, and removal is an explicit user action (long-press -> remove). So the
     * removal is emitted through the existing removeComputer() path rather than
     * invented here, otherwise the stale card would stay on screen forever.
     */
    private fun removeStaleUsbLinkComputer() {
        val stale = synchronized(pollingTuples) {
            pollingTuples.filter { it.computer.isUsbLink }
        }
        if (stale.isEmpty()) return

        for (tuple in stale) {
            tuple.job?.cancel()
            synchronized(pollingTuples) {
                pollingTuples.remove(tuple)
            }
        }
        LimeLog.info("Removed ${stale.size} USB link card(s): tether link is gone")

        // Wipe the persisted row too, otherwise the next launch reloads it from
        // the database and the card reappears before the probe has run.
        for (tuple in stale) {
            try {
                removeComputerFromDb(tuple.computer)
            } catch (e: Exception) {
                LimeLog.warning("Failed to delete USB card row: ${e.message}")
            }
        }
    }

    /** Deletes a computer's row; separated so the USB path can log its own errors. */
    private fun removeComputerFromDb(details: ComputerDetails) {
        if (!getLocalDatabaseReference()) return
        try {
            dbManager.deleteComputer(details)
        } finally {
            releaseLocalDatabaseReference()
        }
    }

    private fun createPollingJob(tuple: PollingTuple): Job = serviceScope.launch {
        var offlineCount = 0
        while (isActive && pollingActive && tuple.job === coroutineContext[Job]) {
            try {
                val polled = runInterruptible {
                    synchronized(tuple.networkLock) {
                        runPoll(tuple.computer, false, offlineCount)
                    }
                }
                if (!polled) {
                    LimeLog.warning("${tuple.computer.name} is offline (try $offlineCount)")
                    offlineCount++
                } else {
                    tuple.lastSuccessfulPollMs = SystemClock.elapsedRealtime()
                    offlineCount = 0
                    populateExternalAddress(tuple.computer)
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
            try {
                delay(getNextPollingDelayMs(tuple, offlineCount))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            }
        }
    }

    private fun getNextPollingDelayMs(tuple: PollingTuple, offlineCount: Int): Long {
        val focusedUuid = foregroundComputerUuid ?: return SERVERINFO_POLLING_PERIOD_MS.toLong()
        val uuid = tuple.computer.uuid

        if (uuid == null || uuid == focusedUuid) {
            return SERVERINFO_POLLING_PERIOD_MS.toLong()
        }

        if (tuple.computer.state != ComputerDetails.State.ONLINE || offlineCount in 1 until OFFLINE_POLL_TRIES) {
            return SERVERINFO_POLLING_PERIOD_MS.toLong()
        }

        LimeLog.info("AppView foreground $focusedUuid; slowing background poll for ${tuple.computer.name}")
        return APPVIEW_BACKGROUND_POLLING_PERIOD_MS
    }

    inner class ComputerManagerBinder : Binder() {
        /**
         * Flow 版的计算机更新流。调用 [startPolling] 后，所有轮询结果都会从这里发射。
         */
        val computerUpdates: SharedFlow<ComputerDetails>
            get() = this@ComputerManagerService.computerUpdates

        fun startPolling() {
            startPollingInternal()
        }

        fun waitForReady() {
            synchronized(discoveryServiceConnection) {
                try {
                    while (discoveryBinder == null) {
                        (discoveryServiceConnection as Object).wait(1000)
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
            }
        }

        fun waitForPollingStopped() {
            while (activePolls.get() != 0) {
                try {
                    Thread.sleep(250)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }

        @Throws(InterruptedException::class)
        fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
            return this@ComputerManagerService.addComputerBlocking(fakeDetails)
        }

        fun removeComputer(computer: ComputerDetails) {
            this@ComputerManagerService.removeComputer(computer)
        }

        fun updateComputer(computer: ComputerDetails) {
            this@ComputerManagerService.updateComputer(computer)
        }

        fun markComputerNotPaired(computer: ComputerDetails, source: String) {
            this@ComputerManagerService.markComputerNotPaired(computer, source)
        }

        fun verifyCurrentPairState(computer: ComputerDetails, source: String): PairStateVerificationResult {
            return this@ComputerManagerService.verifyCurrentPairState(computer, source)
        }

        fun stopPolling() {
            this@ComputerManagerService.onUnbind(null)
        }

        fun createAppListPoller(computer: ComputerDetails): ApplistPoller {
            return ApplistPoller(computer)
        }

        fun setForegroundComputer(uuid: String?) {
            if (uuid.isNullOrEmpty()) {
                clearForegroundComputer(null)
                return
            }

            if (foregroundComputerUuid != uuid) {
                foregroundComputerUuid = uuid
                LimeLog.info("AppView foreground computer set to $uuid; background polling will slow down")
            }
        }

        fun clearForegroundComputer(uuid: String?) {
            if (uuid == null || foregroundComputerUuid == uuid) {
                if (foregroundComputerUuid != null) {
                    LimeLog.info("AppView foreground computer cleared; background polling restored")
                }
                foregroundComputerUuid = null
            }
        }

        fun getUniqueId(): String {
            return idManager.uniqueId
        }

        fun getComputer(uuid: String): ComputerDetails? {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (uuid == tuple.computer.uuid) {
                        return tuple.computer
                    }
                }
            }
            return null
        }

        fun invalidateStateForComputer(uuid: String) {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (uuid == tuple.computer.uuid) {
                        synchronized(tuple.networkLock) {
                            tuple.computer.state = ComputerDetails.State.UNKNOWN
                        }
                    }
                }
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        discoveryBinder?.stopDiscovery()

        pollingActive = false
        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                tuple.job?.cancel()
                tuple.job = null
            }
        }

        return false
    }

    private fun populateExternalAddress(details: ComputerDetails) {
        if (!stunEnabled() || !details.canRefreshRemoteAddressWithStun || details.state != ComputerDetails.State.ONLINE) return
        try {
            val network = stunNetworkFor(details) ?: return
            val attempt = stunCache.begin(network, SystemClock.elapsedRealtime()) ?: return
            val job = serviceScope.launch(STUN_DISPATCHER, start = CoroutineStart.LAZY) {
                var result: String? = null
                try {
                    if (!stunEnabled() || !stunCache.isCurrent(attempt)) return@launch
                    val timeout = minOf(timeoutManager?.stunTimeout ?: 5000, 5000).toLong()
                    val context = coroutineContext
                    LimeLog.info("STUN query started on local network")
                    result = withTimeoutOrNull(timeout) {
                        val queryContext = coroutineContext
                        runInterruptible {
                            StunClient.query("stun.moonlight-stream.org", 3478, timeout,
                                network::getAllByName, network::bindSocket, queryContext::ensureActive)
                        }
                    }
                    context.ensureActive()
                    if (stunCache.finish(attempt, result, SystemClock.elapsedRealtime()) && result != null) {
                        LimeLog.info("STUN result ready for normal host poll")
                    } else {
                        LimeLog.info("STUN query unavailable; host polling continues independently")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    LimeLog.warning("STUN query failed; host polling continues independently")
                } finally {
                    stunCache.finish(attempt, result, SystemClock.elapsedRealtime())
                }
            }
            stunJob = job
            job.invokeOnCompletion { stunCache.finish(attempt, null, SystemClock.elapsedRealtime()) }
            job.start()
        } catch (_: RuntimeException) {
            LimeLog.warning("STUN local network unavailable")
        }
    }

    private fun stunEnabled(): Boolean =
        preferences.getBoolean(PreferenceConfiguration.ENABLE_STUN_PREF_STRING, false)

    private fun invalidateStunQuery() {
        stunCache.invalidate()
        stunJob?.cancel()
    }

    private fun stunNetworkFor(details: ComputerDetails): Network? {
        val host = details.activeAddress?.address ?: return null
        if (!NetHelper.isIpLiteral(host)) return null
        val localAddress = try { InetAddress.getByName(host) } catch (_: IOException) { return null }
        if (localAddress !is Inet4Address || !localAddress.isSiteLocalAddress) return null
        return selectStunNetwork(host)
    }

    private fun selectStunNetwork(host: String): Network? {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val hostAddress = InetAddress.getByName(host)
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) manager.activeNetwork else null
        return (listOfNotNull(active) + manager.allNetworks).distinct().firstOrNull { network ->
            manager.getNetworkCapabilities(network)?.let { caps ->
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            } == true && manager.getLinkProperties(network)?.routes?.any { route ->
                !route.isDefaultRoute && route.matches(hostAddress)
            } == true
        }
    }

    private fun cachedStunAddress(details: ComputerDetails): ComputerDetails.AddressTuple? {
        if (!stunEnabled()) return null
        return try {
            val network = stunNetworkFor(details) ?: return null
            val address = stunCache.cached(network, SystemClock.elapsedRealtime()) ?: return null
            ComputerDetails.AddressTuple(address, details.guessExternalPort())
        } catch (_: RuntimeException) {
            LimeLog.warning("STUN local network unavailable")
            null
        }
    }

    private fun createDiscoveryListener(): MdnsDiscoveryListener {
        return object : MdnsDiscoveryListener {
            override fun notifyComputerAdded(computer: MdnsComputer) {
                val details = ComputerDetails()

                if (computer.getLocalAddress() != null) {
                    details.localAddress = ComputerDetails.AddressTuple(computer.getLocalAddress()!!.hostAddress!!, computer.getPort())
                }
                if (computer.getIpv6Address() != null) {
                    details.ipv6Address = ComputerDetails.AddressTuple(computer.getIpv6Address()!!.hostAddress!!, computer.getPort())
                }

                // Discovery can report the host over the tether link, whose address
                // belongs to the USB twin card. Adding it here would show the user a
                // third card for one machine, flickering as the two race for it.
                if (isDuplicateOfExistingCard(details)) {
                    return
                }

                try {
                    if (!addComputerBlocking(details)) {
                        LimeLog.warning("Auto-discovered PC failed to respond: $details")
                    }
                } catch (e: InterruptedException) {
                    e.printStackTrace()
                    Thread.currentThread().interrupt()
                }
            }

            override fun notifyDiscoveryFailure(e: Exception) {
                LimeLog.severe("mDNS discovery failed")
                e.printStackTrace()
            }
        }
    }

    /**
     * True when [incoming] is a PC we already track under a different card.
     *
     * mDNS reports the host by its real uuid and by whatever address the responder
     * happened to use. Over the USB tether that address is the tether one, which is
     * exactly the address the USB twin card holds -- so discovery would look like a
     * brand new PC and add a duplicate, giving the user three cards for one machine.
     *
     * Comparing on address (not uuid) is deliberate: the incoming details have the
     * host's real uuid while the USB card holds a derived one, so a uuid compare
     * would never match and the duplicate would slip through.
     */
    private fun isDuplicateOfExistingCard(incoming: ComputerDetails): Boolean {
        val incomingAddresses = setOfNotNull(
            incoming.localAddress?.toString(),
            incoming.manualAddress?.toString(),
            incoming.remoteAddress?.toString()
        )
        if (incomingAddresses.isEmpty()) return false

        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                val existing = tuple.computer
                // Same machine already on the other cable: the USB twin. Reuse it
                // instead of adding a card that would race it for the same address.
                if (existing.isUsbLink) {
                    val existingAddresses = setOfNotNull(
                        existing.activeAddress?.toString(),
                        existing.localAddress?.toString(),
                        existing.manualAddress?.toString()
                    )
                    if (incomingAddresses.any { it in existingAddresses }) {
                        LimeLog.info(
                            "Ignoring mDNS duplicate: ${incomingAddresses.first()} is already on USB card ${existing.uuid}"
                        )
                        return true
                    }
                }
                // Same uuid under a non-derived id: the ordinary card, already found.
                if (!existing.isUsbLink && existing.uuid != null && existing.uuid == incoming.uuid) {
                    return true
                }
            }
        }
        return false
    }

    private fun addTuple(details: ComputerDetails) {
        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (tuple.computer.uuid == details.uuid) {
                    tuple.computer.update(details)
                    if (pollingActive && tuple.job == null) {
                        tuple.job = createPollingJob(tuple)
                    }
                    return
                }
            }

            val tuple = PollingTuple(details, null)
            if (pollingActive) {
                tuple.job = createPollingJob(tuple)
            }
            pollingTuples.add(tuple)
        }
    }

    @Throws(InterruptedException::class)
    fun addComputerBlocking(fakeDetails: ComputerDetails): Boolean {
        if (pollComputer(fakeDetails)) {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (tuple.computer.uuid == fakeDetails.uuid) {
                        fakeDetails.serverCert = tuple.computer.serverCert
                        break
                    }
                }
            }
            runPoll(fakeDetails, true, 0)
        }

        if (fakeDetails.state == ComputerDetails.State.ONLINE) {
            LimeLog.info("New PC (${fakeDetails.name}) is UUID ${fakeDetails.uuid}")
            addTuple(fakeDetails)
            populateExternalAddress(fakeDetails)
            return true
        }
        return false
    }

    fun removeComputer(computer: ComputerDetails) {
        if (!getLocalDatabaseReference()) return

        dbManager.deleteComputer(computer)

        synchronized(pollingTuples) {
            val iterator = pollingTuples.iterator()
            while (iterator.hasNext()) {
                val tuple = iterator.next()
                if (tuple.computer.uuid == computer.uuid) {
                    tuple.job?.cancel()
                    tuple.job = null
                    iterator.remove()
                    break
                }
            }
        }

        releaseLocalDatabaseReference()
    }

    fun updateComputer(computer: ComputerDetails) {
        if (!getLocalDatabaseReference()) return

        dbManager.updateComputer(computer)

        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (tuple.computer.uuid == computer.uuid) {
                    tuple.computer.update(computer)
                    break
                }
            }
        }

        releaseLocalDatabaseReference()
    }

    private fun markComputerNotPaired(computer: ComputerDetails, source: String) {
        val uuid = computer.uuid
        LimeLog.warning("$source reported not paired for ${computer.name ?: uuid}; marking host not paired")

        var canonicalComputer = computer
        var canonicalTuple: PollingTuple? = null
        synchronized(pollingTuples) {
            if (uuid != null) {
                for (tuple in pollingTuples) {
                    if (tuple.computer.uuid == uuid) {
                        canonicalComputer = tuple.computer
                        canonicalTuple = tuple
                        break
                    }
                }
            }
        }

        val keysToClear = (getPollKeys(computer) + getPollKeys(canonicalComputer)).distinct()
        val tuple = canonicalTuple
        if (tuple != null) {
            synchronized(tuple.networkLock) {
                tuple.pairStateEpoch++
                applyNotPairedState(computer)
                if (canonicalComputer !== computer) {
                    applyNotPairedState(canonicalComputer)
                }
            }
        } else {
            applyNotPairedState(computer)
        }

        for (key in keysToClear) {
            recentPollResults.remove(key)
            activePollFlights.remove(key)
        }
        uuid?.let { rawUuid ->
            HostCacheKey.fromUuid(rawUuid)?.let { cacheKey ->
                CacheHelper.deleteCacheFile(cacheDir, "applist", cacheKey)
            }
            sendAppListWidgetRefresh(rawUuid)
        }

        if (getLocalDatabaseReference()) {
            try {
                dbManager.updateComputer(canonicalComputer)
            } finally {
                releaseLocalDatabaseReference()
            }
        }

        emitComputerUpdate(canonicalComputer)
    }

    private fun verifyCurrentPairState(computer: ComputerDetails, source: String): PairStateVerificationResult {
        return try {
            val httpConn = NvHTTP(
                ServerHelper.getCurrentAddressFromComputer(computer),
                computer.httpsPort,
                idManager.uniqueId,
                "",
                computer.serverCert,
                PlatformBinding.getCryptoProvider(this)
            )
            val polled = httpConn.getComputerDetails(true)
            when {
                polled.pairState == PairingManager.PairState.PAIRED && polled.serverInfoTrustedByCert -> {
                    markComputerVerifiedPaired(computer, polled)
                    PairStateVerificationResult.VERIFIED_PAIRED
                }
                PairStateTrust.isTrustedNotPaired(polled) -> {
                    markComputerNotPaired(computer, source)
                    PairStateVerificationResult.NOT_PAIRED
                }
                else -> {
                    LimeLog.warning("$source pair-state verification was not trusted for ${computer.name}")
                    PairStateVerificationResult.UNKNOWN
                }
            }
        } catch (e: HostHttpResponseException) {
            LimeLog.warning("$source pair-state verification failed for ${computer.name}: ${e.message}")
            PairStateVerificationResult.UNKNOWN
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LimeLog.warning("$source pair-state verification interrupted for ${computer.name}")
            PairStateVerificationResult.UNKNOWN
        } catch (e: Exception) {
            LimeLog.warning("$source pair-state verification skipped for ${computer.name}: ${e.message}")
            PairStateVerificationResult.UNKNOWN
        }
    }

    private fun markComputerVerifiedPaired(computer: ComputerDetails, verified: ComputerDetails) {
        verified.serverCert = computer.serverCert
        verified.activeAddress = computer.activeAddress

        val uuid = computer.uuid
        var canonicalComputer = computer
        synchronized(pollingTuples) {
            if (uuid != null) {
                for (tuple in pollingTuples) {
                    if (tuple.computer.uuid == uuid) {
                        canonicalComputer = tuple.computer
                        synchronized(tuple.networkLock) {
                            tuple.computer.update(verified)
                        }
                        break
                    }
                }
            }
        }

        computer.update(verified)
        emitComputerUpdate(canonicalComputer)
    }

    private fun applyNotPairedState(computer: ComputerDetails) {
        computer.state = ComputerDetails.State.ONLINE
        computer.pairState = PairingManager.PairState.NOT_PAIRED
        computer.serverCert = null
        computer.rawAppList = null
        computer.serverInfoTrustedByCert = false
        computer.pairStateTrusted = false
    }

    private fun sendAppListWidgetRefresh(computerUuid: String) {
        val refreshIntent = Intent(com.limelight.widget.GameListWidgetProvider.ACTION_REFRESH_WIDGET)
        refreshIntent.component = ComponentName(this, com.limelight.widget.GameListWidgetProvider::class.java)
        refreshIntent.putExtra(com.limelight.widget.GameListWidgetProvider.EXTRA_COMPUTER_UUID, computerUuid)
        sendBroadcast(refreshIntent)
    }

    private fun getLocalDatabaseReference(): Boolean {
        if (dbRefCount.get() == 0) return false
        dbRefCount.incrementAndGet()
        return true
    }

    private fun releaseLocalDatabaseReference() {
        if (dbRefCount.decrementAndGet() == 0) {
            dbManager.close()
        }
    }

    private fun emitComputerUpdate(details: ComputerDetails) {
        val trustedDetails = PairStateTrust.sanitizePollResult(details, details, "update")
        if (trustedDetails !== details) {
            details.update(trustedDetails)
        }
        _computerUpdates.tryEmit(details)
    }

    private fun toNvHttpTimeoutConfig(timeoutConfig: DynamicTimeoutManager.TimeoutConfig?): NvHTTP.TimeoutConfig {
        if (timeoutConfig == null) {
            return NvHTTP.TimeoutConfig.DEFAULT
        }

        return NvHTTP.TimeoutConfig(
            connectTimeoutMs = timeoutConfig.connectTimeout,
            readTimeoutMs = timeoutConfig.readTimeout,
            shortConnectTimeoutMs = minOf(timeoutConfig.connectTimeout, NvHTTP.SHORT_CONNECTION_TIMEOUT)
        )
    }

    private fun getPollKeys(details: ComputerDetails): List<String> {
        val keys = ArrayList<String>()
        details.uuid?.let { keys.add("uuid:$it") }

        val addressKey = listOfNotNull(
            details.activeAddress,
            details.localAddress,
            details.manualAddress,
            details.remoteAddress,
            details.ipv6Address
        )
            .distinct()
            .sortedBy { it.toString() }
            .joinToString("|")

        if (addressKey.isNotEmpty()) {
            keys.add("addr:$addressKey")
        }

        return keys.distinct()
    }

    private fun getPrimaryPollKey(details: ComputerDetails): String? {
        return getPollKeys(details).firstOrNull()
    }

    private fun claimPollFlight(details: ComputerDetails): PollClaim? {
        val keys = getPollKeys(details)
        if (keys.isEmpty()) {
            return null
        }

        val flight = PollFlight()
        val claimedKeys = ArrayList<String>()
        for (key in keys) {
            val existing = activePollFlights.putIfAbsent(key, flight)
            if (existing != null) {
                for (claimedKey in claimedKeys) {
                    activePollFlights.remove(claimedKey, flight)
                }
                return PollClaim(emptyList(), existing, false)
            }
            claimedKeys.add(key)
        }

        return PollClaim(claimedKeys, flight, true)
    }

    private fun getFreshPollResult(details: ComputerDetails, nowMs: Long, pairStateEpoch: Long): ComputerDetails? {
        for (key in getPollKeys(details)) {
            val recent = recentPollResults[key] ?: continue
            if (recent.pairStateEpoch != pairStateEpoch) {
                recentPollResults.remove(key, recent)
                continue
            }
            val ageMs = nowMs - recent.pollTimeMs
            if (ageMs > POLL_RESULT_REUSE_MS) {
                recentPollResults.remove(key, recent)
                continue
            }

            if (recent.details.state == ComputerDetails.State.ONLINE &&
                recent.details.activeAddress != null
            ) {
                LimeLog.info("Fast poll: reusing fresh state for ${details.name ?: key} from ${ageMs}ms ago")
                return ComputerDetails(recent.details)
            }
        }

        return null
    }

    private fun findPollingTuple(details: ComputerDetails): PollingTuple? {
        synchronized(pollingTuples) {
            for (tuple in pollingTuples) {
                if (tuple.computer === details ||
                    (details.uuid != null && tuple.computer.uuid == details.uuid)
                ) {
                    return tuple
                }
            }
        }
        return null
    }

    private fun rememberPollResult(request: ComputerDetails, result: ComputerDetails, pollTimeMs: Long, pairStateEpoch: Long) {
        val snapshot = ComputerDetails(result)
        val keys = (getPollKeys(request) + getPollKeys(result)).distinct()
        for (key in keys) {
            recentPollResults[key] = RecentPoll(snapshot, pollTimeMs, pairStateEpoch)
        }

        findPollingTuple(result)?.lastNetworkPollMs = pollTimeMs
    }

    private fun tryPollIp(details: ComputerDetails, address: ComputerDetails.AddressTuple): ComputerDetails? {
        val startTime = System.currentTimeMillis()
        try {
            // 复用持久化的 HTTPS 端口可以让冷启动 first-poll 跳过"明文 serverinfo
            // 拿端口"那一次往返，直接走 HTTPS。守卫条件：
            //   - 必须有缓存值（httpsPort > 0）——新 PC / 旧记录退化到原路径
            //   - 当前探测的 address 必须就是上次成功的 activeAddress（同地址同端口）
            //     —— 跨地址复用是不安全的（同一台机器的 LAN/WAN 可能映射到不同 https 口）
            //
            // 老的 state == ONLINE 守卫是进程内热路径才有效，DB 加载后 state 是
            // UNKNOWN，会退化。现在 activeAddress 也持久化了，冷启动也能命中。
            val isLikelyOnline = details.state == ComputerDetails.State.ONLINE && address == details.activeAddress

            val timeoutConfig = timeoutManager?.getDynamicTimeoutConfig(address.address, isLikelyOnline)
            if (timeoutConfig != null) {
                LimeLog.info("Polling $address with timeout config: $timeoutConfig")
            }

            val httpsPortReusable = details.httpsPort != 0 && address == details.activeAddress

            val http = NvHTTP(
                address,
                if (httpsPortReusable) details.httpsPort else 0,
                idManager.uniqueId, "", details.serverCert,
                PlatformBinding.getCryptoProvider(this),
                toNvHttpTimeoutConfig(timeoutConfig)
            )

            val newDetails = http.getComputerDetails(isLikelyOnline)

            val hostUuid = newDetails.uuid
            if (hostUuid == null) {
                LimeLog.severe("Polling returned no UUID!")
                timeoutManager?.recordFailure(address.address)
                return null
            }
            // Local val, because `details.uuid` is a mutable property and Kotlin
            // refuses to smart-cast it across the call.
            val cardUuid = details.uuid
            if (cardUuid != null && !uuidMatchesHost(cardUuid, hostUuid)) {
                LimeLog.info("Polling returned the wrong PC!")
                timeoutManager?.recordFailure(address.address)
                return null
            }

            val responseTime = System.currentTimeMillis() - startTime
            timeoutManager?.recordSuccess(address.address, responseTime)
            LimeLog.info("Poll success for $address in ${responseTime}ms")

            return newDetails
        } catch (e: XmlPullParserException) {
            e.printStackTrace()
            timeoutManager?.recordFailure(address.address)
            return null
        } catch (e: IOException) {
            timeoutManager?.recordFailure(address.address)
            return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            timeoutManager?.recordFailure(address.address)
            return null
        }
    }

    @Throws(InterruptedException::class)
    private fun parallelPollPc(details: ComputerDetails): ComputerDetails? {
        val candidates = listOfNotNull(
            details.localAddress,
            details.manualAddress,
            details.remoteAddress,
            details.ipv6Address
        ).distinct()

        if (candidates.isEmpty()) return null

        val diagnostics = networkDiagnostics?.getLastDiagnostics()
        val skipLan = diagnostics?.networkType == NetworkDiagnostics.NetworkType.WAN ||
                diagnostics?.networkType == NetworkDiagnostics.NetworkType.MOBILE
        val orderedCandidates = candidates.sortedBy { if (it == details.activeAddress) 0 else 1 }
        val probeCandidates = orderedCandidates.filter { addr ->
            @Suppress("DEPRECATION")
            val isLan = NetworkDiagnostics.isLanAddress(addr.address)
            val isLearnedAddress = addr == details.remoteAddress || addr == details.ipv6Address
            if (isLan && skipLan && isLearnedAddress) {
                LimeLog.info("Skipping learned LAN address $addr on WAN/MOBILE network")
                false
            } else {
                val isLikelyOnline = details.state == ComputerDetails.State.ONLINE && addr == details.activeAddress
                val cooldownMs = timeoutManager?.getProbeCooldownRemainingMs(addr.address, isLikelyOnline) ?: 0
                if (cooldownMs > 0) {
                    LimeLog.info("Skipping $addr for ${cooldownMs}ms due to probe cooldown")
                    false
                } else {
                    true
                }
            }
        }

        if (probeCandidates.isEmpty()) {
            LimeLog.info("Fast poll: all candidate addresses are cooling down or skipped")
            return null
        }

        // 使用 runBlocking 在当前（已是后台）线程中构造结构化并发作用域；
        // 调用方要么在 Dispatchers.IO 协程的 runInterruptible 内，要么在 mDNS 发现线程，均非主线程。
        return runBlocking {
            val results = kotlinx.coroutines.channels.Channel<Pair<ComputerDetails.AddressTuple, ComputerDetails?>>(probeCandidates.size)

            // 并发探测每个地址
            val probeJobs = probeCandidates.map { addr ->
                launch(Dispatchers.IO) {
                    val polled = try {
                        runInterruptible { tryPollIp(details, addr) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LimeLog.warning("Poll exception for $addr: ${e.message}")
                        null
                    }
                    results.trySend(addr to polled)
                }
            }

            var result: ComputerDetails? = null
            var primaryAddress: ComputerDetails.AddressTuple? = null
            var firstResponseTime = 0L
            var completed = 0

            try {
                while (completed < probeCandidates.size) {
                    val pair = if (result != null) {
                        val remaining = COLLECTION_TIMEOUT_MS - (SystemClock.elapsedRealtime() - firstResponseTime)
                        if (remaining <= 0) {
                            LimeLog.info("Fast poll: collection window elapsed (collected ${result?.availableAddresses?.size} addresses)")
                            break
                        }
                        withTimeoutOrNull(remaining) { results.receiveCatching().getOrNull() }
                            ?: run {
                                LimeLog.info("Fast poll: timed out waiting for more responses")
                                null
                            }
                    } else {
                        results.receiveCatching().getOrNull()
                    } ?: break

                    completed++
                    val (addr, polled) = pair

                    if (polled != null) {
                        if (result == null) {
                            result = polled
                            primaryAddress = addr
                            polled.activeAddress = addr
                            polled.addAvailableAddress(addr)
                            firstResponseTime = SystemClock.elapsedRealtime()
                            LimeLog.info("Fast poll: got first response from address $addr")
                            val canReturnImmediately = addr == details.activeAddress &&
                                    (details.state == ComputerDetails.State.ONLINE ||
                                            timeoutManager?.isAddressHealthy(addr.address) == true)
                            if (canReturnImmediately) {
                                LimeLog.info("Fast poll: returning immediately for healthy active address $addr")
                                break
                            }
                        } else if (addr != primaryAddress &&
                            result!!.uuid != null && polled.uuid != null &&
                            result!!.uuid == polled.uuid &&
                            result!!.availableAddresses?.contains(addr) != true
                        ) {
                            result!!.addAvailableAddress(addr)
                            LimeLog.info("Fast poll: also got response from address $addr")
                        }
                    }
                }

                if (result == null) {
                    LimeLog.info("Fast poll: all addresses failed")
                }
            } finally {
                // 提前结束（拿够地址或超时）时取消未完成的探测
                probeJobs.forEach { it.cancel() }
                results.close()
            }

            result
        }
    }

    @Throws(InterruptedException::class)
    private fun pollComputer(details: ComputerDetails): Boolean {
        val tuple = findPollingTuple(details)
        val pairStateEpoch = tuple?.pairStateEpoch ?: 0L
        val nowMs = SystemClock.elapsedRealtime()
        val freshResult = getFreshPollResult(details, nowMs, pairStateEpoch)
        if (freshResult != null) {
            details.update(PairStateTrust.sanitizePollResult(details, freshResult))
            return true
        }

        val pollClaim = claimPollFlight(details)
        val pollFlight = pollClaim?.flight
        if (pollClaim?.isOwner == true) {
            pollFlight?.pairStateEpoch = pairStateEpoch
        }

        if (pollClaim?.isOwner == false) {
            LimeLog.info("Fast poll: waiting for in-flight poll for ${details.name ?: getPrimaryPollKey(details)}")
            pollFlight?.latch?.await()

            val polledDetails = pollFlight?.result
            if (polledDetails != null) {
                if ((tuple?.pairStateEpoch ?: 0L) != pollFlight.pairStateEpoch) {
                    LimeLog.warning("Fast poll: discarding stale in-flight result for ${details.name ?: getPrimaryPollKey(details)}")
                    return false
                }
                val trustedDetails = PairStateTrust.sanitizePollResult(details, polledDetails)
                details.update(trustedDetails)
                rememberPollResult(details, trustedDetails, pollFlight.completedAtMs, pollFlight.pairStateEpoch)
                LimeLog.info("Fast poll: reused in-flight result for ${details.name ?: getPrimaryPollKey(details)}")
                return true
            }

            return false
        }

        try {
            LimeLog.info("Starting parallel poll for ${details.name} (${details.localAddress}, ${details.remoteAddress}, ${details.manualAddress}, ${details.ipv6Address})")
            val polledDetails = parallelPollPc(details)
            LimeLog.info("Parallel poll for ${details.name} returned address: ${details.activeAddress}")

            if (polledDetails != null) {
                val completedAtMs = SystemClock.elapsedRealtime()
                if ((tuple?.pairStateEpoch ?: 0L) != pairStateEpoch) {
                    LimeLog.warning("Fast poll: discarding stale poll result for ${details.name ?: getPrimaryPollKey(details)}")
                    return false
                }
                val trustedDetails = PairStateTrust.sanitizePollResult(details, polledDetails)
                details.update(trustedDetails)
                rememberPollResult(details, trustedDetails, completedAtMs, pairStateEpoch)
                pollFlight?.result = ComputerDetails(trustedDetails)
                pollFlight?.completedAtMs = completedAtMs
                return true
            }
            return false
        } finally {
            pollFlight?.latch?.countDown()
            if (pollClaim?.isOwner == true) {
                for (key in pollClaim.keys) {
                    activePollFlights.remove(key, pollFlight)
                }
            }
        }
    }

    override fun onCreate() {
        preferences.registerOnSharedPreferenceChangeListener(stunPreferenceListener)
        networkDiagnostics = NetworkDiagnostics(this)
        timeoutManager = DynamicTimeoutManager(networkDiagnostics)

        networkDiagnostics.diagnoseNetwork()

        bindService(
            Intent(this, DiscoveryService::class.java),
            discoveryServiceConnection, Service.BIND_AUTO_CREATE
        )

        idManager = IdentityManager(this)

        dbManager = ComputerDatabaseManager(this)
        dbRefCount.set(1)

        if (!getLocalDatabaseReference()) return

        for (computer in dbManager.getAllComputers()) {
            // isUsbLink is not a stored column; it is derived from the uuid prefix
            // written by usbUuidFor(). Without this a USB card reloaded from the
            // database would come back looking like an ordinary WiFi entry and the
            // UI would lose the distinction after every restart.
            computer.isUsbLink = computer.uuid?.startsWith(USB_UUID_PREFIX) == true
            addTuple(computer)
        }

        releaseLocalDatabaseReference()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    invalidateStunQuery()
                    LimeLog.info("Resetting PC state for new available network")
                    networkDiagnostics?.diagnoseNetwork()
                    LimeLog.info("Network diagnostics after available: ${networkDiagnostics?.getLastDiagnostics()}")
                    recentPollResults.clear()
                    synchronized(pollingTuples) {
                        for (tuple in pollingTuples) {
                            tuple.computer.state = ComputerDetails.State.UNKNOWN
                            emitComputerUpdate(tuple.computer)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    invalidateStunQuery()
                    LimeLog.info("Offlining PCs due to network loss")
                    networkDiagnostics?.diagnoseNetwork()
                    recentPollResults.clear()
                    synchronized(pollingTuples) {
                        for (tuple in pollingTuples) {
                            tuple.computer.state = ComputerDetails.State.OFFLINE
                            emitComputerUpdate(tuple.computer)
                        }
                    }
                }
            }

            val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connMgr.registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onDestroy() {
        preferences.unregisterOnSharedPreferenceChangeListener(stunPreferenceListener)
        invalidateStunQuery()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val connMgr = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connMgr.unregisterNetworkCallback(networkCallback)
        }

        serviceScope.cancel()

        if (discoveryBinder != null) {
            unbindService(discoveryServiceConnection)
        }

        releaseLocalDatabaseReference()
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    inner class ApplistPoller(private val computer: ComputerDetails) {
        private var job: Job? = null
        private val pollSignal = kotlinx.coroutines.channels.Channel<Unit>(
            capacity = kotlinx.coroutines.channels.Channel.CONFLATED
        )
        @Volatile
        private var receivedAppList = false
        private var httpClient: NvHTTP? = null
        private var httpClientKey: String? = null

        fun pollNow() {
            pollSignal.trySend(Unit)
        }

        private fun getPollingTuple(details: ComputerDetails): PollingTuple? {
            synchronized(pollingTuples) {
                for (tuple in pollingTuples) {
                    if (details.uuid == tuple.computer.uuid) {
                        return tuple
                    }
                }
            }
            return null
        }

        private suspend fun awaitNextPoll(): Boolean {
            val delayMs = if (receivedAppList) APPLIST_POLLING_PERIOD_MS else APPLIST_FAILED_POLLING_RETRY_MS
            // 等待信号或超时，任一触发都继续下一轮轮询
            val result = withTimeoutOrNull(delayMs.toLong()) { pollSignal.receiveCatching() }
            // Channel 被关闭时终止轮询
            return result?.isClosed != true
        }

        private fun getHttpClient(
            address: ComputerDetails.AddressTuple,
            timeoutConfig: DynamicTimeoutManager.TimeoutConfig?
        ): NvHTTP {
            val key = "${address}|${computer.httpsPort}|${computer.serverCert?.hashCode()}|${timeoutConfig}"
            if (httpClient == null || httpClientKey != key) {
                httpClient = NvHTTP(
                    address,
                    computer.httpsPort, idManager.uniqueId, "",
                    computer.serverCert, PlatformBinding.getCryptoProvider(this@ComputerManagerService),
                    toNvHttpTimeoutConfig(timeoutConfig)
                )
                httpClientKey = key
                LimeLog.info("App list HTTP client prepared for $address")
            }

            return httpClient!!
        }

        fun start() {
            job = serviceScope.launch {
                var emptyAppListResponses = 0
                do {
                    if (computer.state != ComputerDetails.State.ONLINE ||
                        computer.pairState != PairingManager.PairState.PAIRED
                    ) {
                        emitComputerUpdate(computer)
                        continue
                    }

                    if (computer.uuid == null) continue

                    val tuple = getPollingTuple(computer)

                    try {
                        runInterruptible {
                            val address = ServerHelper.getCurrentAddressFromComputer(computer)
                            val timeoutConfig = timeoutManager?.getDynamicTimeoutConfig(address.address, true)
                            val http = getHttpClient(address, timeoutConfig)

                            val appList: String = if (tuple != null) {
                                synchronized(tuple.networkLock) {
                                    http.getAppListRaw()
                                }
                            } else {
                                http.getAppListRaw()
                            }

                            val list: List<NvApp> = NvHTTP.getAppListByReader(StringReader(appList))
                            if (list.isEmpty()) {
                                LimeLog.warning("Empty app list received from ${computer.uuid}")
                                emptyAppListResponses++
                            }
                            if (appList.isNotEmpty() &&
                                (list.isNotEmpty() || emptyAppListResponses >= EMPTY_LIST_THRESHOLD)
                            ) {
                                val cacheKey = HostCacheKey.fromUuid(computer.uuid)
                                if (cacheKey != null) {
                                    try {
                                        CacheHelper.openCacheFileForOutput(cacheDir, "applist", cacheKey).use { cacheOut ->
                                            CacheHelper.writeStringToOutputStream(cacheOut, appList)
                                        }
                                    } catch (e: IOException) {
                                        e.printStackTrace()
                                    }
                                } else {
                                    LimeLog.warning("Skipping app list cache for host without an identifier")
                                }

                                sendAppListWidgetRefresh(computer.uuid!!)

                                if (list.isNotEmpty()) {
                                    emptyAppListResponses = 0
                                }

                                computer.rawAppList = appList
                                receivedAppList = true

                                emitComputerUpdate(computer)
                            } else if (appList.isEmpty()) {
                                LimeLog.warning("Null app list received from ${computer.uuid}")
                            }
                        }
                    } catch (e: HostHttpResponseException) {
                        if (e.getErrorCode() == 401) {
                            markComputerNotPaired(computer, "App list request")
                            break
                        }
                        e.printStackTrace()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    } catch (e: XmlPullParserException) {
                        e.printStackTrace()
                    } catch (e: InterruptedException) {
                        LimeLog.info("App list polling interrupted for ${computer.name}")
                        break
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    }
                } while (awaitNextPoll())
            }
        }

        fun stop() {
            job?.cancel()
            job = null
            httpClient = null
            httpClientKey = null
            pollSignal.close()
        }
    }

    companion object {
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        private val STUN_DISPATCHER = Dispatchers.IO.limitedParallelism(1)
        /**
         * Marks a computer row as the USB twin of a host. Kept in the uuid because
         * that is the database primary key and the only value that survives a
         * restart; see usbUuidFor().
         */
        private const val USB_UUID_PREFIX = "usb:"

        private const val SERVERINFO_POLLING_PERIOD_MS = 1500
        private const val APPVIEW_BACKGROUND_POLLING_PERIOD_MS: Long = 10000
        private const val APPLIST_POLLING_PERIOD_MS = 30000
        private const val APPLIST_FAILED_POLLING_RETRY_MS = 2000
        private const val MDNS_QUERY_PERIOD_MS = 1000
        private const val OFFLINE_POLL_TRIES = 3
        private const val INITIAL_POLL_TRIES = 2
        private const val EMPTY_LIST_THRESHOLD = 3
        private const val POLL_DATA_TTL_MS = 30000
        private const val POLL_RESULT_REUSE_MS: Long = 2500
        private const val COLLECTION_TIMEOUT_MS: Long = 2000

        /**
         * Floor on how often the USB tethering link is re-probed.
         *
         * The post-poll hook asks for a USB card on every poll of every card, which
         * is several times a second. Re-running the TCP probe at that rate is
         * pointless -- a DHCP lease cannot move that fast -- and it turns into a
         * burst of connects on the tether link every polling period.
         */
        private const val USB_PROBE_MIN_INTERVAL_MS = 15_000L

        /**
         * Retry floor for the case where the link is up but the card does not exist
         * yet -- i.e. the host uuid has not been learned. Matches the server-info
         * polling period so the card appears as soon as the uuid lands.
         */
        private const val USB_PROBE_RETRY_MS = 1_500L
    }
}

private class PollFlight {
    val latch = CountDownLatch(1)

    @Volatile
    var pairStateEpoch: Long = 0

    @Volatile
    var result: ComputerDetails? = null

    @Volatile
    var completedAtMs: Long = 0
}

private data class RecentPoll(
    val details: ComputerDetails,
    val pollTimeMs: Long,
    val pairStateEpoch: Long
)

private data class PollClaim(
    val keys: List<String>,
    val flight: PollFlight,
    val isOwner: Boolean
)

class PollingTuple(
    val computer: ComputerDetails,
    var job: kotlinx.coroutines.Job?
) {
    val networkLock = Any()
    var lastSuccessfulPollMs: Long = 0
    var lastNetworkPollMs: Long = 0
    @Volatile
    var pairStateEpoch: Long = 0
}

class ReachabilityTuple(
    val computer: ComputerDetails,
    val reachableAddress: String
)
