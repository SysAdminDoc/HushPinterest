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
import app.morphe.patches.pinterest.misc.extension.patchLog
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.EXTENSION_ROOT
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.namedRegisters
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide closeup buttons"

internal const val CLOSEUP_BUTTONS = "$EXTENSION_PACKAGE/ui/CloseupButtons;"
internal const val CLOSEUP_BACK_HOOK = "$CLOSEUP_BUTTONS->backArrow(Landroid/view/View;)V"
internal const val CLOSEUP_LENS_HOOK = "$CLOSEUP_BUTTONS->lens(Landroid/view/View;)V"
internal const val CLOSEUP_VISIBILITY_HOOK = "$CLOSEUP_BUTTONS->visibility(Landroid/view/View;I)I"
internal const val CLOSEUP_SEARCH_LABEL_HOOK = "$CLOSEUP_BUTTONS->searchLabel(Z)Z"

/** The R fields naming each button's id. This build reads ids from R rather than inlining them. */
internal const val CLOSEUP_BACK_ID = "closeup_back_button"
internal const val CLOSEUP_LENS_ID = "flashlight_search_button"

/** A pin with several pictures shows them in a carousel, which has a lens of its own under this id. */
internal const val CLOSEUP_CAROUSEL_LENS_ID = "carousel_flashlight_button"

/** The closeup state's toString names the lens label's flag right before reading it. */
internal const val SEARCH_LABEL_FLAG = ", isVisualActionAnimated="

/** The R string the lens wears as its label. */
internal const val SEARCH_IMAGE_TEXT = "search_image"

private const val FIND_VIEW = "Landroid/view/View;->findViewById(I)Landroid/view/View;"
private const val GET_CLASS = "Ljava/lang/Object;->getClass()Ljava/lang/Class;"
private val MOVE_OBJECTS = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val ANDROID_VIEWS = listOf("Landroid/view/", "Landroid/widget/")

/** Where a button is handed to the extension: the hook goes in at [index], passing [register], a [view]. */
internal class ButtonSite(val method: Method, val index: Int, val register: Int, val view: String, val found: Boolean)

internal fun Instruction.readsId(id: String): Boolean {
    if (opcode != Opcode.SGET) return false
    val field = (this as ReferenceInstruction).reference as? FieldReference ?: return false
    return field.name == id && field.type == "I"
}

/**
 * Every place [id]'s button is found or built, from the methods that read the R field of that name.
 * Found: the id read, `findViewById` with it, and `move-result-object`. The hook goes right after,
 * and the button's class is the check-cast that follows, after Kotlin's getClass null check if
 * there is one, made on the button or on the copy a `move-object` makes of it (the picture
 * carousel's lens). Built: a method answering one of the APK's own views through a single
 * `return-object`, where that view takes the id (see [tiedToId]). The hook goes right before it,
 * and the class is the method's return type. Constructed: see [constructedAt]. Anything else
 * reading the id, such as a table pairing ids with names, isn't a button.
 */
internal fun BytecodePatchContext.buttonSites(id: String): List<ButtonSite> {
    val sites = mutableListOf<ButtonSite>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        owner.methods.forEach { method -> sites += buttonSites(method, id) }
    }
    return sites
}

private fun BytecodePatchContext.buttonSites(method: Method, id: String): List<ButtonSite> {
    val body = method.instructions()
    val reads = body.indices.filter { body[it].readsId(id) }
    if (reads.isEmpty()) return emptyList()
    val found = reads.mapNotNull { foundAt(method, body, it, id) }
    if (found.isNotEmpty()) return found
    val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.size == 1 && classDefByOrNull(method.returnType) != null && isFrameworkView(method.returnType)) {
        val at = returns.single()
        val button = (body[at] as OneRegisterInstruction).registerA
        if (tiedToId(body, reads, button)) return listOf(ButtonSite(method, at, button, method.returnType, false))
    }
    return constructedAt(method, body, reads)
}

/**
 * Built in place: one of the APK's own views constructed from an object whose own constructor took
 * the id, in a method that goes on to do more. 14.40.0 builds the second closeup's lens that way,
 * where 14.39.0 called a small method answering it. The hook goes right after the view's
 * constructor, the only place the new view is sure to be in that register, so these sites are
 * checked like a found button's.
 */
private fun BytecodePatchContext.constructedAt(method: Method, body: List<Instruction>, reads: List<Int>): List<ButtonSite> {
    val ids = reads.map { (body[it] as OneRegisterInstruction).registerA }.toSet()
    val holders = mutableSetOf<Int>()
    val sites = mutableListOf<ButtonSite>()
    for (index in reads.min() + 1 until body.size) {
        val call = body[index]
        if (call !is FiveRegisterInstruction && call !is RegisterRangeInstruction) continue
        val target = (call as ReferenceInstruction).reference as? MethodReference ?: continue
        val registers = call.namedRegisters()
        if (target.name != "<init>" || registers.isEmpty()) continue
        val passed = registers.drop(1)
        if (passed.any { it in ids }) {
            holders += registers.first()
            continue
        }
        if (passed.none { it in holders }) continue
        val type = target.definingClass
        if (classDefByOrNull(type) == null || !isFrameworkView(type)) continue
        sites += ButtonSite(method, index + 1, registers.first(), type, true)
    }
    return sites
}

