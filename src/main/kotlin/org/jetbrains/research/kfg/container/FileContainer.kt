package org.jetbrains.research.kfg.container

import org.jetbrains.research.kfg.ClassManager
import org.jetbrains.research.kfg.Package
import org.jetbrains.research.kfg.ir.ConcreteClass
import org.jetbrains.research.kfg.util.*
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.nio.file.Path

class FileContainer(private val file: File, override val pkg: Package) : Container {
    override val name: String
        get() = file.absolutePath

    override val classLoader: ClassLoader
        get() = file.classLoader


    override fun parse(flags: Flags): Map<String, ClassNode> {
        val classes = mutableMapOf<String, ClassNode>()
        if (file.isClass && pkg.isParent(file.className)) {
            val classNode = readClassNode(file.inputStream(), flags)

            // need to recompute frames because sometimes original Jar classes don't contain frame info
            classes[classNode.name] = when {
                classNode.hasFrameInfo -> classNode
                else -> classNode.recomputeFrames(classLoader)
            }
        }
        return classes
    }

    override fun unpack(cm: ClassManager, target: Path, unpackAllClasses: Boolean) {
        val loader = file.classLoader

        val absolutePath = target.toAbsolutePath()
        if (file.isClass) {
            val `class` = cm[file.className]
            when {
                pkg.isParent(file.name) && `class` is ConcreteClass -> {
                    val localPath = "${`class`.fullname}.class"
                    val path = "$absolutePath/$localPath"
                    `class`.write(cm, loader, path, Flags.writeComputeFrames)
                }
                unpackAllClasses -> {
                    val path = "$absolutePath/${file.name}"
                    val classNode = readClassNode(file.inputStream())
                    classNode.write(loader, path, Flags.writeComputeNone)
                }
            }
        }
    }

    override fun update(cm: ClassManager, target: Path): Container {
        val absolutePath = target.toAbsolutePath()
        unpack(cm, target)

        if (file.isClass && pkg.isParent(file.name)) {
            val `class` = cm[file.className]

            if (`class` is ConcreteClass) {
                val localName = "${`class`.fullname}.class"

                File(absolutePath.toString(), localName).write(file.inputStream())
            }
        }
        return DirectoryContainer(target.toFile(), pkg)
    }
}