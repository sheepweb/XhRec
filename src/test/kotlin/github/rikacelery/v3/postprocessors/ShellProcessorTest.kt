package github.rikacelery.v3.postprocessors

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShellProcessorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `finds absolute shell output assignment`() {
        val output = tempDir.resolve("out").resolve("room.fixed.mp4").toFile()

        val files = shellAssignedOutputFiles(
            listOf(
                "bash",
                "-lc",
                "IN=\"/tmp/input.mp4\"; OUT=\"${output.absolutePath}\"; ffmpeg -i \"\$IN\" \"\$OUT\"; echo \"\$OUT\""
            )
        )

        assertEquals(listOf(output), files)
    }

    @Test
    fun `ignores relative shell output assignment`() {
        val files = shellAssignedOutputFiles(listOf("OUT=\"out/room.fixed.mp4\"; echo \"\$OUT\""))

        assertTrue(files.isEmpty())
    }

    @Test
    fun `creates parent directory for assigned shell output`() {
        val output = tempDir.resolve("missing").resolve("room.fixed.mp4").toFile()

        ensureShellOutputParents(listOf("OUT=\"${output.absolutePath}\"; echo \"\$OUT\""))

        assertTrue(output.parentFile.isDirectory)
    }
}
