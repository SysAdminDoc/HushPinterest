/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.pinterest.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.localRegisterCount
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.extension.requireStub
import app.morphe.patches.pinterest.misc.extension.requireThisIntact
import app.morphe.patches.pinterest.misc.extension.writeStub
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Customize board tabs"
internal const val BOARD_TAB_STYLE = "com.pinterest.STRUCTURED_FEED_LANDING_PAGE_HEADER_STYLE"
internal const val BOARD_TAB_HEADER_BASE = "Landroid/widget/LinearLayout;"
internal const val BOARD_TABS = "$EXTENSION_PACKAGE/ui/BoardTabs;"
internal const val BOARD_TAB_BIND_HOOK = "$BOARD_TABS->bind(Landroid/view/View;Ljava/lang/Object;)V"
private const val BUNDLE = "Landroid/os/Bundle;"

/** The screen of a board tab, the method handing its header new data, and the header call in it. */
private class BoardTabTarget(val style: Method, val bind: Method, val call: Int, val header: String)

/**
 * The header is the screen's LinearLayout field, and the binding method is the one passing its own
 * single argument straight to that header. Other screens read the same style string, but none of
 * them has such a header.
 */
private fun BytecodePatchContext.boardTabTarget(): BoardTabTarget =
    methodsWithString(BOARD_TAB_STYLE).filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }.flatMap { style ->
        val owner = classDefBy(style.definingClass)
        val headers = owner.fields.filterNot { AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }
            .filter { classDefByOrNull(it)?.superclass == BOARD_TAB_HEADER_BASE }.toSet()
        owner.methods.filter { it.returnType == "V" && it.parameterTypes.size == 1 }.mapNotNull { method ->
            val calls = method.instructions().withIndex().filter { (_, instruction) ->
                val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                instruction.opcode == Opcode.INVOKE_VIRTUAL && called != null && called.definingClass in headers &&
                    called.returnType == "V" && called.parameters() == method.parameters()
            }
            calls.singleOrNull()?.let { (index, instruction) ->
                val called = (instruction as ReferenceInstruction).reference as MethodReference
                BoardTabTarget(style, method, index, called.definingClass)
            }
        }
    }.one("$PATCH: the board tab screen")

/** The read of the screen's arguments that the style check starts with. */
private fun BytecodePatchContext.writeScreenArguments(style: Method) {
    val description = style.instructions().firstOrNull { it.opcode == Opcode.IGET_OBJECT }
        ?.let { (it as ReferenceInstruction).reference as FieldReference }
        ?: throw PatchException("$PATCH: the style check reads no screen description")
    val arguments = style.calls().firstOrNull { it.returnType == BUNDLE && it.parameterTypes.isEmpty() }
        ?: throw PatchException("$PATCH: the style check reads no screen arguments")
    writeStub(BOARD_TABS, "screenArguments", 2, """
        check-cast p0, ${description.definingClass}
        iget-object v0, p0, $description
        if-eqz v0, :none
        invoke-interface { v0 }, $arguments
        move-result-object v0
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
}

@Suppress("unused")
val customizeBoardTabsPatch = bytecodePatch(
    name = PATCH,
    description = "Tidies the picture at the top of board tabs on Home. You can hide the See board button and the " +
        "More ideas for line, so only the board name is left, and move the name. Starts off. Turn them on in " +
        "HushPinterest settings > Interface.",
) {
    category("Navigation")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("boardTabs")
        requireStatusMethod("boardTabHeader")
        requireStub(BOARD_TABS, "screenArguments")
        val target = boardTabTarget()
        val call = target.bind.instructions()[target.call] as FiveRegisterInstruction
        val self = target.bind.localRegisterCount()
        if (call.registerC > 15 || self > 15) throw PatchException("$PATCH: the header call's registers are out of range")
        target.bind.requireThisIntact(PATCH, listOf(target.call))
        writeScreenArguments(target.style)
        mutable(target.bind).addInstructions(target.call, "invoke-static { v${call.registerC}, v$self }, $BOARD_TAB_BIND_HOOK")
        enableCapability("boardTabHeader")
        enableStatus("boardTabs")
    }
}
