package com.example.meshsocial.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meshsocial.MeshSocialApplication
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
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MeshSocialApplication).container

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

    private val candidateTracker = PeerCandidateTracker()
    private var scanWindowJob: Job? = null

    init {
        viewModelScope.launch {
            container.users.observeCurrentUser().collect { user ->
                _user.value = user
                if (user != null) {
                    logConnection("GATT server start (${user.userId.toString().take(8)})")
                    container.gattServer.start()
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
            while (true) {
                container.posts.deleteExpired(Instant.now())
                delay(60_000)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
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
            when (val result = container.createPost(author.userId, content)) {
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
