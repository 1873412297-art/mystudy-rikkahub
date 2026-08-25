package me.rerere.rikkahub.ui.components.richtext.runtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.TavernRuntimePermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TavernRuntimeWorldBookRpcTest {
    private fun writeController() = TavernRuntimeController(
        permissionStore = TavernRuntimePermissionStore(
            initial = TavernRuntimePermissions(allowScripts = true, allowWorldWrite = true)
        )
    )

    @Test
    fun `world book CRUD round trip over RPC`() {
        val controller = writeController()

        // createBook
        val created = controller.dispatch(
            TavernRuntimeRequest(
                id = "c1",
                method = "world.createBook",
                params = buildJsonObject {
                    put("name", JsonPrimitive("Arcane World"))
                    put(
                        "entries",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive("hero"))
                                    put("content", JsonPrimitive("A brave hero"))
                                }
                            )
                        }
                    )
                },
            )
        )
        assertTrue(created.ok)
        val createdBook = created.result as JsonObject
        assertEquals("Arcane World", createdBook.getValue("name").jsonPrimitive.content)
        assertEquals(1, createdBook.getValue("entryCount").jsonPrimitive.int)

        // listBooks
        val listed = controller.dispatch(TavernRuntimeRequest(id = "l1", method = "world.listBooks"))
        assertTrue(listed.ok)
        val books = listed.result!!.jsonArray
        assertEquals(1, books.size)
        assertEquals("Arcane World", (books.single() as JsonObject).getValue("name").jsonPrimitive.content)

        // getBook by name
        val fetched = controller.dispatch(
            TavernRuntimeRequest(
                id = "g1",
                method = "world.getBook",
                params = buildJsonObject { put("book", JsonPrimitive("Arcane World")) },
            )
        )
        assertTrue(fetched.ok)
        assertEquals(1, (fetched.result as JsonObject).getValue("entries").jsonArray.size)

        // getEntries filtered by book
        val entries = controller.dispatch(
            TavernRuntimeRequest(
                id = "e1",
                method = "world.getEntries",
                params = buildJsonObject { put("book", JsonPrimitive("Arcane World")) },
            )
        )
        assertTrue(entries.ok)
        assertEquals(1, entries.result!!.jsonArray.size)

        // updateBook rename + entries replace
        val updated = controller.dispatch(
            TavernRuntimeRequest(
                id = "u1",
                method = "world.updateBook",
                params = buildJsonObject {
                    put("book", JsonPrimitive("Arcane World"))
                    put(
                        "patch",
                        buildJsonObject {
                            put("name", JsonPrimitive("Mystic World"))
                            put(
                                "entries",
                                buildJsonArray {
                                    add(buildJsonObject { put("content", JsonPrimitive("replaced")) })
                                    add(buildJsonObject { put("content", JsonPrimitive("second")) })
                                }
                            )
                        }
                    )
                },
            )
        )
        assertTrue(updated.ok)
        val updatedBook = updated.result as JsonObject
        assertEquals("Mystic World", updatedBook.getValue("name").jsonPrimitive.content)
        assertEquals(2, updatedBook.getValue("entryCount").jsonPrimitive.int)

        // deleteBook
        val deleted = controller.dispatch(
            TavernRuntimeRequest(
                id = "d1",
                method = "world.deleteBook",
                params = buildJsonObject { put("book", JsonPrimitive("Mystic World")) },
            )
        )
        assertTrue(deleted.ok)
        assertTrue(deleted.result!!.jsonPrimitive.boolean)
        val afterDelete = controller.dispatch(TavernRuntimeRequest(id = "l2", method = "world.listBooks"))
        assertEquals(0, afterDelete.result!!.jsonArray.size)
    }

    @Test
    fun `world book writes require allowWorldWrite permission`() {
        val controller = TavernRuntimeController(
            permissionStore = TavernRuntimePermissionStore(
                initial = TavernRuntimePermissions(allowScripts = true, allowWorldWrite = false)
            )
        )

        listOf(
            TavernRuntimeRequest(
                id = "p1",
                method = "world.createBook",
                params = buildJsonObject { put("name", JsonPrimitive("X")) },
            ),
            TavernRuntimeRequest(
                id = "p2",
                method = "world.updateBook",
                params = buildJsonObject {
                    put("book", JsonPrimitive("X"))
                    put("patch", buildJsonObject { put("name", JsonPrimitive("Y")) })
                },
            ),
            TavernRuntimeRequest(
                id = "p3",
                method = "world.deleteBook",
                params = buildJsonObject { put("book", JsonPrimitive("X")) },
            ),
        ).forEach { response ->
            val result = controller.dispatch(response)
            assertFalse("${response.method} must reject", result.ok)
            assertEquals("${response.method} code", "PERMISSION_DENIED", result.error!!.code)
        }

        // 读操作不需要写权限
        assertTrue(controller.dispatch(TavernRuntimeRequest(id = "p4", method = "world.listBooks")).ok)
    }

    @Test
    fun `world book RPC reports structured not found and conflict errors`() {
        val controller = writeController()

        val missingGet = controller.dispatch(
            TavernRuntimeRequest(
                id = "n1",
                method = "world.getBook",
                params = buildJsonObject { put("book", JsonPrimitive("ghost")) },
            )
        )
        assertFalse(missingGet.ok)
        assertEquals("NOT_FOUND", missingGet.error!!.code)

        val missingEntries = controller.dispatch(
            TavernRuntimeRequest(
                id = "n2",
                method = "world.getEntries",
                params = buildJsonObject { put("book", JsonPrimitive("ghost")) },
            )
        )
        assertFalse(missingEntries.ok)
        assertEquals("NOT_FOUND", missingEntries.error!!.code)

        val missingUpdate = controller.dispatch(
            TavernRuntimeRequest(
                id = "n3",
                method = "world.updateBook",
                params = buildJsonObject {
                    put("book", JsonPrimitive("ghost"))
                    put("patch", buildJsonObject { put("description", JsonPrimitive("x")) })
                },
            )
        )
        assertFalse(missingUpdate.ok)
        assertEquals("NOT_FOUND", missingUpdate.error!!.code)

        // 重名冲突
        controller.dispatch(
            TavernRuntimeRequest(
                id = "n4",
                method = "world.createBook",
                params = buildJsonObject { put("name", JsonPrimitive("Taken")) },
            )
        )
        val duplicate = controller.dispatch(
            TavernRuntimeRequest(
                id = "n5",
                method = "world.createBook",
                params = buildJsonObject { put("name", JsonPrimitive("Taken")) },
            )
        )
        assertFalse(duplicate.ok)
        assertEquals("ALREADY_EXISTS", duplicate.error!!.code)

        // 缺参数
        val blankName = controller.dispatch(
            TavernRuntimeRequest(
                id = "n6",
                method = "world.createBook",
                params = buildJsonObject { put("name", JsonPrimitive("  ")) },
            )
        )
        assertFalse(blankName.ok)
        assertEquals("BAD_REQUEST", blankName.error!!.code)

        val missingPatch = controller.dispatch(
            TavernRuntimeRequest(
                id = "n7",
                method = "world.updateBook",
                params = buildJsonObject { put("book", JsonPrimitive("Taken")) },
            )
        )
        assertFalse(missingPatch.ok)
        assertEquals("BAD_REQUEST", missingPatch.error!!.code)
    }
}
