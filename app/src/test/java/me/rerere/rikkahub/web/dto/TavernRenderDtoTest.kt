package me.rerere.rikkahub.web.dto

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class TavernRenderDtoTest {

    @Test
    fun `serializes full payload`() {
        val dto = TavernRenderDto(statusRenderJs = "function renderStatus(){return ''}", css = "body{}")
        val json = JsonInstant.encodeToString(dto)
        assertEquals(
            """{"statusRenderJs":"function renderStatus(){return ''}","css":"body{}"}""",
            json
        )
    }

    @Test
    fun `serializes nulls`() {
        val json = JsonInstant.encodeToString(TavernRenderDto())
        assertEquals("""{"statusRenderJs":null,"css":null}""", json)
    }
}
