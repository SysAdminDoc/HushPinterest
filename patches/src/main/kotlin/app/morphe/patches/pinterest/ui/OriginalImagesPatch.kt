/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.freeLocalsAt
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.EXTENSION_ROOT
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Original-quality images"

/** The text Pinterest's image model writes before its first rendition, followed by the other three. */
internal const val IMAGE_DESCRIPTION = "Image(largeInternal="
internal const val IMAGE_ORIGINAL = ", original="

private const val STRING = "Ljava/lang/String;"
private const val LARGEST_PAGE_SIZE = "1200x"
private const val MAP_GET = "Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * Pinterest's collage image model holds four renditions and answers the large one first, then the
 * original, medium and small. While the switch is on, its chooser answers the original first
 * wherever Pinterest supplied one, and keeps its own order otherwise.
 *
 * Pins never reach that model. Pinterest asks its API for a set of display sizes with each pin, and
 * its closeup shows the large one. While the switch is on, the set also asks for the original, and
 * the closeup shows the original whenever one arrived.
 *
 * Idea pin pages never read the pin's own image. Two lookups pick each page's picture from that
 * page's image map, asking it for 736x, and the size set's request covers those maps too. While the
 * switch is on, both lookups answer the page's original when asked for a large size.
 *
 * Found by reading 14.38.0 (2026-10-05), the page lookups by reading 14.45.0 (2026-11-18). The model
 * keeps its description text, and the closeup builder, size set and lookups are matched by shape.
 */
@Suppress("unused")
val originalImagesPatch = bytecodePatch(
    name = PATCH,
    description = "Loads the original image for each pin and in collages, instead of the large size. Pictures look " +
        "sharper but use more data. Starts off. Turn it on in HushPinterest settings > Interface.",
) {
    category("Interface")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("originalImages")
        requireStatusMethod("imageChooser")
        requireStatusMethod("closeupImage")
        requireStatusMethod("pageImage")
        val (chooser, original) = imageChooser()
        val closeup = closeupImage()
        val pageReads = pageImageReads()
        val method = mutable(chooser)
        val answer = method.freeLocalsAt(PATCH, 0, 1).single()
        method.addInstructionsWithLabels(0, """
            invoke-static { }, $UI_HOOKS->originalImages()Z
            move-result v$answer
            if-eqz v$answer, :hush_image_order
            iget-object v$answer, p0, $original
            if-eqz v$answer, :hush_image_order
            return-object v$answer
        """, ExternalLabel("hush_image_order", method.getInstruction(0)))
        enableCapability("imageChooser")
        closeup.sizes.addInstructions(closeup.sizesReturn, "invoke-static { v${closeup.set} }, $UI_HOOKS->imageSizes(Ljava/util/Set;)V")
        closeup.builder.addInstructions(closeup.helper + 2, """
            invoke-static { p0, v${closeup.image} }, $UI_HOOKS->closeupImage(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v${closeup.image}
            check-cast v${closeup.image}, ${closeup.imageType}
        """)
        enableCapability("closeupImage")
        for (read in pageReads) {
            read.method.addInstructionsWithLabels(read.at, """
                invoke-static { v${read.images}, p1 }, $UI_HOOKS->pageImage(Ljava/util/Map;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v${read.answer}
                if-eqz v${read.answer}, :hush_page_size
                return-object v${read.answer}
            """, ExternalLabel("hush_page_size", read.method.getInstruction(read.at)))
        }
        enableCapability("pageImage")
        enableStatus("originalImages")
    }
}

/**
 * The closeup builder's first helper call and what it answers: a static method taking a pin and
 * answering a list, that gets the size bucket from its static getter and hands the pin and bucket
 * to two helpers answering one image model. The first answers the large image the closeup shows.
 */
