package cn.iamplus.pushbox.ui.screens

import cn.iamplus.pushbox.data.MessageRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesFlowTest {
    @Test
    fun loadingIsDistinctFromAnEmptyConversation() = runBlocking {
        val states = conversationMessages(flowOf("")) { flowOf(emptyList()) }.toList()
        assertEquals(2, states.size)
        assertNull(states.first().rows)
        assertEquals(emptyList<MessageRow>(), states.last().rows)
        assertNull(states.last().query)
    }

    @Test
    fun rapidInputOnlyQueriesTheLatestTrimmedText() = runBlocking {
        val queried = mutableListOf<String?>()
        val input = flow {
            emit("a")
            yield()
            emit("ab")
            yield()
            emit("  abc  ")
        }
        val result = conversationMessages(input) {
            queried += it
            flowOf(listOf(row("hit")))
        }.toList()
        assertEquals(listOf("abc"), queried)
        assertEquals("abc", result.last().query)
        assertEquals("hit", result.last().rows!!.single().msgId)
    }

    @Test
    fun clearingInputCancelsPendingSearchAndLoadsAllRows() = runBlocking {
        val queried = mutableListOf<String?>()
        val input = flow {
            emit("not-yet-searched")
            yield()
            emit(" \n ")
        }
        val states = conversationMessages(input) {
            queried += it
            flowOf(listOf(row("all")))
        }.toList()
        assertEquals(listOf<String?>(null), queried)
        assertEquals(ConversationMessages(null, listOf(row("all"))), states.last())
    }

    @Test
    fun newSearchCancelsOldSubscriptionBeforePublishingItsRows() = runBlocking {
        withTimeout(5_000) {
            val inputs = Channel<String>(Channel.UNLIMITED)
            val states = Channel<ConversationMessages>(Channel.UNLIMITED)
            val cancelled = CompletableDeferred<Unit>()
            val job = launch {
                conversationMessages(inputs.receiveAsFlow()) { query ->
                    if (query == null) flow {
                        try {
                            emit(listOf(row("all")))
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    } else flowOf(listOf(row("filtered")))
                }.collect { states.send(it) }
            }
            inputs.send("")
            assertNull(states.receive().rows)
            assertEquals("all", states.receive().rows!!.single().msgId)
            inputs.send("hit")
            assertEquals(ConversationMessages("hit"), states.receive())
            assertTrue(cancelled.isCompleted)
            assertEquals(ConversationMessages("hit", listOf(row("filtered"))), states.receive())
            job.cancelAndJoin()
        }
    }

    @Test
    fun closingTheScreenCancelsItsRoomSubscription() = runBlocking {
        withTimeout(5_000) {
            val subscribed = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                conversationMessages(flowOf("")) {
                    flow<List<MessageRow>> {
                        try {
                            subscribed.complete(Unit)
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                }.collect {}
            }
            subscribed.await()
            job.cancelAndJoin()
            assertTrue(cancelled.isCompleted)
        }
    }

    @Test
    fun separateScreensDoNotShareQueryOrRows() = runBlocking {
        val first = conversationMessages(flowOf("search")) {
            flowOf(listOf(row("first")))
        }.toList()
        val second = conversationMessages(flowOf("")) {
            flowOf(listOf(row("second")))
        }.toList()
        assertEquals("search", first.last().query)
        assertNull(second.last().query)
        assertEquals("first", first.last().rows!!.single().msgId)
        assertEquals("second", second.last().rows!!.single().msgId)
    }

    private fun row(id: String) = MessageRow(
        msgId = id, seq = 1, channelNo = "channel", senderId = "sender",
        msgType = "text", title = null, summary = null, body = id, level = "info",
        linkUrl = null, mediaStatus = null, createdAt = 1,
    )
}