/**
 * True when the view answered in [button] takes the id read at [reads]: `setId` is called on it
 * with the id, or its constructor takes an object whose own constructor took the id. A method
 * that answers some other view, the parent it looked the button up in for one, doesn't qualify.
 */
internal fun tiedToId(body: List<Instruction>, reads: List<Int>, button: Int): Boolean {
    val ids = reads.map { (body[it] as OneRegisterInstruction).registerA }.toSet()
    val holders = mutableSetOf<Int>()
    for (index in reads.min() + 1 until body.size) {
        val call = body[index]
        if (call !is FiveRegisterInstruction && call !is RegisterRangeInstruction) continue
        val method = (call as ReferenceInstruction).reference as? MethodReference ?: continue
        val registers = call.namedRegisters()
        if (registers.isEmpty()) continue
        val passed = registers.drop(1)
        if (method.name == "setId" && method.parameterTypes.map { it.toString() } == listOf("I") &&
            registers.first() == button && passed.any { it in ids }) return true
        if (method.name != "<init>") continue
        if (passed.any { it in ids }) holders += registers.first()
        if (registers.first() == button && passed.any { it in holders }) return true
    }
    return false
}

private fun foundAt(method: Method, body: List<Instruction>, at: Int, id: String): ButtonSite? {
    val find = body.getOrNull(at + 1) as? FiveRegisterInstruction ?: return null
    if (find.opcode != Opcode.INVOKE_VIRTUAL || (find as ReferenceInstruction).reference.toString() != FIND_VIEW ||
        find.registerCount != 2 || find.registerD != (body[at] as OneRegisterInstruction).registerA) return null
    val kept = body.getOrNull(at + 2)
    if (kept?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val button = (kept as OneRegisterInstruction).registerA
    var next = at + 3
    val check = body.getOrNull(next)
    if (check is FiveRegisterInstruction && check.opcode == Opcode.INVOKE_VIRTUAL && check.registerCount == 1 &&
        check.registerC == button && (check as ReferenceInstruction).reference.toString() == GET_CLASS) next++
    var cast = body.getOrNull(next)
    var copy = button
    if (cast?.opcode in MOVE_OBJECTS && (cast as TwoRegisterInstruction).registerB == button) {
        copy = cast.registerA
        cast = body.getOrNull(next + 1)
    }
    if (cast?.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != copy) {
        throw PatchException("$PATCH: ${method.definingClass}->${method.name} finds the $id button without casting it to its class")
    }
    return ButtonSite(method, at + 3, button, ((cast as ReferenceInstruction).reference as TypeReference).type, true)
}

/** A read of the lens label's flag into [register], with the hook going in right after [index]. */
internal class LabelRead(val method: Method, val index: Int, val register: Int)

/**
 * Where the lens decides to wear its Search image label and play the animation that shows it:
 * every read of the closeup state's label flag in the one class that builds the lens and picks
 * the search_image string. The flag is the first boolean the state's toString reads after naming
 * it. Pinterest sets it on the first two eligible closeups of a day and clears it after, so the
 * hook only ever makes a closeup look like the third. Throws when any part is missing or there's
 * more than one candidate.
 */
internal fun BytecodePatchContext.labelReads(): List<LabelRead> {
    val described = methodsWithString(SEARCH_LABEL_FLAG).filter { it.name == "toString" && it.parameterTypes.isEmpty() }
    val toString = described.singleOrNull()
        ?: throw PatchException("${described.size} closeup states describe their lens label flag")
    val body = toString.instructions()
    val named = body.indexOfFirst { (it as? ReferenceInstruction)?.reference.let { text -> text is StringReference && text.string == SEARCH_LABEL_FLAG } }
    val flag = body.drop(named + 1).firstOrNull { it.opcode == Opcode.IGET_BOOLEAN }
        ?.let { (it as ReferenceInstruction).reference as FieldReference }
        ?.takeIf { it.definingClass == toString.definingClass && it.type == "Z" }
        ?: throw PatchException("the closeup state ${toString.definingClass} names its lens label flag without reading its own boolean")
    fun Instruction.readsFlag() = opcode == Opcode.IGET_BOOLEAN && "${(this as ReferenceInstruction).reference}" == "$flag"
    val builders = mutableListOf<ClassDef>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        val code = owner.methods.map { it.instructions() }
        if (code.any { body -> body.any { it.readsId(CLOSEUP_LENS_ID) } } && code.any { body -> body.any { it.readsId(SEARCH_IMAGE_TEXT) } } &&
            code.any { body -> body.any { it.readsFlag() } }) builders += owner
    }
    val builder = builders.singleOrNull() ?: throw PatchException("${builders.size} lens builders read its label flag")
    return builder.methods.flatMap { method ->
        method.instructions().withIndex().filter { (_, read) -> read.readsFlag() }
            .map { (at, read) -> LabelRead(method, at, (read as TwoRegisterInstruction).registerA) }
    }
}