internal fun Method.closeupHelper(): Pair<Int, MethodReference>? {
    if (!AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.size != 1 || returnType != "Ljava/util/List;") return null
    val pin = parameterTypes[0].toString()
    if (!pin.startsWith("L")) return null
    val body = instructions()
    val buckets = mutableSetOf<String>()
    val helpers = mutableListOf<Int>()
    body.forEachIndexed { at, instruction ->
        if (instruction.opcode != Opcode.INVOKE_STATIC) return@forEachIndexed
        val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: return@forEachIndexed
        if (reference.parameterTypes.isEmpty() && reference.returnType == reference.definingClass) buckets += reference.definingClass
        if (reference.parameterTypes.size == 2 && reference.parameterTypes[0].toString() == pin) helpers += at
    }
    if (helpers.size != 2) return null
    val (first, second) = helpers.map { (body[it] as ReferenceInstruction).reference as MethodReference }
    val bucket = first.parameterTypes[1].toString()
    if (bucket !in buckets || second.parameterTypes[1].toString() != bucket || first.returnType != second.returnType ||
        !first.returnType.startsWith("L") || first.returnType == pin) return null
    return helpers[0] to first
}

/** True when the instruction writes [register], alone or as the high half of a wide pair. */
private fun Instruction.writes(register: Int): Boolean {
    val target = (this as? OneRegisterInstruction)?.registerA ?: return false
    return opcode.setsRegister() && (target == register || opcode.setsWideRegister() && target + 1 == register)
}

internal class CloseupImage(
    val builder: MutableMethod, val helper: Int, val image: Int, val imageType: String,
    val sizes: MutableMethod, val sizesReturn: Int, val set: Int,
)

/** Both closeup edits, found in full before either is made. */
internal fun BytecodePatchContext.closeupImage(): CloseupImage {
    val builders = mutableListOf<Pair<Method, Pair<Int, MethodReference>>>()
    classDefForEach { owner ->
        if (owner.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        for (method in owner.methods) method.closeupHelper()?.let { builders += method to it }
    }
    val (found, call) = builders.one("$PATCH: pin closeup image builder")
    val (helper, large) = call
    val builder = mutable(found)
    val body = builder.implementation!!.instructions
    val result = body.getOrNull(helper + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || helper + 2 >= body.size) {
        throw PatchException("$PATCH: the closeup builder no longer keeps the large image it asks for")
    }
    val image = (result as OneRegisterInstruction).registerA
    val pin = builder.implementation!!.registerCount - 1
    // The hook reads the pin from p0, so the large image must be the one the helper made for that same pin.
    val handed = (body[helper] as FiveRegisterInstruction).registerC == pin
    val pinWritten = body.take(helper + 2).any { it.writes(pin) }
    if (!handed || image == pin || image > 15 || pin > 15 || pinWritten || (body[helper + 2] as BuilderInstruction).location.labels.isNotEmpty()) {
        throw PatchException("$PATCH: the closeup builder's pin and large image no longer fit the hook")
    }
    val sizeSet = classDefBy(large.parameterTypes[1].toString()).methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
            method.returnType == "Ljava/util/HashSet;" && method.implementation != null
    }.one("$PATCH: pin image size set")
    val sizes = mutable(sizeSet)
    val returns = sizes.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
    val exit = returns.singleOrNull() ?: throw PatchException("$PATCH: the pin image size set has ${returns.size} returns, expected 1")
    val set = (exit.value as OneRegisterInstruction).registerA
    if (set > 15 || (exit.value as BuilderInstruction).location.labels.isNotEmpty()) {
        throw PatchException("$PATCH: the pin image size set no longer returns from one straight path")
    }
    // The hook adds to the set it's handed, so the set must be a new one each call, never one shared between callers.
    val start = sizes.implementation!!.instructions.first()
    val fresh = start.opcode == Opcode.NEW_INSTANCE && (start as OneRegisterInstruction).registerA == set &&
        (start as ReferenceInstruction).reference.toString() == "Ljava/util/HashSet;" &&
        sizes.implementation!!.instructions.drop(1).none { it.writes(set) }
    if (!fresh) throw PatchException("$PATCH: the pin image size set no longer answers a new set each call")
    return CloseupImage(builder, helper, image, large.returnType, sizes, exit.index, set)
}

