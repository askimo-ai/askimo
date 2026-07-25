/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.repository

import io.askimo.core.chat.domain.ChatMessage
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.domain.FileAttachment
import io.askimo.core.context.MessageRole
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant

class ChatMessageRepositoryIT {

    private lateinit var testSession: ChatSession

    @BeforeEach
    fun setUp() {
        testSession = sessionRepository.createSession(ChatSession(id = "", title = "Test Session"))
    }

    @AfterEach
    fun tearDown() {
        if (::testSession.isInitialized) {
            sessionRepository.deleteSession(testSession.id)
        }
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var messageRepository: ChatMessageRepository
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var attachmentRepository: ChatMessageAttachmentRepository

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)

            databaseManager = DatabaseManager.getInMemoryTestInstance(this)

            // Get singleton repositories from DatabaseManager
            sessionRepository = databaseManager.getChatSessionRepository()
            messageRepository = databaseManager.getChatMessageRepository()
            attachmentRepository = databaseManager.getChatMessageAttachmentRepository()
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) {
                databaseManager.close()
            }
            if (::testBaseScope.isInitialized) {
                testBaseScope.close()
            }
        }
    }

    @Test
    fun `should add and retrieve messages for a session`() {
        val message1 = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Hello",
            ),
        )
        val message2 = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.ASSISTANT,
                content = "Hi there!",
            ),
        )

        val messages = messageRepository.getMessages(testSession.id)

        assertEquals(2, messages.size)
        assertEquals(message1.id, messages[0].id)
        assertEquals(message2.id, messages[1].id)
    }

    @Test
    fun `should handle different message roles correctly`() {
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "User message",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.ASSISTANT,
                content = "Assistant message",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.SYSTEM,
                content = "System message",
            ),
        )

        val messages = messageRepository.getMessages(testSession.id)

        assertEquals(3, messages.size)
        assertEquals(MessageRole.USER, messages[0].role)
        assertEquals(MessageRole.ASSISTANT, messages[1].role)
        assertEquals(MessageRole.SYSTEM, messages[2].role)
    }

    @Test
    fun `should handle empty messages list`() {
        val messages = messageRepository.getMessages(testSession.id)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `should search messages by content`() {
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Hello world",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Goodbye world",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Something else",
            ),
        )

        val results = messageRepository.searchMessages(testSession.id, "world")

        assertEquals(2, results.size)
        assertTrue(results.all { it.content.contains("world") })
    }

    @Test
    fun `should mark message as outdated`() {
        val message = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Message",
            ),
        )

        val marked = messageRepository.markMessageAsOutdated(message.id)

        assertEquals(1, marked)
        val messages = messageRepository.getMessages(testSession.id)
        assertTrue(messages[0].isOutdated)
    }

    @Test
    fun `should mark messages as outdated after specific message`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        val message1 = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "First",
                createdAt = baseTime,
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.ASSISTANT,
                content = "Second",
                createdAt = baseTime.plusSeconds(1),
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Third",
                createdAt = baseTime.plusSeconds(2),
            ),
        )

        val marked = messageRepository.markMessagesAsOutdatedAfter(testSession.id, message1.id)

        assertEquals(3, marked) // All 3 messages from message1 onwards are marked
        val messages = messageRepository.getMessages(testSession.id)
        assertEquals(true, messages[0].isOutdated) // First message marked (fromMessage)
        assertEquals(true, messages[1].isOutdated) // Second message marked
        assertEquals(true, messages[2].isOutdated) // Third message marked
    }

    @Test
    fun `should paginate messages forward from start`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        repeat(5) { i ->
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = testSession.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        val (messages, nextCursor) = messageRepository.getMessagesPaginated(
            sessionId = testSession.id,
            limit = 3,
            cursor = null,
            direction = PaginationDirection.FORWARD,
        )

        assertEquals(3, messages.size)
        assertEquals("Message 0", messages[0].content)
        assertEquals("Message 1", messages[1].content)
        assertEquals("Message 2", messages[2].content)
        assertNotNull(nextCursor)
    }

    @Test
    fun `should paginate messages backward from end`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        repeat(5) { i ->
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = testSession.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        val (messages, nextCursor) = messageRepository.getMessagesPaginated(
            sessionId = testSession.id,
            limit = 3,
            cursor = null,
            direction = PaginationDirection.BACKWARD,
        )

        assertEquals(3, messages.size)
        assertEquals("Message 2", messages[0].content)
        assertEquals("Message 3", messages[1].content)
        assertEquals("Message 4", messages[2].content)
        assertNotNull(nextCursor)
    }

    @Test
    fun `should return null cursor when no more messages`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        repeat(3) { i ->
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = testSession.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        val (messages, nextCursor) = messageRepository.getMessagesPaginated(
            sessionId = testSession.id,
            limit = 5,
            cursor = null,
            direction = PaginationDirection.FORWARD,
        )

        assertEquals(3, messages.size)
        assertEquals(null, nextCursor)
    }

    @Test
    fun `should delete all messages for a session`() {
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Message 1",
            ),
        )
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.ASSISTANT,
                content = "Message 2",
            ),
        )

        val deleted = messageRepository.deleteMessagesBySession(testSession.id)

        assertEquals(2, deleted)
        val messages = messageRepository.getMessages(testSession.id)
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `should paginate through entire message history forward`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        repeat(10) { i ->
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = testSession.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        val allMessages = mutableListOf<ChatMessage>()
        var cursor: Instant? = null

        do {
            val (messages, nextCursor) = messageRepository.getMessagesPaginated(
                sessionId = testSession.id,
                limit = 3,
                cursor = cursor,
                direction = PaginationDirection.FORWARD,
            )
            allMessages.addAll(messages)
            cursor = nextCursor
        } while (cursor != null)

        assertEquals(10, allMessages.size)
        assertEquals("Message 0", allMessages.first().content)
        assertEquals("Message 9", allMessages.last().content)
    }

    @Test
    fun `should not duplicate messages across pages`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        repeat(10) { i ->
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = testSession.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                ),
            )
        }

        val page1 = messageRepository.getMessagesPaginated(
            sessionId = testSession.id,
            limit = 5,
            cursor = null,
            direction = PaginationDirection.FORWARD,
        )

        val page2 = messageRepository.getMessagesPaginated(
            sessionId = testSession.id,
            limit = 5,
            cursor = page1.second,
            direction = PaginationDirection.FORWARD,
        )

        val allMessageIds = (page1.first + page2.first).map { it.id }
        assertEquals(allMessageIds.size, allMessageIds.toSet().size) // No duplicates
    }

    // ===== Attachment Tests =====

    @Test
    fun `should add message with attachments and retrieve them`() {
        val attachment1 = FileAttachment(
            id = "",
            messageId = "",
            sessionId = testSession.id,
            fileName = "document.pdf",
            mimeType = "pdf",
            size = 1024L,
            createdAt = Instant.now(),
            content = "PDF content here",
        )
        val attachment2 = FileAttachment(
            id = "",
            messageId = "",
            sessionId = testSession.id,
            fileName = "image.png",
            mimeType = "png",
            size = 2048L,
            createdAt = Instant.now(),
            content = "PNG content here",
        )

        val message = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Message with attachments",
                attachments = listOf(attachment1, attachment2),
            ),
        )

        val messages = messageRepository.getMessages(testSession.id)

        assertEquals(1, messages.size)
        assertEquals(2, messages[0].attachments.size)
        assertEquals("document.pdf", messages[0].attachments[0].fileName)
        assertEquals("image.png", messages[0].attachments[1].fileName)
        assertEquals(message.id, messages[0].attachments[0].messageId)
        assertEquals(message.id, messages[0].attachments[1].messageId)
    }

    @Test
    fun `should retrieve message without attachments`() {
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Message without attachments",
            ),
        )

        val messages = messageRepository.getMessages(testSession.id)

        assertEquals(1, messages.size)
        assertTrue(messages[0].attachments.isEmpty())
    }

    @Test
    fun `should handle multiple messages with different attachment counts`() {
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Message with one attachment",
                attachments = listOf(
                    FileAttachment(
                        id = "",
                        messageId = "",
                        sessionId = testSession.id,
                        fileName = "file1.txt",
                        mimeType = "txt",
                        size = 100L,
                        createdAt = Instant.now(),
                        content = "content1",
                    ),
                ),
            ),
        )

        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.ASSISTANT,
                content = "Message without attachments",
            ),
        )

        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Message with two attachments",
                attachments = listOf(
                    FileAttachment(
                        id = "",
                        messageId = "",
                        sessionId = testSession.id,
                        fileName = "file2.txt",
                        mimeType = "txt",
                        size = 200L,
                        createdAt = Instant.now(),
                        content = "content2",
                    ),
                    FileAttachment(
                        id = "",
                        messageId = "",
                        sessionId = testSession.id,
                        fileName = "file3.txt",
                        mimeType = "txt",
                        size = 300L,
                        createdAt = Instant.now(),
                        content = "content3",
                    ),
                ),
            ),
        )

        val messages = messageRepository.getMessages(testSession.id)

        assertEquals(3, messages.size)
        assertEquals(1, messages[0].attachments.size)
        assertEquals(0, messages[1].attachments.size)
        assertEquals(2, messages[2].attachments.size)
    }

    @Test
    fun `should automatically delete attachments when message is deleted via CASCADE`() {
        val message = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Message with attachments",
                attachments = listOf(
                    FileAttachment(
                        id = "",
                        messageId = "",
                        sessionId = testSession.id,
                        fileName = "file.txt",
                        mimeType = "txt",
                        size = 100L,
                        createdAt = Instant.now(),
                        content = "content",
                    ),
                ),
            ),
        )

        // Verify attachment exists
        val messagesBeforeDelete = messageRepository.getMessages(testSession.id)
        assertEquals(1, messagesBeforeDelete[0].attachments.size)

        // Delete session (which will CASCADE delete messages and attachments)
        messageRepository.deleteMessagesBySession(testSession.id)

        // Verify no messages remain
        val messagesAfterDelete = messageRepository.getMessages(testSession.id)
        assertTrue(messagesAfterDelete.isEmpty())

        // Verify attachments are also deleted (via CASCADE)
        val attachments = attachmentRepository.getAttachmentsBySessionId(testSession.id)
        assertTrue(attachments.isEmpty())
    }

    @Test
    fun `should load attachments with paginated messages`() {
        val baseTime = Instant.parse("2024-01-01T00:00:00Z")
        repeat(5) { i ->
            messageRepository.addMessage(
                ChatMessage(
                    id = "",
                    sessionId = testSession.id,
                    role = MessageRole.USER,
                    content = "Message $i",
                    createdAt = baseTime.plusSeconds(i.toLong()),
                    attachments = listOf(
                        FileAttachment(
                            id = "",
                            messageId = "",
                            sessionId = testSession.id,
                            fileName = "file$i.txt",
                            mimeType = "txt",
                            size = (i * 100).toLong(),
                            createdAt = baseTime.plusSeconds(i.toLong()),
                            content = "content$i",
                        ),
                    ),
                ),
            )
        }

        val (page1, cursor) = messageRepository.getMessagesPaginated(
            sessionId = testSession.id,
            limit = 2,
            cursor = null,
            direction = PaginationDirection.FORWARD,
        )

        assertEquals(2, page1.size)
        assertEquals(1, page1[0].attachments.size)
        assertEquals("file0.txt", page1[0].attachments[0].fileName)
        assertEquals(1, page1[1].attachments.size)
        assertEquals("file1.txt", page1[1].attachments[0].fileName)

        val (page2, _) = messageRepository.getMessagesPaginated(
            sessionId = testSession.id,
            limit = 2,
            cursor = cursor,
            direction = PaginationDirection.FORWARD,
        )

        assertEquals(2, page2.size)
        assertEquals(1, page2[0].attachments.size)
        assertEquals("file2.txt", page2[0].attachments[0].fileName)
    }

    @Test
    fun `should load attachments with search results`() {
        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Important document",
                attachments = listOf(
                    FileAttachment(
                        id = "",
                        messageId = "",
                        sessionId = testSession.id,
                        fileName = "important.pdf",
                        mimeType = "pdf",
                        size = 1024L,
                        createdAt = Instant.now(),
                        content = "important content",
                    ),
                ),
            ),
        )

        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Regular message",
            ),
        )

        messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Another important note",
                attachments = listOf(
                    FileAttachment(
                        id = "",
                        messageId = "",
                        sessionId = testSession.id,
                        fileName = "note.txt",
                        mimeType = "txt",
                        size = 512L,
                        createdAt = Instant.now(),
                        content = "note content",
                    ),
                ),
            ),
        )

        val results = messageRepository.searchMessages(testSession.id, "important")

        assertEquals(2, results.size)
        assertEquals(1, results[0].attachments.size)
        assertEquals("important.pdf", results[0].attachments[0].fileName)
        assertEquals(1, results[1].attachments.size)
        assertEquals("note.txt", results[1].attachments[0].fileName)
    }

    @Test
    fun `should preserve attachment metadata without content field`() {
        val attachment = FileAttachment(
            id = "",
            messageId = "",
            sessionId = testSession.id,
            fileName = "test.txt",
            mimeType = "txt",
            size = 1024L,
            createdAt = Instant.now(),
            content = "This content should be stored",
        )

        val message = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Test message",
                attachments = listOf(attachment),
            ),
        )

        val retrievedMessages = messageRepository.getMessages(testSession.id)
        val retrievedAttachment = retrievedMessages[0].attachments[0]

        assertEquals("test.txt", retrievedAttachment.fileName)
        assertEquals("txt", retrievedAttachment.mimeType)
        assertEquals(1024L, retrievedAttachment.size)
        assertNotNull(retrievedAttachment.id)
        assertEquals(message.id, retrievedAttachment.messageId)
        assertEquals(testSession.id, retrievedAttachment.sessionId)
        // Content is NOT stored in database, should be null
        assertEquals(null, retrievedAttachment.content)
    }

    @Test
    fun `should handle large number of attachments efficiently`() {
        val attachments = (1..10).map { i ->
            FileAttachment(
                id = "",
                messageId = "",
                sessionId = testSession.id,
                fileName = "file$i.txt",
                mimeType = "txt",
                size = (i * 100).toLong(),
                createdAt = Instant.now(),
                content = "content$i",
            )
        }

        val message = messageRepository.addMessage(
            ChatMessage(
                id = "",
                sessionId = testSession.id,
                role = MessageRole.USER,
                content = "Message with many attachments",
                attachments = attachments,
            ),
        )

        val messages = messageRepository.getMessages(testSession.id)

        assertEquals(1, messages.size)
        assertEquals(10, messages[0].attachments.size)
        // Verify all attachments are properly loaded via JOIN (order doesn't matter)
        val fileNames = messages[0].attachments.map { it.fileName }.toSet()
        val expectedFileNames = (1..10).map { "file$it.txt" }.toSet()
        assertEquals(expectedFileNames, fileNames)
    }

    // ===== Bookmark Tests =====

    @Test
    fun `toggleBookmark should return true when bookmarking a message`() {
        val msg = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Bookmark me"),
        )

        val result = messageRepository.toggleBookmark(msg.id)

        assertTrue(result)
    }

    @Test
    fun `toggleBookmark should return false when un-bookmarking a message`() {
        val msg = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Toggle off"),
        )
        messageRepository.toggleBookmark(msg.id) // on

        val result = messageRepository.toggleBookmark(msg.id) // off

        assertFalse(result)
    }

    @Test
    fun `toggleBookmark should persist isBookmarked flag correctly`() {
        val msg = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Check flag"),
        )

        messageRepository.toggleBookmark(msg.id)
        assertTrue(messageRepository.getMessages(testSession.id).first { it.id == msg.id }.isBookmarked)

        messageRepository.toggleBookmark(msg.id)
        assertFalse(messageRepository.getMessages(testSession.id).first { it.id == msg.id }.isBookmarked)
    }

    @Test
    fun `toggleBookmark should only affect the targeted message`() {
        val baseTime = Instant.now()
        val target = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Target", createdAt = baseTime),
        )
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.ASSISTANT, content = "Bystander", createdAt = baseTime.plusSeconds(1)),
        )

        messageRepository.toggleBookmark(target.id)

        val messages = messageRepository.getMessages(testSession.id)
        assertTrue(messages.first { it.id == target.id }.isBookmarked)
        assertFalse(messages.first { it.content == "Bystander" }.isBookmarked)
    }

    // ===== getBookmarkedMessages =====

    @Test
    fun `getBookmarkedMessages should return empty list when no bookmarks in session`() {
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Not bookmarked"),
        )

        val result = messageRepository.getBookmarkedMessages(testSession.id)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getBookmarkedMessages should return only bookmarked messages for the session`() {
        val baseTime = Instant.now()
        val bookmarked = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Bookmarked", createdAt = baseTime),
        )
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.ASSISTANT, content = "Not bookmarked", createdAt = baseTime.plusSeconds(1)),
        )
        messageRepository.toggleBookmark(bookmarked.id)

        val result = messageRepository.getBookmarkedMessages(testSession.id)

        assertEquals(1, result.size)
        assertEquals(bookmarked.id, result[0].id)
        assertEquals("Bookmarked", result[0].content)
    }

    @Test
    fun `getBookmarkedMessages should return messages ordered by createdAt ascending`() {
        val baseTime = Instant.now()
        val first = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "First", createdAt = baseTime),
        )
        val third = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.ASSISTANT, content = "Third", createdAt = baseTime.plusSeconds(2)),
        )
        val second = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Second", createdAt = baseTime.plusSeconds(1)),
        )
        messageRepository.toggleBookmark(first.id)
        messageRepository.toggleBookmark(second.id)
        messageRepository.toggleBookmark(third.id)

        val result = messageRepository.getBookmarkedMessages(testSession.id)

        assertEquals(listOf("First", "Second", "Third"), result.map { it.content })
    }

    @Test
    fun `getBookmarkedMessages should not return bookmarks from other sessions`() {
        val otherSession = sessionRepository.createSession(ChatSession(id = "", title = "Other Session"))
        try {
            val otherMsg = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = otherSession.id, role = MessageRole.USER, content = "Other session bookmark"),
            )
            messageRepository.toggleBookmark(otherMsg.id)

            val result = messageRepository.getBookmarkedMessages(testSession.id)

            assertTrue(result.isEmpty())
        } finally {
            sessionRepository.deleteSession(otherSession.id)
        }
    }

    // ===== getAllBookmarkedMessages =====

    @Test
    fun `getAllBookmarkedMessages should return empty list when no bookmarks exist`() {
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Not bookmarked"),
        )

        val result = messageRepository.getAllBookmarkedMessages()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAllBookmarkedMessages should return bookmarks from all sessions`() {
        val otherSession = sessionRepository.createSession(ChatSession(id = "", title = "Other Session"))
        try {
            val baseTime = Instant.now()
            val msg1 = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Session 1 bookmark", createdAt = baseTime),
            )
            val msg2 = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = otherSession.id, role = MessageRole.USER, content = "Session 2 bookmark", createdAt = baseTime.plusSeconds(1)),
            )
            messageRepository.toggleBookmark(msg1.id)
            messageRepository.toggleBookmark(msg2.id)

            val result = messageRepository.getAllBookmarkedMessages()

            assertEquals(2, result.size)
            assertTrue(result.any { it.id == msg1.id })
            assertTrue(result.any { it.id == msg2.id })
        } finally {
            sessionRepository.deleteSession(otherSession.id)
        }
    }

    @Test
    fun `getAllBookmarkedMessages should not include non-bookmarked messages`() {
        val bookmarked = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Bookmarked"),
        )
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.ASSISTANT, content = "Not bookmarked"),
        )
        messageRepository.toggleBookmark(bookmarked.id)

        val result = messageRepository.getAllBookmarkedMessages()

        assertEquals(1, result.size)
        assertEquals(bookmarked.id, result[0].id)
    }

    // ===== getBookmarkCountsBySession =====

    @Test
    fun `getBookmarkCountsBySession should return empty map when no bookmarks exist`() {
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Not bookmarked"),
        )

        val result = messageRepository.getBookmarkCountsBySession()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getBookmarkCountsBySession should return correct count for single session`() {
        val baseTime = Instant.now()
        val msg1 = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "A", createdAt = baseTime),
        )
        val msg2 = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.ASSISTANT, content = "B", createdAt = baseTime.plusSeconds(1)),
        )
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "C (not bookmarked)", createdAt = baseTime.plusSeconds(2)),
        )
        messageRepository.toggleBookmark(msg1.id)
        messageRepository.toggleBookmark(msg2.id)

        val result = messageRepository.getBookmarkCountsBySession()

        assertEquals(1, result.size)
        assertEquals(2, result[testSession.id])
    }

    @Test
    fun `getBookmarkCountsBySession should return counts for multiple sessions`() {
        val otherSession = sessionRepository.createSession(ChatSession(id = "", title = "Other Session"))
        try {
            val baseTime = Instant.now()
            val m1 = messageRepository.addMessage(ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "S1-1", createdAt = baseTime))
            val m2 = messageRepository.addMessage(ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.ASSISTANT, content = "S1-2", createdAt = baseTime.plusSeconds(1)))
            val m3 = messageRepository.addMessage(ChatMessage(id = "", sessionId = otherSession.id, role = MessageRole.USER, content = "S2-1", createdAt = baseTime.plusSeconds(2)))
            messageRepository.toggleBookmark(m1.id)
            messageRepository.toggleBookmark(m2.id)
            messageRepository.toggleBookmark(m3.id)

            val result = messageRepository.getBookmarkCountsBySession()

            assertEquals(2, result.size)
            assertEquals(2, result[testSession.id])
            assertEquals(1, result[otherSession.id])
        } finally {
            sessionRepository.deleteSession(otherSession.id)
        }
    }

    @Test
    fun `getBookmarkCountsBySession should not include sessions with zero bookmarks`() {
        val otherSession = sessionRepository.createSession(ChatSession(id = "", title = "Unbookmarked Session"))
        try {
            val bookmarked = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Bookmarked"),
            )
            messageRepository.addMessage(
                ChatMessage(id = "", sessionId = otherSession.id, role = MessageRole.USER, content = "Not bookmarked"),
            )
            messageRepository.toggleBookmark(bookmarked.id)

            val result = messageRepository.getBookmarkCountsBySession()

            assertEquals(1, result.size)
            assertTrue(result.containsKey(testSession.id))
            assertFalse(result.containsKey(otherSession.id))
        } finally {
            sessionRepository.deleteSession(otherSession.id)
        }
    }

    // ===== getAllBookmarkedWithSessions =====

    @Test
    fun `getAllBookmarkedWithSessions should return empty list when no bookmarks exist`() {
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Not bookmarked"),
        )

        val result = messageRepository.getAllBookmarkedWithSessions()

        assertTrue(result.isEmpty())
    }

    @Test
    fun `getAllBookmarkedWithSessions should return message paired with its session`() {
        val msg = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Bookmarked"),
        )
        messageRepository.toggleBookmark(msg.id)

        val result = messageRepository.getAllBookmarkedWithSessions()

        assertEquals(1, result.size)
        assertEquals(msg.id, result[0].first.id)
        assertEquals(testSession.id, result[0].second.id)
        assertEquals(testSession.title, result[0].second.title)
    }

    @Test
    fun `getAllBookmarkedWithSessions should not include non-bookmarked messages`() {
        val bookmarked = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Bookmarked"),
        )
        messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.ASSISTANT, content = "Not bookmarked"),
        )
        messageRepository.toggleBookmark(bookmarked.id)

        val result = messageRepository.getAllBookmarkedWithSessions()

        assertEquals(1, result.size)
        assertEquals(bookmarked.id, result[0].first.id)
    }

    @Test
    fun `getAllBookmarkedWithSessions should order by session updatedAt descending then message createdAt ascending`() {
        val baseTime = Instant.now()

        val olderSession = sessionRepository.createSession(ChatSession(id = "", title = "Older Session"))
        try {
            val olderMsg = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = olderSession.id, role = MessageRole.USER, content = "Older", createdAt = baseTime),
            )
            messageRepository.toggleBookmark(olderMsg.id)

            Thread.sleep(50)

            // testSession gets touched last because we add a message to it after olderSession
            val newerMsg1 = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Newer-1", createdAt = baseTime.plusSeconds(1)),
            )
            val newerMsg2 = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.ASSISTANT, content = "Newer-2", createdAt = baseTime.plusSeconds(2)),
            )
            messageRepository.toggleBookmark(newerMsg1.id)
            messageRepository.toggleBookmark(newerMsg2.id)
            // touch testSession so its updatedAt is after olderSession
            sessionRepository.touchSession(testSession.id)

            val result = messageRepository.getAllBookmarkedWithSessions()

            assertEquals(3, result.size)
            // First two rows belong to testSession (more recently updated)
            assertEquals(testSession.id, result[0].second.id)
            assertEquals("Newer-1", result[0].first.content)
            assertEquals(testSession.id, result[1].second.id)
            assertEquals("Newer-2", result[1].first.content)
            // Last row belongs to olderSession
            assertEquals(olderSession.id, result[2].second.id)
            assertEquals("Older", result[2].first.content)
        } finally {
            sessionRepository.deleteSession(olderSession.id)
        }
    }

    @Test
    fun `getAllBookmarkedWithSessions should include rows from multiple sessions`() {
        val otherSession = sessionRepository.createSession(ChatSession(id = "", title = "Other Session"))
        try {
            val baseTime = Instant.now()
            val msg1 = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "S1", createdAt = baseTime),
            )
            val msg2 = messageRepository.addMessage(
                ChatMessage(id = "", sessionId = otherSession.id, role = MessageRole.USER, content = "S2", createdAt = baseTime.plusSeconds(1)),
            )
            messageRepository.toggleBookmark(msg1.id)
            messageRepository.toggleBookmark(msg2.id)

            val result = messageRepository.getAllBookmarkedWithSessions()

            assertEquals(2, result.size)
            val sessionIds = result.map { it.second.id }.toSet()
            assertTrue(sessionIds.contains(testSession.id))
            assertTrue(sessionIds.contains(otherSession.id))
        } finally {
            sessionRepository.deleteSession(otherSession.id)
        }
    }

    @Test
    fun `getAllBookmarkedWithSessions should reflect un-bookmarking immediately`() {
        val msg = messageRepository.addMessage(
            ChatMessage(id = "", sessionId = testSession.id, role = MessageRole.USER, content = "Toggle"),
        )
        messageRepository.toggleBookmark(msg.id) // on
        assertEquals(1, messageRepository.getAllBookmarkedWithSessions().size)

        messageRepository.toggleBookmark(msg.id) // off
        assertTrue(messageRepository.getAllBookmarkedWithSessions().isEmpty())
    }
}
