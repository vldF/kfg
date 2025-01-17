package org.vorpal.research.kfg.util

import org.vorpal.research.kthelper.collection.queueOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URLClassLoader


val File.isJar get() = this.name.endsWith(".jar")
val File.isClass get() = this.name.endsWith(".class")
val File.className get() = this.name.removeSuffix(".class")

val File.classLoader get() = URLClassLoader(arrayOf(toURI().toURL()))

val File.allEntries: List<File>
    get() {
        val result = mutableListOf<File>()
        val queue = queueOf(this)
        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current.isFile) {
                result += current
            } else if (current.isDirectory) {
                queue.addAll(current.listFiles() ?: arrayOf())
            }
        }
        return result
    }

private const val MAX_BYTE_ARRAY_SIZE = 16384

val InputStream.asByteArray: ByteArray get() {
    val buffer = ByteArrayOutputStream()

    var nRead: Int
    val data = ByteArray(MAX_BYTE_ARRAY_SIZE)

    while (this.read(data, 0, data.size).also { nRead = it } != -1) {
        buffer.write(data, 0, nRead)
    }

    return buffer.toByteArray()
}

fun File.write(input: InputStream) = this.writeBytes(input.asByteArray)