package org.vorpal.research.kfg

import org.vorpal.research.kfg.container.DirectoryContainer
import org.vorpal.research.kfg.container.JarContainer
import org.vorpal.research.kfg.ir.BodyBlock
import org.vorpal.research.kfg.ir.Class
import org.vorpal.research.kfg.ir.Method
import org.vorpal.research.kfg.ir.value.usageContext
import org.vorpal.research.kfg.visitor.ClassVisitor
import org.vorpal.research.kfg.visitor.executePipeline
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*
import kotlin.test.*

// simple test: run kfg on itself and check nothing fails
class KfgIntegrationTest {
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()

    val pkg = Package.parse("org.vorpal.research.kfg.*")
    lateinit var jar: JarContainer
    lateinit var cm: ClassManager

    @BeforeTest
    fun setUp() {
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))

        val version = System.getProperty("project.version")
        val jarPath = "target/kfg-$version-jar-with-dependencies.jar"

        jar = JarContainer(jarPath, pkg)
        cm = ClassManager(
            KfgConfigBuilder()
                .failOnError(false)
                .verifyIR(true)
                .checkClasses(true)
                .build()
        )
        cm.initialize(jar)
    }

    @AfterTest
    fun tearDown() {
        System.setOut(System.out)
        System.setErr(System.err)
    }

    @Test
    fun run() {
        val target = Files.createTempDirectory(Paths.get("."), "kfg")
        jar.update(cm, target)

        assertTrue(out.toString().isBlank(), out.toString())
        assertTrue(err.toString().isBlank(), err.toString())
        if (!target.toFile().deleteRecursively()) {
            System.err.println("Could not delete temp directory ${target.toAbsolutePath()}")
        }
    }

    @Test
    fun directoryContainerTest() {
        val targetDirPath = Paths.get("./target/classes")

        val container = DirectoryContainer(targetDirPath, pkg)
        val cm = ClassManager(
            KfgConfigBuilder()
                .failOnError(false)
                .build()
        )
        cm.initialize(container)


        val target = Files.createTempDirectory("kfg")
        container.update(cm, target)

        assertTrue(out.toString().isBlank(), out.toString())
        assertTrue(err.toString().isBlank(), err.toString())
        if (!target.toFile().deleteRecursively()) {
            System.err.println("Could not delete temp directory ${target.toAbsolutePath()}")
        }
    }

    @Test
    fun packagePipelineTest() {
        val visitedClasses = mutableSetOf<Class>()
        executePipeline(cm, pkg) {
            +object : ClassVisitor {
                override val cm: ClassManager
                    get() = this@KfgIntegrationTest.cm

                override fun cleanup() {}

                override fun visit(klass: Class) {
                    super.visit(klass)
                    visitedClasses += klass
                }
            }
        }

        assertEquals(cm.concreteClasses.intersect(visitedClasses), cm.concreteClasses)
        assertTrue((cm.concreteClasses - visitedClasses).isEmpty())
    }

    @Test
    fun classPipelineTest() {
        val klass = cm.concreteClasses.random()
        val targetClasses = run {
            val result = mutableSetOf<Class>(klass)
            val queue = ArrayDeque<Class>(listOf(klass))
            while (queue.isNotEmpty()) {
                val first = queue.pollFirst()
                result += first
                queue.addAll(first.innerClasses.keys.filterNot { it in result })
            }
            result
        }

        val visitedClasses = mutableSetOf<Class>()
        executePipeline(cm, klass) {
            +object : ClassVisitor {
                override val cm: ClassManager
                    get() = this@KfgIntegrationTest.cm

                override fun cleanup() {}

                override fun visit(klass: Class) {
                    super.visit(klass)
                    visitedClasses += klass
                }
            }
        }

        assertEquals(targetClasses.intersect(visitedClasses), targetClasses)
        assertTrue((targetClasses - visitedClasses).isEmpty())
    }

    @Test
    fun methodPipelineTest() {
        val klass = run {
            var temp = cm.concreteClasses.random()
            while (temp.methods.isEmpty())
                temp = cm.concreteClasses.random()
            temp
        }
        val targetMethods = klass.getMethods(klass.methods.random().name)

        val visitedMethods = mutableSetOf<Method>()
        executePipeline(cm, targetMethods) {
            +object : ClassVisitor {
                override val cm: ClassManager
                    get() = this@KfgIntegrationTest.cm

                override fun cleanup() {}

                override fun visitMethod(method: Method) {
                    super.visitMethod(method)
                    visitedMethods += method
                }
            }
        }

        assertEquals(targetMethods.intersect(visitedMethods), targetMethods)
        assertTrue((targetMethods - visitedMethods).isEmpty())
    }

    @Test
    fun classCreateTest() {
        val klass = cm.createClass(jar, pkg.concretized, "NewlyCreatedKlass")
        klass.superClass = cm.objectClass
        klass.isPublic = true

        val testField = klass.addField("newlyCreatedField", cm.type.intType)
        testField.defaultValue = cm.value.getConstant(12)

        val initMethod = klass.addMethod(Method.CONSTRUCTOR_NAME, cm.type.voidType)
        with(initMethod.usageContext) {
            val block = BodyBlock("entry")
            block.add(inst(cm) { `return`() })
            initMethod.body.add(block)
        }
        val target = Files.createTempDirectory(Paths.get("."), "kfg")
        jar.update(cm, target)
        if (!target.toFile().deleteRecursively()) {
            System.err.println("Could not delete temp directory ${target.toAbsolutePath()}")
        }
    }
}