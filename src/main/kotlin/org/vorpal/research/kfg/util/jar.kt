package org.vorpal.research.kfg.util

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.JSRInlinerAdapter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.util.CheckClassAdapter
import org.vorpal.research.kfg.ClassManager
import org.vorpal.research.kfg.KfgException
import org.vorpal.research.kfg.Package
import org.vorpal.research.kfg.builder.asm.ClassBuilder
import org.vorpal.research.kfg.builder.cfg.LabelFilterer
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kthelper.`try`
import java.io.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

val JarEntry.isClass get() = this.name.endsWith(".class")
val JarEntry.fullName get() = this.name.removeSuffix(".class")
val JarEntry.pkg get() = Package(fullName.dropLastWhile { it != Package.SEPARATOR })
val JarEntry.isManifest get() = this.name == "META-INF/MANIFEST.MF"

val JarFile.classLoader get() = File(this.name).classLoader

val ClassNode.hasFrameInfo: Boolean
    get() {
        var hasInfo = false
        for (mn in methods) {
            hasInfo = hasInfo || mn.instructions.any { it is FrameNode }
        }
        return hasInfo
    }

internal fun ClassNode.inlineJsrs() {
    this.methods = methods.map { it.jsrInlined }
}

internal fun Class.restoreMethodNodes() {
    cn.methods = allMethods.map { it.mn }
}

internal val MethodNode.jsrInlined: MethodNode
    get() {
        val temp = JSRInlinerAdapter(null, access, name, desc, signature, exceptions?.toTypedArray())
        this.accept(temp)
        return LabelFilterer(temp).build()
    }

class ClassReadError(msg: String) : KfgException(msg)

data class Flags(val value: Int) : Comparable<Flags> {
    companion object {
        val readAll = Flags(0)
        val readSkipDebug = Flags(ClassReader.SKIP_DEBUG)
        val readSkipFrames = Flags(ClassReader.SKIP_FRAMES)
        val readCodeOnly = readSkipDebug + readSkipFrames

        val writeComputeNone = Flags(0)
        val writeComputeFrames = Flags(ClassWriter.COMPUTE_FRAMES)
        val writeComputeMaxs = Flags(ClassWriter.COMPUTE_MAXS)
        val writeComputeAll = writeComputeFrames
    }

    fun merge(other: Flags) = Flags(this.value or other.value)
    operator fun plus(other: Flags) = this.merge(other)

    override fun compareTo(other: Flags) = value.compareTo(other.value)
}

class KfgClassWriter(private val loader: ClassLoader, flags: Flags) : ClassWriter(flags.value) {

    private fun readClass(type: String) = try {
        java.lang.Class.forName(type.replace(Package.SEPARATOR, Package.CANONICAL_SEPARATOR), false, loader)
    } catch (e: Throwable) {
        throw ClassReadError(e.toString())
    }

    override fun getCommonSuperClass(type1: String, type2: String): String = try {
        var class1 = readClass(type1)
        val class2 = readClass(type2)

        when {
            class1.isAssignableFrom(class2) -> type1
            class2.isAssignableFrom(class1) -> type2
            class1.isInterface || class2.isInterface -> "java/lang/Object"
            else -> {
                do {
                    class1 = class1.superclass
                } while (!class1.isAssignableFrom(class2))
                class1.name.replace(Package.CANONICAL_SEPARATOR, Package.SEPARATOR)
            }
        }
    } catch (e: Throwable) {
        "java/lang/Object"
    }
}

class JarBuilder(val name: String, manifest: Manifest) {
    private val jar = JarOutputStream(FileOutputStream(name), manifest)

    fun add(source: File) {
        if (source.isDirectory) {
            var name = source.path.replace("\\", "/")
            if (name.isNotEmpty()) {
                if (!name.endsWith("/"))
                    name += "/"
                val entry = JarEntry(name)
                entry.time = source.lastModified()
                jar.putNextEntry(entry)
                jar.closeEntry()
            }

        } else {
            val entry = JarEntry(source.path.replace("\\", "/"))
            entry.time = source.lastModified()
            add(entry, FileInputStream(source))
        }
    }

    operator fun plusAssign(source: File) {
        add(source)
    }

    fun add(entry: JarEntry, fis: InputStream) {
        jar.putNextEntry(entry)
        val `in` = BufferedInputStream(fis)

        val buffer = ByteArray(1024)
        while (true) {
            val count = `in`.read(buffer)
            if (count == -1) break
            jar.write(buffer, 0, count)
        }
        jar.closeEntry()
    }

    fun close() {
        jar.close()
    }
}

internal fun readClassNode(input: InputStream, flags: Flags = Flags.readAll): ClassNode {
    val classReader = ClassReader(input)
    val classNode = ClassNode()
    classReader.accept(classNode, flags.value)
    return classNode
}

internal fun ClassNode.recomputeFrames(loader: ClassLoader): ClassNode {
    val ba = this.toByteArray(loader)
    return ba.toClassNode()
}

private fun ByteArray.toClassNode(): ClassNode {
    val classReader = ClassReader(this.inputStream())
    val classNode = ClassNode()
    classReader.accept(classNode, Flags.readAll.value)
    return classNode
}

private fun ClassNode.toByteArray(
    loader: ClassLoader,
    flags: Flags = Flags.writeComputeAll,
    checkClass: Boolean = false
): ByteArray {
    this.inlineJsrs()
    val cw = KfgClassWriter(loader, flags)
    val adapter = when {
        checkClass -> CheckClassAdapter(cw)
        else -> cw
    }
    this.accept(adapter)
    return cw.toByteArray()
}

internal fun ClassNode.write(
    loader: ClassLoader,
    path: Path,
    flags: Flags = Flags.writeComputeAll,
    checkClass: Boolean = false
): File =
    path.toFile().apply {
        parentFile?.mkdirs()
        this.writeBytes(this@write.toByteArray(loader, flags, checkClass))
    }

fun Class.write(
    cm: ClassManager, loader: ClassLoader,
    path: Path = Paths.get("$fullName.class"),
    flags: Flags = Flags.writeComputeFrames,
    checkClass: Boolean = false
): File = `try` {
    ClassBuilder(cm, this).build().write(loader, path, flags, checkClass)
}.also {
    this.restoreMethodNodes()
}.getOrThrow()
