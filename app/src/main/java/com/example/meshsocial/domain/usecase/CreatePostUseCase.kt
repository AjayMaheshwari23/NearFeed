package com.example.meshsocial.domain.usecase

import com.example.meshsocial.data.repository.PostRepository
import com.example.meshsocial.domain.model.Post
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface CreatePostResult {
    data class Created(val post: Post) : CreatePostResult
    data class Rejected(val reason: String) : CreatePostResult
}

class CreatePostUseCase(
    private val posts: PostRepository,
    private val maxPostsPerRollingDay: Int = 20,
    private val retention: Duration = Duration.ofHours(24),
) {
    suspend operator fun invoke(authorId: UUID, rawContent: String, now: Instant = Instant.now()): CreatePostResult {
        val content = rawContent.trim()
        if (content.isBlank()) return CreatePostResult.Rejected("Post cannot be empty")
        if (content.length > 500) return CreatePostResult.Rejected("Starter limit: 500 characters")

        val since = now.minus(Duration.ofHours(24))
        val count = posts.countByAuthorSince(authorId, since)
        if (count >= maxPostsPerRollingDay) {
            return CreatePostResult.Rejected("20-post rolling 24-hour quota reached")
        }

        val post = Post(
            postId = UUID.randomUUID(),
            authorId = authorId,
            content = content,
            createdAt = now,
            expiresAt = now.plus(retention),
        )
        posts.insert(post)
        return CreatePostResult.Created(post)
    }
}
