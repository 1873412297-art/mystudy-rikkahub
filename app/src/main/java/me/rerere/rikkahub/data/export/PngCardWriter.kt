package me.rerere.rikkahub.data.export

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Writes SillyTavern-compatible PNG character cards with embedded tEXt chunks.
 *
 * Mirrors the logic in SillyTavern's `character-card-parser.js` `write()` function:
 * 1. Take a base image (bitmap) and encode to PNG
 * 2. Parse the PNG chunks
 * 3. Remove existing tEXt chunks with keyword "chara" or "ccv3"
 * 4. Insert new tEXt chunks: "chara" (V2) and "ccv3" (V3) before IEND
 * 5. Re-encode with proper CRCs
 */
object PngCardWriter {

    fun write(cardJson: String, bitmap: Bitmap): ByteArray {
        // 1. Compress bitmap to PNG to get the standard chunks
        val pngBytes = bitmapToPngBytes(bitmap)

        // 2. Parse existing PNG chunks
        val chunks = parseChunks(pngBytes)

        // 3. Remove existing card tEXt chunks
        val filtered = chunks.filter { chunk ->
            if (chunk.type != "tEXt") return@filter true
            val keyword = extractKeyword(chunk.data)
            keyword.lowercase() !in setOf("chara", "ccv3")
        }.toMutableList()

        // 4. Build base64-encoded card data
        val base64Data = Base64.encodeToString(cardJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // 5. Insert V2 chunk ("chara") before IEND
        val iendIdx = filtered.indexOfLast { it.type == "IEND" }
        val charaChunk = makeTextChunk("chara", base64Data)
        filtered.add(iendIdx, charaChunk)

        // 6. Try to insert V3 chunk ("ccv3") — fails silently if JSON is invalid
        try {
            val v3Data = cardJson
                .replace("\"spec\":\"chara_card_v2\"", "\"spec\":\"chara_card_v3\"")
                .replace("\"spec_version\":\"2.0\"", "\"spec_version\":\"3.0\"")
            val v3Base64 = Base64.encodeToString(v3Data.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val ccv3Chunk = makeTextChunk("ccv3", v3Base64)
            filtered.add(iendIdx, ccv3Chunk)
        } catch (_: Exception) {
            // Silently skip V3 chunk on any error
        }

        // 7. Rebuild PNG
        return buildPng(filtered)
    }

    // region PNG utilities

    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    private data class PngChunk(val type: String, val data: ByteArray)

    private fun parseChunks(pngBytes: ByteArray): List<PngChunk> {
        val buf = ByteBuffer.wrap(pngBytes).order(ByteOrder.BIG_ENDIAN)
        // Skip signature (8 bytes)
        buf.position(8)
        val chunks = mutableListOf<PngChunk>()
        while (buf.hasRemaining()) {
            val len = buf.int
            val typeBytes = ByteArray(4)
            buf.get(typeBytes)
            val type = String(typeBytes, Charsets.US_ASCII)
            val data = ByteArray(len)
            buf.get(data)
            buf.int // skip CRC
            chunks.add(PngChunk(type, data))
        }
        return chunks
    }

    private fun extractKeyword(tEXtData: ByteArray): String {
        val nullIdx = tEXtData.indexOf(0.toByte())
        return if (nullIdx > 0) String(tEXtData, 0, nullIdx, Charsets.US_ASCII) else ""
    }

    private fun makeTextChunk(keyword: String, text: String): PngChunk {
        val kwBytes = keyword.toByteArray(Charsets.US_ASCII)
        val textBytes = text.toByteArray(Charsets.ISO_8859_1)
        val data = ByteArray(kwBytes.size + 1 + textBytes.size)
        System.arraycopy(kwBytes, 0, data, 0, kwBytes.size)
        data[kwBytes.size] = 0 // null separator
        System.arraycopy(textBytes, 0, data, kwBytes.size + 1, textBytes.size)
        return PngChunk("tEXt", data)
    }

    private fun buildPng(chunks: List<PngChunk>): ByteArray {
        val baos = ByteArrayOutputStream()
        // PNG signature
        baos.write(PNG_SIGNATURE)
        val crc = CRC32()
        for (chunk in chunks) {
            val typeBytes = chunk.type.toByteArray(Charsets.US_ASCII)
            val data = chunk.data
            val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(data.size)
            baos.write(buf.array()) // length
            baos.write(typeBytes)   // type
            baos.write(data)        // data
            // CRC: type + data
            crc.reset()
            crc.update(typeBytes)
            crc.update(data)
            val crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            crcBuf.putInt(crc.value.toInt())
            baos.write(crcBuf.array())
        }
        return baos.toByteArray()
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    // endregion
}
