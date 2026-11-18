/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.EXTENSION_ROOT
import app.morphe.patches.pinterest.misc.settings.sendToStandIn
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Let the screen sleep"

/** The one call that holds the screen on for as long as its view is on the screen. */
internal const val KEEP_SCREEN_ON = "Landroid/view/View;->setKeepScreenOn(Z)V"

internal const val SCREEN_SLEEP_HOOK = "$UI_HOOKS->keepScreenOn(Landroid/view/View;Z)V"

/** One call in Pinterest that asks to hold the screen on: the [method] and where in it. */
internal data class ScreenHold(val method: Method, val index: Int)

@Suppress("unused")
val letScreenSleepPatch = bytecodePatch(
    name = PATCH,
    description = "Lets the screen turn off after your phone's usual timeout while a pin is open. Pinterest keeps " +
        "it on while a video is on the screen, playing or not. Starts off. Turn it on in HushPinterest settings > " +
        "Interface.",
) {
    category("Pin page")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("letScreenSleep")
        requireStatusMethod("screenSleep")
        requireScreenSleepHook()
        val holds = screenHolds()
        if (holds.isEmpty()) throw PatchException("$PATCH: this Pinterest build never asks to hold the screen on")
        // The hook takes the view and the value on the same registers and answers nothing, as the
        // call it stands in for does. A method's later calls go first, so earlier indexes still hold.
        holds.groupBy { it.method }.forEach { (method, sites) ->
            val target = mutable(method)
            sites.sortedByDescending { it.index }.forEach { site ->
                target.instructions().getOrNull(site.index)?.takeIf { it.isKeepScreenOnCall() } ?: throw PatchException(
                    "$PATCH: ${method.definingClass}->${method.name} doesn't hold the screen where it was found",
                )
                target.sendToStandIn(site.index, SCREEN_SLEEP_HOOK)
            }
        }
        enableCapability("screenSleep")
        enableStatus("letScreenSleep")
    }
}

/** Throws unless UiHooks has a public static keepScreenOn that takes a view and a boolean. */
private fun BytecodePatchContext.requireScreenSleepHook() {
    mutableClassDefBy(UI_HOOKS).methods.singleOrNull { method ->
        method.name == "keepScreenOn" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "Z") &&
            method.returnType == "V" &&
            AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags)
    } ?: throw PatchException("$UI_HOOKS has no keepScreenOn(Landroid/view/View;Z)V")
}

/** Every View.setKeepScreenOn call in Pinterest's own code, the extension's left out. */
internal fun BytecodePatchContext.screenHolds(): List<ScreenHold> {
    val found = mutableListOf<ScreenHold>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method -> screenHoldIndexes(method).forEach { found += ScreenHold(method, it) } }
    }
    return found
}

/** Where [method] calls View.setKeepScreenOn. */
internal fun screenHoldIndexes(method: Method): List<Int> {
    val body = method.implementation?.instructions?.toList() ?: return emptyList()
    return body.indices.filter { body[it].isKeepScreenOnCall() }
}

/** True for an invoke-virtual of View.setKeepScreenOn(boolean) on two plain registers. */
internal fun Instruction.isKeepScreenOnCall(): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL || this !is FiveRegisterInstruction || registerCount != 2) return false
    return ((this as ReferenceInstruction).reference as? MethodReference)?.toString() == KEEP_SCREEN_ON
}
