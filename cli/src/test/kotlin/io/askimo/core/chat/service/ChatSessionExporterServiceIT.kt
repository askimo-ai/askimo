/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.askimo.core.chat.domain.ChatDirective
import io.askimo.core.chat.domain.ChatSession
import io.askimo.core.chat.repository.ChatDirectiveRepository
import io.askimo.core.chat.repository.ChatSessionRepository
import io.askimo.core.db.DatabaseManager
import io.askimo.core.util.AskimoHome
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ChatSessionExporterServiceIT {

    @AfterEach
    fun tearDown() {
        sessionRepository.deleteAll()
        directiveRepository.list().forEach { directiveRepository.delete(it.id) }
    }

    @Test
    fun `json compatibility directive matches the exported active set`(@TempDir exportDir: Path) {
        val legacyDirectiveId = "unresolved-legacy-directive"
        val activeDirectiveId = "active-directive"
        val session = ChatSession(
            id = "export-session",
            title = "Directive export",
            directiveId = legacyDirectiveId,
        )
        sessionRepository.upsertFromServer(listOf(session))
        directiveRepository.save(
            ChatDirective(id = activeDirectiveId, name = "Active", content = "Active content"),
        )
        sessionRepository.setSessionDirectiveActive(session.id, activeDirectiveId, true)

        assertEquals(legacyDirectiveId, sessionRepository.getSession(session.id)?.directiveId)
        assertEquals(setOf(activeDirectiveId), sessionRepository.getActiveDirectiveIds(session.id))

        val exportPath = exportDir.resolve("session.json")
        val result = exporterService.exportToJson(session.id, exportPath.toString())

        assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        val exportedJson = ObjectMapper().readTree(exportPath.toFile())
        assertEquals(activeDirectiveId, exportedJson["directiveId"].asText())
        assertEquals(
            listOf(activeDirectiveId),
            exportedJson["directiveIds"].map { it.asText() },
        )
    }

    companion object {
        private lateinit var testBaseScope: AskimoHome.TestBaseScope
        private lateinit var databaseManager: DatabaseManager
        private lateinit var sessionRepository: ChatSessionRepository
        private lateinit var directiveRepository: ChatDirectiveRepository
        private lateinit var exporterService: ChatSessionExporterService

        @JvmStatic
        @BeforeAll
        fun setUpClass(@TempDir tempDir: Path) {
            testBaseScope = AskimoHome.withTestBase(tempDir)
            databaseManager = DatabaseManager.getInMemoryTestInstance(this)
            sessionRepository = databaseManager.getChatSessionRepository()
            directiveRepository = databaseManager.getChatDirectiveRepository()
            exporterService = ChatSessionExporterService(
                sessionRepository = sessionRepository,
                messageRepository = databaseManager.getChatMessageRepository(),
            )
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            if (::databaseManager.isInitialized) databaseManager.close()
            DatabaseManager.reset()
            if (::testBaseScope.isInitialized) testBaseScope.close()
        }
    }
}
