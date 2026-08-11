package com.example.meshsocial.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.meshsocial.MeshSocialApplication
import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.domain.model.User
import com.example.meshsocial.domain.usecase.CreatePostResult
import com.example.meshsocial.sim.InMemoryPendingSyncRepository
import com.example.meshsocial.sim.InMemoryPeerStateRepository
import com.example.meshsocial.sim.InMemoryPostRepository
import com.example.meshsocial.sync.PairwiseAntiEntropySynchronizer
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

    init {
        viewModelScope.launch {
            container.users.observeCurrentUser().collect { _user.value = it }
        }
        viewModelScope.launch {
            container.posts.observeAll().collect { posts ->
                _feed.value = posts.filter { it.expiresAt.isAfter(Instant.now()) }
            }
        }
        viewModelScope.launch {
            while (true) {
                container.posts.deleteExpired(Instant.now())
                delay(60_000)
            }
        }
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