/**
 * Where this method reads an idea pin page's image map by the size it was asked for: a static
 * method taking the page and two size names, the largest size named in its body, answering an
 * address. Reads after the size parameter is overwritten don't count, they use some other key.
 */
internal fun Method.pageImageReads(): List<Int> {
    val registers = implementation?.registerCount ?: return emptyList()
    val parameters = parameters()
    if (!AccessFlags.STATIC.isSet(accessFlags) || returnType != STRING || parameters.size != 3 ||
        !parameters[0].startsWith("L") || parameters.drop(1) != listOf(STRING, STRING) || LARGEST_PAGE_SIZE !in strings()) return emptyList()
    val size = registers - 2
    return instructions().takeWhile { !it.writes(size) }.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_INTERFACE && (instruction as FiveRegisterInstruction).registerCount == 2 &&
            instruction.registerD == size && (instruction as ReferenceInstruction).reference.toString() == MAP_GET
    }.map { it.index }
}

internal class PageImageRead(val method: MutableMethod, val at: Int, val images: Int, val answer: Int)

/** Every read of both page image lookups, last first so an edit leaves the others' places alone. */
internal fun BytecodePatchContext.pageImageReads(): List<PageImageRead> {
    val lookups = methodsWithString(LARGEST_PAGE_SIZE).filter { !it.definingClass.startsWith(EXTENSION_ROOT) && it.pageImageReads().isNotEmpty() }.toList()
    if (lookups.size != 2) throw PatchException("$PATCH: found ${lookups.size} idea pin page image lookups, expected 2")
    return lookups.flatMap { lookup ->
        val reads = lookup.pageImageReads()
        val method = mutable(lookup)
        val body = method.implementation!!.instructions
        reads.reversed().map { at ->
            if ((body[at] as BuilderInstruction).location.labels.isNotEmpty()) {
                throw PatchException("$PATCH: code jumps straight to the page image read in ${method.definingClass}")
            }
            PageImageRead(method, at, (body[at] as FiveRegisterInstruction).registerC, method.freeLocalsAt(PATCH, at, 1).single())
        }
    }
}

/** Each label the image model's description writes, with the field it writes after it. */
internal fun Method.imageLabels(): Map<String, FieldReference> {
    val labels = linkedMapOf<String, FieldReference>()
    var label: String? = null
    for (instruction in instructions()) {
        val reference = (instruction as? ReferenceInstruction)?.reference
        if (reference is StringReference) label = reference.string
        if (instruction.opcode == Opcode.IGET_OBJECT && reference is FieldReference && label != null) {
            labels[label] = reference
            label = null
        }
    }
    return labels
}

/**
 * The image model's rendition chooser and its original rendition: the model's one instance method,
 * taking nothing and answering a rendition, that reads all four and starts with the large one.
 */
internal fun BytecodePatchContext.imageChooser(): Pair<Method, FieldReference> {
    val description = methodsWithString(IMAGE_DESCRIPTION).filter { it.name == "toString" }.one("$PATCH: image model")
    val labels = description.imageLabels()
    val large = labels[IMAGE_DESCRIPTION]
    val original = labels[IMAGE_ORIGINAL]
    val renditions = labels.values.toSet()
    if (large == null || original == null || renditions.size != 4 || renditions.any { it.type != large.type || it.definingClass != description.definingClass }) {
        throw PatchException("$PATCH: Pinterest's image model no longer describes four renditions of one kind")
    }
    val chooser = classDefBy(description.definingClass).methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() && method.returnType == large.type &&
            method.fields().toSet().containsAll(renditions)
    }.one("$PATCH: image rendition chooser")
    val first = chooser.instructions().firstOrNull()
    if (first?.opcode != Opcode.IGET_OBJECT || (first as ReferenceInstruction).reference != large) {
        throw PatchException("$PATCH: the image chooser no longer starts with the large rendition")
    }
    return chooser to original
}
