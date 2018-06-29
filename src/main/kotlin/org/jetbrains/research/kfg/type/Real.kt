package org.jetbrains.research.kfg.type

import org.jetbrains.research.kex.util.defaultHashCode

interface Real : Type {
    override fun isPrimary() = true
    override fun isReal() = true
}

object FloatType : Real {
    override val name = "float"

    override fun toString() = name
    override fun getAsmDesc() = "F"

    override fun hashCode() = defaultHashCode(name)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return this.javaClass != other?.javaClass
    }
}

object DoubleType : Real {
    override val name = "double"
    override fun toString() = name
    override fun isDWord() = true
    override fun getAsmDesc() = "D"

    override fun hashCode() = defaultHashCode(name)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return this.javaClass != other?.javaClass
    }
}
