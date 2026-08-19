package me.rerere.rikkahub.utils

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `maps GitHub release and filters non APK assets`() {
        val release = Json.decodeFromString<GitHubRelease>(releaseJson)

        val update = release.toUpdateInfo()

        assertEquals("2.4.6", update.version)
        assertEquals("修复更新检查。", update.changelog)
        assertEquals(1, update.downloads.size)
        assertEquals("app-universal-release.apk", update.downloads.single().name)
        assertEquals(
            "https://github.com/1873412297-art/mystudy-rikkahub/releases/download/v2.4.6/app-universal-release.apk",
            update.downloads.single().url,
        )
        assertEquals("2.0 MB", update.downloads.single().size)
    }

    @Test
    fun `normalizes v prefix for version comparison`() {
        val update = Json.decodeFromString<GitHubRelease>(releaseJson).toUpdateInfo()

        assertTrue(Version(update.version) > Version("2.4.5"))
    }

    @Test
    fun `rejects release without APK assets`() {
        val release = Json.decodeFromString<GitHubRelease>(releaseJsonWithoutApk)

        assertThrows(IllegalArgumentException::class.java) {
            release.toUpdateInfo()
        }
    }

    private companion object {
        const val releaseJson = """
            {
              "tag_name": "v2.4.6",
              "published_at": "2026-08-20T00:00:00Z",
              "body": "修复更新检查。",
              "assets": [
                {
                  "name": "app-universal-release.apk",
                  "browser_download_url": "https://github.com/1873412297-art/mystudy-rikkahub/releases/download/v2.4.6/app-universal-release.apk",
                  "size": 2097152
                },
                {
                  "name": "Source-code.zip",
                  "browser_download_url": "https://example.com/source.zip",
                  "size": 100
                }
              ]
            }
        """

        const val releaseJsonWithoutApk = """
            {
              "tag_name": "v2.4.6",
              "published_at": "2026-08-20T00:00:00Z",
              "body": "没有安装包。",
              "assets": [
                {
                  "name": "Source-code.zip",
                  "browser_download_url": "https://example.com/source.zip",
                  "size": 100
                }
              ]
            }
        """
    }
}
