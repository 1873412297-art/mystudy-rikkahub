package me.rerere.rikkahub.service

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TavernScriptRunnerServiceTest {

    private val client = TavernScriptRunnerClient(ApplicationProvider.getApplicationContext())

    @Test
    fun timedOutScriptDoesNotPoisonNextInvocation() = runBlocking {
        assertNull(client.invoke("function(args) { while (true) {} }", "", timeoutMs = 150))
        assertEquals("recovered", client.invoke("function(args) { return args; }", "recovered", timeoutMs = 2_000))
    }
}
