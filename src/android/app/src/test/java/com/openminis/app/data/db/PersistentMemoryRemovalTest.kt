package com.openminis.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.openminis.app.tools.AgentTools
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.DriverManager

class PersistentMemoryRemovalTest {
    @Test
    fun `main and helper schemas contain no persistent memory tools`() {
        for (helper in listOf(false, true)) {
            val names = AgentTools.makeAgentTools(isHelper = helper).map { it.name }
            assertFalse("memory_write" in names)
            assertFalse("memory_get" in names)
            assertTrue(names.containsAll(listOf("shell_execute", "file_read", "file_write", "file_edit")))
        }
        assertEquals(7, AgentTools.makeAgentTools().size)
    }

    @Test
    fun `prompt and command menus no longer advertise memory`() {
        val source = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt").readText()
        for (retired in listOf("memory_write", "memory_get", "GLOBAL.md", "id = \"memory\"")) {
            assertFalse("Retired capability remains: $retired", source.contains(retired))
        }
        assertFalse(File("src/main/java/com/openminis/app/data/repository/MemoryRepository.kt").exists())
    }

    @Test
    fun `migration removes memory flag while preserving sessions messages and markers`() {
        verifyMigration(true)
        verifyMigration(false)
    }

    private fun verifyMigration(foreignKeysEnabled: Boolean) {
        val schema = File("schemas/com.openminis.app.data.db.AppDatabase/14.json")
        val entities = Json.parseToJsonElement(schema.readText()).jsonObject
            .getValue("database").jsonObject.getValue("entities").jsonArray
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            fun sql(text: String) = connection.createStatement().use { it.execute(text); Unit }
            sql("PRAGMA foreign_keys = ${if (foreignKeysEnabled) "ON" else "OFF"}")
            for (entity in entities) {
                val obj = entity.jsonObject
                val name = obj.getValue("tableName").jsonPrimitive.content
                sql(obj.getValue("createSql").jsonPrimitive.content.replace("\u0024{TABLE_NAME}", name))
                for (index in obj.getValue("indices").jsonArray) {
                    sql(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\u0024{TABLE_NAME}", name))
                }
            }
            // Populate every non-null field generically so schema additions do not
            // hide accidental data loss behind an empty migration fixture.
            for (name in listOf("sessions", "messages", "compact_markers")) {
                val entity = entities.first { it.jsonObject.getValue("tableName").jsonPrimitive.content == name }.jsonObject
                val fields = entity.getValue("fields").jsonArray.map { it.jsonObject }
                val columns = fields.map { it.getValue("columnName").jsonPrimitive.content }
                val values = fields.map { field ->
                    val column = field.getValue("columnName").jsonPrimitive.content
                    when {
                        column == "session_id" -> "'s1'"
                        column == "id" -> if (name == "sessions") "'s1'" else "'${name}1'"
                        field.getValue("affinity").jsonPrimitive.content == "INTEGER" -> "1"
                        else -> "'fixture'"
                    }
                }
                sql("INSERT INTO $name (${columns.joinToString()}) VALUES (${values.joinToString()})")
            }
            val before = listOf("messages", "compact_markers").associateWith { table ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM $table").use { rows ->
                        assertTrue(rows.next())
                        (1..rows.metaData.columnCount).map { rows.getString(it) }
                    }
                }
            }
            val database = Proxy.newProxyInstance(
                SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java),
            ) { _, method, args ->
                check(method.name == "execSQL") { "Unexpected migration call: ${method.name}" }
                sql(args!![0] as String)
                null
            } as SupportSQLiteDatabase
            connection.autoCommit = false
            AppDatabase.MIGRATION_14_15.migrate(database)
            connection.commit()
            for ((table, expected) in before) {
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT * FROM $table").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(expected, (1..rows.metaData.columnCount).map { rows.getString(it) })
                        assertFalse(rows.next())
                    }
                }
            }
            connection.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA table_info(sessions)").use { rows ->
                    while (rows.next()) assertNotEquals("memory_enabled", rows.getString("name"))
                }
                stmt.executeQuery("PRAGMA foreign_key_check").use { assertFalse(it.next()) }
                stmt.executeQuery("SELECT id FROM sessions").use { rows ->
                    assertTrue(rows.next())
                    assertEquals("s1", rows.getString(1))
                }
            }
        }
    }
}
