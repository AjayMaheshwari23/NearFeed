package com.example.meshsocial.protocol

import com.example.meshsocial.domain.model.Post
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Binary serialization for [SyncMessage]. Frame-level chunking/reassembly is a
 * separate concern ([FrameCodec], not yet implemented); this codec handles one
 * complete message per payload.
 *
 * Wire format: [messageType:byte][body...]
 * Type constants must never be renumbered once devices talk in the wild.
 */
object MessageCodec {

    private const val TYPE_HELLO = 1
    private const val TYPE_INVENTORY = 2
    private const val TYPE_REQUEST_POSTS = 3
    private const val TYPE_POST_BATCH = 4
    private const val TYPE_ACK = 5
    private const val TYPE_SYNC_COMPLETE = 6

    fun encode(message: SyncMessage): ByteArray = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            when (message) {
                is SyncMessage.Hello -> {
                    out.writeByte(TYPE_HELLO)
                    out.writeInt(message.protocolVersion)
                    writeUuid(out, message.peerId)
                }
                is SyncMessage.Inventory -> {
                    out.writeByte(TYPE_INVENTORY)
                    writeUuid(out, message.sessionId)
                    writeUuids(out, message.postIds)
                }
                is SyncMessage.RequestPosts -> {
                    out.writeByte(TYPE_REQUEST_POSTS)
                    writeUuid(out, message.sessionId)
                    writeUuids(out, message.postIds)
                }
                is SyncMessage.PostBatch -> {
                    out.writeByte(TYPE_POST_BATCH)
                    writeUuid(out, message.sessionId)
                    writeUuid(out, message.batchId)
                    out.writeInt(message.posts.size)
                    message.posts.forEach { writePost(out, it) }
                }
                is SyncMessage.Ack -> {
                    out.writeByte(TYPE_ACK)
                    writeUuid(out, message.sessionId)
                    writeUuid(out, message.batchId)
                }
                is SyncMessage.SyncComplete -> {
                    out.writeByte(TYPE_SYNC_COMPLETE)
                    writeUuid(out, message.sessionId)
                }
            }
        }
        buffer.toByteArray()
    }

    fun decode(payload: ByteArray): SyncMessage = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        val type = input.readByte().toInt()
        when (type) {
            TYPE_HELLO -> SyncMessage.Hello(
                protocolVersion = input.readInt(),
                peerId = readUuid(input),
            )
            TYPE_INVENTORY -> SyncMessage.Inventory(
                sessionId = readUuid(input),
                postIds = readUuids(input),
            )
            TYPE_REQUEST_POSTS -> SyncMessage.RequestPosts(
                sessionId = readUuid(input),
                postIds = readUuids(input),
            )
            TYPE_POST_BATCH -> {
                val sessionId = readUuid(input)
                val batchId = readUuid(input)
                val count = input.readInt()
                if (count < 0 || count > 10_000) throw IllegalArgumentException("invalid post count $count")
                val posts = (0 until count).map { readPost(input) }
                SyncMessage.PostBatch(sessionId, batchId, posts)
            }
            TYPE_ACK -> SyncMessage.Ack(
                sessionId = readUuid(input),
                batchId = readUuid(input),
            )
            TYPE_SYNC_COMPLETE -> SyncMessage.SyncComplete(sessionId = readUuid(input))
            else -> throw IllegalArgumentException("unknown message type $type")
        }
    }

    private fun writeUuid(out: DataOutputStream, uuid: UUID) {
        out.writeLong(uuid.mostSignificantBits)
        out.writeLong(uuid.leastSignificantBits)
    }

    private fun readUuid(input: DataInputStream): UUID =
        UUID(input.readLong(), input.readLong())

    private fun writeUuids(out: DataOutputStream, uuids: Set<UUID>) {
        out.writeInt(uuids.size)
        uuids.forEach { writeUuid(out, it) }
    }

    private fun readUuids(input: DataInputStream): Set<UUID> {
        val count = input.readInt()
        if (count < 0 || count > 1_000_000) throw IllegalArgumentException("invalid id count $count")
        return buildSet {
            repeat(count) { add(readUuid(input)) }
        }
    }

    private fun writePost(out: DataOutputStream, post: Post) {
        writeUuid(out, post.postId)
        writeUuid(out, post.authorId)
        writeString(out, post.authorDisplayName)
        val content = post.content.toByteArray(Charsets.UTF_8)
        out.writeInt(content.size)
        out.write(content)
        out.writeLong(post.createdAt.toEpochMilli())
        out.writeLong(post.expiresAt.toEpochMilli())
    }

    private fun readPost(input: DataInputStream): Post {
        val postId = readUuid(input)
        val authorId = readUuid(input)
        val authorDisplayName = readString(input)
        val length = input.readInt()
        if (length < 0 || length > 100_000) throw IllegalArgumentException("invalid content length $length")
        val content = ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
        val createdAt = java.time.Instant.ofEpochMilli(input.readLong())
        val expiresAt = java.time.Instant.ofEpochMilli(input.readLong())
        return Post(postId, authorId, authorDisplayName, content, createdAt, expiresAt)
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val length = input.readInt()
        if (length < 0 || length > 10_000) throw IllegalArgumentException("invalid string length $length")
        return ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
    }
}
