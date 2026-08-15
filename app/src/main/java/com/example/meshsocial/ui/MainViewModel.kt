package com.example.meshsocial.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meshsocial.NearFeedApplication
import com.example.meshsocial.ble.BlePermissions
import com.example.meshsocial.discovery.SUPPORTED_PROTOCOL_VERSION
import com.example.meshsocial.discovery.PeerCandidate
import com.example.meshsocial.discovery.PeerCandidateTracker
import com.example.meshsocial.domain.model.PeerState
import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.domain.model.SyncDirection
import com.example.meshsocial.domain.model.User
import com.example.meshsocial.domain.usecase.CreatePostResult
import com.example.meshsocial.sim.InMemoryPendingSyncRepository
import com.example.meshsocial.sim.InMemoryPeerStateRepository
import com.example.meshsocial.sim.InMemoryPostRepository
import com.example.meshsocial.sync.PairwiseAntiEntropySynchronizer
import com.example.meshsocial.topology.TopologyContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as NearFeedApplication).container

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user.asStateFlow()

    private val _feed = MutableStateFlow<List<Post>>(emptyList())
    val feed: StateFlow<List<Post>> = _feed.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _debugLog = MutableStateFlow<List<String>>(emptyList())
    val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerCandidate>>(emptyList())
    val peers: StateFlow<List<PeerCandidate>> = _peers.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _connectionLog = MutableStateFlow<List<String>>(emptyList())
    val connectionLog: StateFlow<List<String>> = _connectionLog.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<UUID>>(emptyList())
    val connectedPeers: StateFlow<List<UUID>> = _connectedPeers.asStateFlow()

    private val _syncEvents = MutableStateFlow<List<String>>(emptyList())
    val syncEvents: StateFlow<List<String>> = _syncEvents.asStateFlow()

    private val _backgroundRunning = MutableStateFlow(false)
    val backgroundRunning: StateFlow<Boolean> = _backgroundRunning.asStateFlow()

    enum class CyclePhase { WAITING, SCANNING, RECONCILING, IDLE }

    data class ActivityLine(val time: String, val text: String)

    private val _cyclePhase = MutableStateFlow(CyclePhase.IDLE)
    val cyclePhase: StateFlow<CyclePhase> = _cyclePhase.asStateFlow()

    private val _cycleElapsed = MutableStateFlow(0L)
    val cycleElapsed: StateFlow<Long> = _cycleElapsed.asStateFlow()

    private val _cycleTotal = MutableStateFlow(0L)
    val cycleTotal: StateFlow<Long> = _cycleTotal.asStateFlow()

    private val _postsReceived = MutableStateFlow(0)
    val postsReceived: StateFlow<Int> = _postsReceived.asStateFlow()

    private val _postsSent = MutableStateFlow(0)
    val postsSent: StateFlow<Int> = _postsSent.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _activityLog = MutableStateFlow<List<ActivityLine>>(emptyList())
    val activityLog: StateFlow<List<ActivityLine>> = _activityLog.asStateFlow()

    private val candidateTracker = PeerCandidateTracker()
    private var scanWindowJob: Job? = null
    private var backgroundJob: Job? = null

    init {
        // Start the BLE stack (GATT server, advertising, background sync) only
        // once the user has actually granted the runtime permissions. A process
        // started before the grant keeps stale denied state; this gate re-checks
        // on a cadence so the app self-heals without a restart.
        viewModelScope.launch {
            container.users.observeCurrentUser().collect { user ->
                _user.value = user
                if (user != null) {
                    launchNearbyStackWhenReady(user.userId)
                }
            }
        }
        viewModelScope.launch {
            container.posts.observeAll().collect { posts ->
                _feed.value = posts.filter { it.expiresAt.isAfter(Instant.now()) }
            }
        }
        viewModelScope.launch {
            container.peerDiscovery.discoveredPeers.collect { candidate ->
                _peers.value = candidateTracker.onCandidate(candidate)
            }
        }
        viewModelScope.launch {
            container.syncEvents.collect { event ->
                _syncEvents.value = (_syncEvents.value + event).takeLast(200)
                parseCounters(event)
                logActivity(event)
            }
        }
        viewModelScope.launch {
            while (true) {
                _pendingCount.value = container.pendingSync.getAll().size
                delay(5_000)
            }
        }
        viewModelScope.launch {
            while (true) {
                container.posts.deleteExpired(Instant.now())
                delay(60_000)
            }
        }
    }

    private var nearbyStackJob: Job? = null

    /**
     * Starts the GATT server, advertising, and the background sync loop only
     * when BLE runtime permissions are granted. Re-checks every few seconds so a
     * grant that happens after process start (e.g. the permission dialog) is
     * picked up without a manual app restart.
     */
    private fun launchNearbyStackWhenReady(userId: UUID) {
        if (nearbyStackJob != null) return
        nearbyStackJob = viewModelScope.launch {
            while (true) {
                val context: Application = getApplication()
                val missing = BlePermissions.missing(context)
                val btOn = BlePermissions.isBluetoothEnabled(context)
                if (missing.isNotEmpty() || !btOn) {
                    logConnection(
                        "Waiting for nearby access — perms missing=${missing.size}, bluetooth=$btOn"
                    )
                    delay(2_000)
                    continue
                }
                logConnection("Nearby access granted — starting stack (${userId.toString().take(8)})")
                container.gattServer.start()
                container.peerDiscovery.startAdvertising()
                startBackgroundSync()
                break
            }
        }
    }

    /**
     * Continuous background loop: advertise+scan, reconcile top-K, then idle
     * briefly and repeat. Persistent connections stay open across cycles; the
     * topology policy skips already-active peers so we do not double-connect.
     *
     * Gated on BLE readiness (permissions granted + Bluetooth radio on). If the
     * phone is not ready (e.g. Bluetooth turned off), the loop waits and retries
     * instead of silently failing to scan.
     */
    fun startBackgroundSync(
        scanWindowSeconds: Long = 20,
        idleGapSeconds: Long = 10,
    ) {
        if (backgroundJob != null) return
        _backgroundRunning.value = true
        backgroundJob = viewModelScope.launch {
            while (true) {
                if (!BlePermissions.isReady(getApplication())) {
                    _scanning.value = false
                    _cyclePhase.value = CyclePhase.WAITING
                    _cycleElapsed.value = 0
                    _cycleTotal.value = 0
                    val missing = BlePermissions.missing(getApplication())
                    val btOn = BlePermissions.isBluetoothEnabled(getApplication())
                    logConnection(
                        "BG cycle: WAITING — perms missing=${missing.size}, bluetooth=$btOn"
                    )
                    logActivity("Waiting — perms missing=${missing.size}, bluetooth=$btOn")
                    delay(Duration.ofSeconds(5).toMillis())
                    continue
                }
                _scanning.value = true
                container.peerDiscovery.startDiscovery()
                logConnection("BG cycle: scanning ${scanWindowSeconds}s")
                logActivity("Scanning started")
                runPhase(CyclePhase.SCANNING, scanWindowSeconds)
                container.peerDiscovery.stopDiscovery()
                _scanning.value = false
                logConnection("BG cycle: window closed, reconciling top-K")
                logActivity("Scan window closed, reconciling")
                runPhase(CyclePhase.RECONCILING, 0)
                reconcileConnections()
                logConnection("BG cycle: idle ${idleGapSeconds}s")
                logActivity("Reconcile done, idle")
                runPhase(CyclePhase.IDLE, idleGapSeconds)
            }
        }
    }

    /** Runs a cycle phase, ticking elapsed seconds for the progress display. */
    private suspend fun runPhase(phase: CyclePhase, totalSeconds: Long) {
        _cyclePhase.value = phase
        _cycleTotal.value = totalSeconds
        _cycleElapsed.value = 0
        val step = 1_000L
        val steps = totalSeconds
        repeat(steps.toInt()) { i ->
            delay(step)
            _cycleElapsed.value = (i + 1).toLong()
        }
    }

    private fun parseCounters(event: String) {
        Regex("inserted (\\d+) post").find(event)?.let {
            _postsReceived.value += it.groupValues[1].toInt()
        }
        Regex("sent (\\d+) post").find(event)?.let {
            _postsSent.value += it.groupValues[1].toInt()
        }
    }

    private fun logActivity(text: String) {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalTime.now())
        _activityLog.value = (_activityLog.value + ActivityLine(time, text)).takeLast(50)
    }

    fun stopBackgroundSync() {
        backgroundJob?.cancel()
        backgroundJob = null
        _backgroundRunning.value = false
        scanWindowJob?.cancel()
        scanWindowJob = null
        viewModelScope.launch {
            container.peerDiscovery.stopDiscovery()
            _scanning.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        backgroundJob?.cancel()
        container.gattServer.stop()
    }

    fun setMessage(text: String) {
        _message.value = text
    }

    fun startDiscovery(scanWindowSeconds: Long = 30) {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            container.peerDiscovery.startDiscovery()
            scanWindowJob = viewModelScope.launch {
                delay(Duration.ofSeconds(scanWindowSeconds).toMillis())
                stopDiscovery()
            }
        }
    }

    fun stopDiscovery() {
        scanWindowJob?.cancel()
        scanWindowJob = null
        viewModelScope.launch {
            container.peerDiscovery.stopDiscovery()
            _scanning.value = false
            reconcileConnections()
        }
    }

    /** Selection + connection request to the top-K discovered peers. */
    fun reconcileConnections() {
        val selfId = _user.value?.userId ?: return
        val candidates = _peers.value
        if (candidates.isEmpty()) {
            logConnection("Reconcile: no candidates")
            return
        }
        viewModelScope.launch {
            val peerStates = container.peerStates.getAll()
                .associateBy { it.peerId }
            val pending = container.pendingSync.getAll()
            val pendingCounts = pending.groupingBy { it.peerId }.eachCount()
            val pendingSince = pending
                .filter { it.direction == SyncDirection.RECEIVE }
                .groupBy { it.peerId }
                .mapValues { (_, items) -> items.minOf { it.updatedAt } }

            val context = TopologyContext(
                selfPeerId = selfId,
                peerStates = peerStates,
                pendingCounts = pendingCounts,
                pendingSince = pendingSince,
                now = Instant.now(),
            )
            logConnection("Reconcile: ${candidates.size} candidate(s), selecting top-${container.connectionCoordinator.activeCount.coerceAtLeast(1) + 1}")
            container.connectionCoordinator.reconcileConnections(candidates, context)
            _connectedPeers.value = container.connectionCoordinator.activeConnections()
        }
    }

    private fun logConnection(line: String) {
        _connectionLog.value = (_connectionLog.value + line).takeLast(100)
    }

    fun createProfile(displayName: String) {
        val name = displayName.trim()
        if (name.isBlank()) {
            _message.value = "Display name is required"
            return
        }
        viewModelScope.launch {
            container.users.save(
                User(UUID.randomUUID(), name, Instant.now())
            )
            _message.value = "Profile created"
        }
    }

    fun createPost(content: String) {
        val author = _user.value ?: return
        viewModelScope.launch {
            when (val result = container.createPost(author.userId, author.displayName, content)) {
                is CreatePostResult.Created -> _message.value = "Posted"
                is CreatePostResult.Rejected -> _message.value = result.reason
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun runSyncDemo() {
        viewModelScope.launch {
            _debugLog.value = emptyList()
            log("Creating two isolated in-memory replicas A and B")

            val now = Instant.now()
            val peerA = UUID.randomUUID()
            val peerB = UUID.randomUUID()
            val aPosts = InMemoryPostRepository()
            val bPosts = InMemoryPostRepository()
            val aPending = InMemoryPendingSyncRepository()
            val bPending = InMemoryPendingSyncRepository()
            val aPeers = InMemoryPeerStateRepository()
            val bPeers = InMemoryPeerStateRepository()

            suspend fun seed(repo: InMemoryPostRepository, author: UUID, text: String) {
                repo.insert(Post(
                    postId = UUID.randomUUID(),
                    authorId = author,
                    authorDisplayName = "peer ${author.toString().take(8)}",
                    content = text,
                    createdAt = now,
                    expiresAt = now.plus(Duration.ofHours(24)),
                ))
            }

            seed(aPosts, peerA, "A: post 1")
            seed(aPosts, peerA, "A: post 2")
            seed(bPosts, peerB, "B: post 1")
            seed(bPosts, peerB, "B: post 2")
            seed(bPosts, peerB, "B: post 3")

            val sync = PairwiseAntiEntropySynchronizer()
            val a = PairwiseAntiEntropySynchronizer.Node(peerA, aPosts, aPeers, aPending)
            val b = PairwiseAntiEntropySynchronizer.Node(peerB, bPosts, bPeers, bPending)

            log("Before: A=${aPosts.activePostIds(now).size}, B=${bPosts.activePostIds(now).size}")
            log("Sync round 1 with transfer budget=1 (simulated connection drop)")
            val first = sync.sync(a, b, now, maxTransfersPerDirection = 1)
            log("Round 1: $first")
            log("Pending work survives the interruption")

            log("Sync round 2: resume pending + fresh inventory")
            val second = sync.sync(a, b, now.plusSeconds(2))
            log("Round 2: $second")
            log("After: A=${aPosts.activePostIds(now).size}, B=${bPosts.activePostIds(now).size}")
            log("Converged=${aPosts.activePostIds(now) == bPosts.activePostIds(now)}")
        }
    }

    private fun log(line: String) {
        _debugLog.value = _debugLog.value + line
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return MainViewModel(application) as T
        }
    }
}
