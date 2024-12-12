package fastapi.http

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

interface TestInterface {
    @Get("/api/test")
    suspend fun test(): String

    @Get("/api/test/{id}")
    suspend fun test2(id: Int): String

    @Post("/api/test")
    suspend fun test3(@JsonBody body: String): String

    @Post("/api/test/{id}")
    suspend fun test4(id: Int, @JsonBody body: String): String

}

//class HttpSpec {
//    @Test
//    fun `test Http`() {
//        runBlocking {
//            async {
//                server<TestInterface>(object : TestInterface {
//                    override suspend fun test(): String {
//                        return "hello"
//                    }
//
//                    override suspend fun test2(id: Int): String {
//                        return "hello $id"
//                    }
//
//                    override suspend fun test3(body: String): String {
//                        return "hello $body"
//                    }
//
//                    override suspend fun test4(id: Int, body: String): String {
//                        return "hello $id $body"
//                    }
//                })
//            }
//
//            delay(1000L)
//
//            val client = client<TestInterface>("http://localhost:8080")
//            println(client.test())
//            println(client.test2(1))
//            println(client.test3("hello"))
//            println(client.test4(1, "hello"))
//        }
//    }
//}
