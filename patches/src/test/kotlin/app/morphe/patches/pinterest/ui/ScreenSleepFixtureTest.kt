/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.ExtensionDex
import app.morphe.FixtureDex
import app.morphe.FixtureTests
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** The calls that hold the screen on, in each declared APK. */
@Category(FixtureTests::class)
class ScreenSleepFixtureTest {
    @Test
    fun `each declared APK hands the video view's screen hold to the extension on the same registers`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = read(build)
            val holds = holdsIn(classes)
            // The 14.45.0 dex holds the screen in one place: the builder that makes the video view.
            assertEquals("${build.name}: screen holds", 1, holds.values.sumOf { it.size })
            assertTrue("${build.name}: the hold is in the video view's builder",
                holds.keys.all { it.returnType == VIDEO_VIEW })
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            letScreenSleepPatch.execute(context)
            for (flag in listOf("letScreenSleep", "screenSleep")) assertEquals("${build.name}: $flag", 1, PinPageKit.flag(context, flag))

            for ((method, indexes) in holds) {
                val before = method.implementation!!.instructions.toList()
                val after = PinPageKit.patched(context, method)
                assertEquals("${build.name}: ${method.name} keeps its length", before.size, after.size)
                for (index in indexes) {
                    val was = before[index] as FiveRegisterInstruction
                    val call = after[index] as FiveRegisterInstruction
                    assertEquals(build.name, Opcode.INVOKE_STATIC, after[index].opcode)
                    assertEquals(build.name, SCREEN_SLEEP_HOOK, (call as ReferenceInstruction).reference.toString())
                    assertEquals("${build.name}: the view register", was.registerC, call.registerC)
                    assertEquals("${build.name}: the value register", was.registerD, call.registerD)
                }
                val rest = before.indices.filter { it !in indexes }
                assertEquals("${build.name}: the rest of ${method.name} is unchanged",
                    rest.map { before[it].opcode }, rest.map { after[it].opcode })
                assertTrue("${build.name}: no screen hold is left in ${method.name}", after.none { it.isKeepScreenOnCall() })
            }
        }
    }

    @Test
    fun `a missing hook, status stub or screen hold refuses with Pinterest's call untouched`() {
        val classes = read(Fixtures.declaredBuilds().last())
        val extension = ExtensionDex.classes()
        fun without(name: String) = extension.map { classDef -> if (classDef.type != UI_HOOKS) classDef else
            ImmutableClassDef(classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces, classDef.sourceFile,
                classDef.annotations, classDef.fields, classDef.methods.filterNot { it.name == name }) }
        val cases = linkedMapOf(
            "no letScreenSleep stub" to (PinPageKit.extensionWithout("letScreenSleep") + classes to "letScreenSleep"),
            "no screenSleep stub" to (PinPageKit.extensionWithout("screenSleep") + classes to "screenSleep"),
            "no keepScreenOn hook" to (without("keepScreenOn") + classes to "no keepScreenOn"),
            "no screen hold" to (extension to "never asks to hold the screen on"),
        )
        for ((reason, case) in cases) {
            val (input, message) = case
            val context = PatchContexts.of(input)
            val thrown = assertThrows(reason, PatchException::class.java) { letScreenSleepPatch.execute(context) }
            assertTrue("$reason: ${thrown.message}", thrown.message.orEmpty().contains(message))
            assertEquals(reason, 0, PinPageKit.flagOrZero(context, "letScreenSleep"))
            assertEquals(reason, 0, PinPageKit.flagOrZero(context, "screenSleep"))
            val present = input.map { it.type }.toSet()
            for (method in holdsIn(classes).keys.filter { it.definingClass in present }) {
                assertEquals("$reason changed ${method.definingClass}->${method.name}",
                    method.implementation!!.instructions.map { it.opcode }, PinPageKit.patched(context, method).map { it.opcode })
            }
        }
    }

    /** Each method that calls View.setKeepScreenOn, with where, read off the clean dex. */
    private fun holdsIn(classes: List<ClassDef>): Map<Method, List<Int>> =
        classes.flatMap { it.methods }.associateWith { screenHoldIndexes(it) }.filterValues { it.isNotEmpty() }

    /** Every class in the APK that calls View.setKeepScreenOn. */
    private fun read(build: java.io.File): List<ClassDef> {
        val found = FixtureDex.classesWhere(build, { true }) { method -> screenHoldIndexes(method).isNotEmpty() }
        check(found.isNotEmpty()) { "${build.name}: nothing holds the screen on" }
        return found
    }
}

private const val VIDEO_VIEW = "Lcom/pinterest/video/core/view/StyledPinterestVideoView;"
