package fastapi

import fastapi.ffi.python.Python
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

interface Package {
    @Python.Func("string::capwords")
    fun capwords(text: String): String

    @Python.Func("async asyncio::sleep")
    suspend fun sleep(seconds: Double): Unit

    @Python.Func("""
        async def do_work():
            import asyncio
            if not hasattr(G, 'counter'):
                G.counter = 0
            G.counter += 1
            await asyncio.sleep(0.1)
            return G.counter
    """)
    suspend fun do_work(): Int
}

class PythonFFISpec {
    @Test fun `test`() {
        Python.withFFI<Package> { pkg ->
            println(pkg.capwords("hello, world!"))

            runBlocking {
                launch {
                    println("Hello from coroutine!")
                    for (i in 1..10) {
                        println("Coroutine: $i")
                        println("do_work: ${pkg.do_work()}")
                    }
                }
                val t0 = System.currentTimeMillis()
                println("Sleeping for 0.01 seconds...")
                pkg.sleep(0.01)
                val t1 = System.currentTimeMillis()
                println("Done! Slept for ${(t1 - t0)/1000.0} s.")
            }
        }
    }
}
