package net.kuecker.lodestar.kit.interchange

/** Reads the interchange fixtures (`fixtures/interchange/`), the same files the Swift tests use. */
internal object InterchangeTestSupport {
    fun fixture(name: String): String {
        val stream = checkNotNull(InterchangeTestSupport::class.java.getResourceAsStream("/fixtures/interchange/$name")) {
            "Fixture $name is missing from the test resources"
        }
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }
}
