package me.rerere.rikkahub.ui.pages.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundPerformanceTest {

    @Test
    fun `immersive Tavern freezes the animated background behind its WebView`() {
        val chatPage = sourceFile("ChatPage.kt")
        val background = sourceFile("Background.kt")
        val mesh = sourceFile("MeshGradientBackground.kt")

        assertTrue(chatPage.contains("animateGradient = !useTavernWeb"))
        assertTrue(chatPage.contains("animateImage = useTavernWeb"))
        assertTrue(background.contains("animateGradient: Boolean = true"))
        assertTrue(background.contains("animateImage: Boolean = false"))
        assertTrue(background.contains("ValueAnimator.areAnimatorsEnabled()"))
        assertTrue(background.contains("Lifecycle.State.RESUMED"))
        assertTrue(background.contains("resolveTavernBackgroundMotion("))
        assertTrue(background.contains("graphicsLayer"))
        assertTrue(background.contains("val travel = min(size.width, size.height)"))
        assertTrue(background.contains("translationX = travel * motion.translationFraction * cos(phase)"))
        assertTrue(background.contains("translationY = travel * motion.translationFraction * 0.65f * sin(phase)"))
        assertTrue(background.contains("animated = animateGradient"))
        assertTrue(mesh.contains("animated: Boolean = true"))
        assertTrue(mesh.contains("if (animated)"))
    }

    @Test
    fun `immersive Tavern disables the modal drawer drag gesture`() {
        val chatPage = sourceFile("ChatPage.kt")

        assertTrue(chatPage.contains("val usesImmersiveTavernPresentation ="))
        assertTrue(chatPage.contains("gesturesEnabled = !usesImmersiveTavernPresentation"))
        assertTrue(chatPage.contains("scope.launch { drawerState.open() }"))
    }

    private fun sourceFile(name: String): String = listOf(
        File("src/main/java/me/rerere/rikkahub/ui/pages/chat/$name"),
        File("app/src/main/java/me/rerere/rikkahub/ui/pages/chat/$name"),
    ).firstOrNull { it.exists() }?.readText()
        ?: error("$name not found in test working dir")
}
