package com.example.meshsocial

import com.example.meshsocial.domain.model.Post
import com.example.meshsocial.protocol.MessageCodec
import com.example.meshsocial.protocol.SyncMessage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class MessageCodecTest {

    @Test
    fun helloRoundTrips() {
        val original = SyncMessage.Hello(protocolVersion = 1, peerId = UUID.randomUUID())
        assertEquals(original, MessageCodec.decode(MessageCodec.encode(original)))
    }

    @Test
    fun inventoryRoundTrips() {
        val original = SyncMessage.Inventory(
            sessionId = UUID.randomUUID(),
            postIds = setOf(UUID.randomUUID(), UUID.randomUUID()),
        )
        assertEquals(original, MessageCodec.decode(MessageCodec.encode(original)))
    }

    @Test
    fun postBatchRoundTrips() {
        val now = Instant.parse("2026-08-12T00:00:00Z")
        val original = SyncMessage.PostBatch(
            sessionId = UUID.randomUUID(),
            batchId = UUID.randomUUID(),
            posts = listOf(
                Post(
                    postId = UUID.randomUUID(),
                    authorId = UUID.randomUUID(),
                    authorDisplayName = "alice",
                    content = "hello mesh, this is a longer post content",
                    createdAt = now,
                    expiresAt = now.plusSeconds(86400),
                ),
            ),
        )
        assertEquals(original, MessageCodec.decode(MessageCodec.encode(original)))
    }

    @Test
    fun ackRoundTrips() {
        val original = SyncMessage.Ack(sessionId = UUID.randomUUID(), batchId = UUID.randomUUID())
        assertEquals(original, MessageCodec.decode(MessageCodec.encode(original)))
    }

    @Test
    fun syncCompleteRoundTrips() {
        val original = SyncMessage.SyncComplete(sessionId = UUID.randomUUID())
        assertEquals(original, MessageCodec.decode(MessageCodec.encode(original)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownTypeFails() {
        MessageCodec.decode(byteArrayOf(0x7F))
    }
}
