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
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.pinterest.misc.extension.PatchLogCapture
import app.morphe.patches.pinterest.misc.extension.SETTINGS_STATUS
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.io.File

/** Customize board tabs on each declared build: where its hooks land, and what a missing target does. */
@Category(FixtureTests::class)
class BoardTabsFixtureTest {
    private fun flag(context: BytecodePatchContext, name: String) =
        (context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.implementation!!
            .instructions.first() as NarrowLiteralInstruction).narrowLiteral

    @Test fun `the screen hands over its header and itself right before binding the header`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = slice(build)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            val warnings = PatchLogCapture.warnings { customizeBoardTabsPatch.execute(context) }
            assertEquals(warnings.toString(), 0, warnings.size)
            assertEquals(build.name, 1, flag(context, "boardTabs"))
            assertEquals(build.name, 1, flag(context, "boardTabHeader"))
            val hooked = classes.flatMap { owner -> context.mutableClassDefBy(owner.type).methods.toList() }.filter { method ->
                method.implementation?.instructions?.any { (it as? ReferenceInstruction)?.reference?.toString() == BOARD_TAB_BIND_HOOK } == true
            }.single()
            val body = hooked.implementation!!.instructions.toList()
            val at = body.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == BOARD_TAB_BIND_HOOK }
            val hook = body[at] as FiveRegisterInstruction
            val header = body[at + 1] as FiveRegisterInstruction
            assertEquals(build.name, Opcode.INVOKE_VIRTUAL, body[at + 1].opcode)
            assertEquals("${build.name}: the header", header.registerC, hook.registerC)
            assertEquals("${build.name}: the screen", hooked.implementation!!.registerCount - 2, hook.registerD)
            val stub = context.mutableClassDefBy(BOARD_TABS).methods.single { it.name == "screenArguments" }
            assertTrue(build.name, stub.calls().any { it.returnType == "Landroid/os/Bundle;" })
        }
    }

    @Test fun `a build without the header is refused`() {
        val build = Fixtures.declaredBuilds().last()
        val context = PatchContexts.of(ExtensionDex.classes() + slice(build).filter { it.superclass != BOARD_TAB_HEADER_BASE })
        assertThrows(PatchException::class.java) { customizeBoardTabsPatch.execute(context) }
        assertEquals(0, flag(context, "boardTabs"))
        assertEquals(0, flag(context, "boardTabHeader"))
    }

    private companion object {
        val slices = mutableMapOf<File, List<ClassDef>>()

        /** Every class with the style string, the classes of their fields, and those of the header's fields. */
        fun slice(build: File) = slices.getOrPut(build) {
            val owners = FixtureDex.classesWhere(build, { true }) { BOARD_TAB_STYLE in it.strings() }
            val fields = FixtureDex.classes(build, owners.flatMap { owner -> owner.fields.map { it.type } }.toSet())
            val parts = FixtureDex.classes(build, fields.values.filter { it.superclass == BOARD_TAB_HEADER_BASE }
                .flatMap { header -> header.fields.map { it.type } }.toSet())
            (owners + fields.values + parts.values).associateBy { it.type }.values.toList()
        }
    }
}