/** True when the class's first ancestor outside the APK is one of Android's view classes. */
private fun BytecodePatchContext.isFrameworkView(type: String) =
    superclassChain(type).drop(1).lastOrNull()?.let { outside -> ANDROID_VIEWS.any { outside.startsWith(it) } } == true

@Suppress("unused")
val hideCloseupButtonsPatch = bytecodePatch(
    name = PATCH,
    description = "Lets you hide the back arrow and the visual search (lens) button that float over a pin you open, " +
        "each with its own switch, so they stop covering the picture. A third switch keeps the lens but drops the " +
        "Search image label Pinterest puts on it for your first pins each day. Your phone's back gesture still " +
        "leaves the pin, and Search image in the pin menu still works. All three start off. Turn them on in " +
        "HushPinterest settings > Interface.",
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("hideCloseupButtons")
        requireStatusMethod("closeupButtons")
        requireStatusMethod("closeupSearchLabel")
        val backs = buttonSites(CLOSEUP_BACK_ID)
        val single = buttonSites(CLOSEUP_LENS_ID)
        if (backs.isEmpty()) throw PatchException("$PATCH: nothing finds the closeup's back arrow")
        if (single.isEmpty()) throw PatchException("$PATCH: nothing builds the closeup's visual search button")
        // Only pins with several pictures have the carousel's lens, so a build without it still
        // hides the lens on every other pin.
        val carousel = buttonSites(CLOSEUP_CAROUSEL_LENS_ID)
        if (carousel.isEmpty()) patchLog.warning("$PATCH: nothing finds the picture carousel's visual search button")
        val lenses = single + carousel
        // The label has its own switch, so a build where its flag can't be pinned down still gets
        // both buttons, just without that third switch.
        val labels = try {
            labelReads()
        } catch (missing: PatchException) {
            patchLog.warning("$PATCH: ${missing.message}. Hide Search image label isn't in this build.")
            emptyList()
        }

        // Everything is found and checked before the first change.
        val views = (backs + lenses).map { it.view }.distinct().map { type ->
            if (!isFrameworkView(type)) throw PatchException("$PATCH: the button class $type is not an Android view")
            mutableClassDefBy(type).also { requireOverride(it, "setVisibility", listOf("I")) }
        }
        val hooked = backs.map { it to CLOSEUP_BACK_HOOK } + lenses.map { it to CLOSEUP_LENS_HOOK }
        val edits = hooked.map { (site, hook) -> Triple(mutable(site.method), site, hook) }
        for ((method, site, _) in edits) {
            // A found button is only in its register on the path from findViewById, and a constructed
            // one on the path from its constructor.
            val at = method.implementation!!.instructions[site.index] as BuilderInstruction
            if (site.found && at.location.labels.isNotEmpty()) {
                throw PatchException("$PATCH: ${site.method.definingClass}->${site.method.name} branches into the found button")
            }
        }

        // Within one method the later site goes in first, so the earlier index still holds. A button
        // goes in at the label of the instruction it precedes; a label read's answer goes in after the
        // read, off any label there, so only the path from the read is filtered.
        val inserts = mutableListOf<Pair<Int, () -> Unit>>()
        for ((method, site, hook) in edits) inserts += site.index to {
            method.addInstructionsAtControlFlowLabel(site.index, "invoke-static/range { v${site.register} .. v${site.register} }, $hook")
        }
        for (read in labels) {
            val method = mutable(read.method)
            inserts += read.index + 1 to {
                method.addInstructions(read.index + 1, """
                    invoke-static { v${read.register} }, $CLOSEUP_SEARCH_LABEL_HOOK
                    move-result v${read.register}
                """)
            }
        }
        for ((_, insert) in inserts.sortedByDescending { it.first }) insert()
        val prefix = """
            invoke-static { p0, p1 }, $CLOSEUP_VISIBILITY_HOOK
            move-result p1
        """
        for (view in views) {
            val own = view.methods.singleOrNull { it.name == "setVisibility" && it.parameters() == listOf("I") }
            if (own != null) own.addInstructions(0, prefix) else addOverride(view, "setVisibility", listOf("I"), prefix)
        }
        enableCapability("closeupButtons")
        if (labels.isNotEmpty()) enableCapability("closeupSearchLabel")
        enableStatus("hideCloseupButtons")
    }
}
