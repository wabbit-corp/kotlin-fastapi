package fastapi

import fastapi.ffi.Version
import org.junit.Test
import org.junit.Assert.*

class VersionTest {

    @Test
    fun `test equal versions`() {
        assertEquals(0, Version(listOf(1, 0, 0)).compareTo(Version(listOf(1, 0, 0))))
    }

    @Test
    fun `test major version differences`() {
        assertTrue(Version(listOf(2, 0, 0)) > Version(listOf(1, 0, 0)))
        assertTrue(Version(listOf(1, 0, 0)) < Version(listOf(2, 0, 0)))
    }

    @Test
    fun `test minor version differences`() {
        assertTrue(Version(listOf(1, 2, 0)) > Version(listOf(1, 1, 0)))
        assertTrue(Version(listOf(1, 1, 0)) < Version(listOf(1, 2, 0)))
    }

    @Test
    fun `test patch version differences`() {
        assertTrue(Version(listOf(1, 0, 2)) > Version(listOf(1, 0, 1)))
        assertTrue(Version(listOf(1, 0, 1)) < Version(listOf(1, 0, 2)))
    }

    @Test
    fun `test different length versions`() {
        assertTrue(Version(listOf(1, 0, 0, 1)) > Version(listOf(1, 0, 0)))
        assertTrue(Version(listOf(1, 0, 0)) < Version(listOf(1, 0, 0, 1)))
    }
}
