package fastapi.http

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

interface TestInterface {
    @Http.Get("/api/test")
    suspend fun test(): String

    @Http.Get("/api/test/{id}")
    suspend fun test2(id: Int): String

    @Http.Post("/api/test")
    suspend fun test3(@Http.JsonBody body: String): String

    @Http.Post("/api/test/{id}")
    suspend fun test4(id: Int, @Http.JsonBody body: String): String

}

interface StreamApi {
    @Http.Get("/api/stream")
    @Http.Stream
    suspend fun stream(): Flow<String>

    @Http.Post("/api/echo")
    @Http.Stream
    suspend fun echo(input: Flow<String>): Flow<String>

    @Http.Get("/api/secure")
    suspend fun secure(@Http.Bearer token: String, @Http.Header("X-Trace") trace: String): String

    @Http.Get("/api/fail")
    suspend fun fail(): String
}


class HttpSpec {
    @Test
    fun `test Http`() {
        runBlocking {
            val engine = server<TestInterface, StreamApi>(
                object : TestInterface {
                    override suspend fun test(): String = "hello"
                    override suspend fun test2(id: Int): String = "hello $id"
                    override suspend fun test3(body: String): String = "hello $body"
                    override suspend fun test4(id: Int, body: String): String = "hello $id $body"
                },
                object : StreamApi {
                    override suspend fun stream(): Flow<String> = flow {
                        repeat(3) { i ->
                            emit("s$i")
                            delay(50)
                        }
                    }
                    override suspend fun echo(input: Flow<String>): Flow<String> = flow {
                        input.collect { emit(it.uppercase()) }
                    }
                    override suspend fun secure(token: String, trace: String): String = "ok $token $trace"
                    override suspend fun fail(): String { throw IllegalArgumentException("nope") }
                },
                "0.0.0.0", 8080)

            try {
                delay(1000L)

                val client = client<TestInterface>("http://localhost:8080")
                val sclient = client<StreamApi>("http://localhost:8080")
                println(client.test())
                println(client.test2(1))
                println(client.test3("hello"))
                println(client.test4(1, "hello"))

                // SSE: server push
                val items = sclient.stream().toList()
                assertEquals(listOf("s0","s1","s2"), items)

                println("SSE is done.")

                // WS: bidi echo
                val outgoing = flow {
                    emit("a"); emit("b")
                }
                val echoed = sclient.echo(outgoing).toList()
                assertEquals(listOf("A","B"), echoed)

                println("WS is done.")

                // Header/bearer binding
                val secured = sclient.secure("TOKEN", "TRACE")
                assertEquals("ok TOKEN TRACE", secured)

                // Error taxonomy
                var thrown = false
                try { sclient.fail() } catch (e: Throwable) {
                    thrown = true
                    assertTrue(e is RemoteException, "expected RemoteException")
                    println("RemoteException => ${e.message}")
                }
                assertTrue(thrown, "expected failure from /api/fail")

            } finally {
                engine.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
            }
        }
    }
}
