package com.shihab.diplay.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearLayoutGravityTest {
    @Test
    fun fieldWriteDoesNotCallSetGravity() {
        val writes = mutableListOf<Int>()
        var layouts = 0
        var setter = 0
        LinearLayoutGravity.apply(
            current = 17,
            target = 49,
            writeField = { writes += it },
            requestLayout = { layouts++ },
            setGravity = { setter++ },
        )
        assertEquals(listOf(49), writes)
        assertEquals(1, layouts)
        assertEquals(0, setter)
    }

    @Test
    fun sameFieldValueIsANoOp() {
        var setter = 0
        var layouts = 0
        LinearLayoutGravity.apply(
            current = 49,
            target = 49,
            writeField = { throw AssertionError("field write") },
            requestLayout = { layouts++ },
            setGravity = { setter++ },
        )
        assertEquals(0, layouts)
        assertEquals(0, setter)
    }

    @Test
    fun missingFieldUsesSetGravityOnly() {
        val set = mutableListOf<Int>()
        LinearLayoutGravity.apply(
            current = null,
            target = 49,
            writeField = { throw AssertionError("no field") },
            requestLayout = { throw AssertionError("no layout") },
            setGravity = { set += it },
        )
        assertEquals(listOf(49), set)
        assertTrue(set.isNotEmpty())
    }
}
