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
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Every class reading the closeup back arrow's or lens's id, the classes those buttons are, and their
 * ancestors, in each declared APK, with the closeup state that describes the lens's label flag.
 */
@Category(FixtureTests::class)
class CloseupButtonsFixtureTest {
    private class Build(val name: String, val readers: List<ClassDef>, val others: List<ClassDef>, val states: List<ClassDef>) {
        val classes get() = readers + others + states
    }

    private val ids = setOf(CLOSEUP_BACK_ID, CLOSEUP_LENS_ID, CLOSEUP_CAROUSEL_LENS_ID)
    private val fragments = listOf(
        "Lcom/pinterest/feature/pin/closeup/view/OrganicPinCloseupFragment;",
        "Lcom/pinterest/feature/pin/closeup/view/PromotedPinCloseupFragment;",
    )

    @Test
    fun `each declared original APK hands both buttons over and holds their visibility`() {
        for (build in Fixtures.declaredBuilds().map(::read)) {
            val context = PatchContexts.of(ExtensionDex.classes() + build.classes)
            hideCloseupButtonsPatch.execute(context)
            for (flag in listOf("hideCloseupButtons", "closeupButtons")) assertEquals("${build.name}: $flag", 1, flag(context, flag))

            val hooked = mapOf(CLOSEUP_BACK_HOOK to mutableListOf<String>(), CLOSEUP_LENS_HOOK to mutableListOf<String>())
            val views = mutableSetOf<String>()
            var relabeled = 0
            for (method in build.readers.flatMap { it.methods }.filter { it.readsAnId() }) {
                val original = method.implementation!!.instructions.toList()
                val body = patched(context, method)
                val hooks = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() in hooked.keys }
                assertEquals("${build.name}: $method keeps every original instruction", original.map { it.opcode },
                    body.filterIndexed { at, _ -> at !in hooks }.map { it.opcode })
                for (at in hooks) {
                    val hook = (body[at] as ReferenceInstruction).reference.toString()
                    hooked.getValue(hook) += method.definingClass
                    val register = (body[at] as RegisterRangeInstruction).let { assertEquals(1, it.registerCount); it.startRegister }
                    val before = body[at - 1]
                    if (before.opcode == Opcode.MOVE_RESULT_OBJECT) {
                        assertEquals("${build.name}: $method hands over the view findViewById found", (before as OneRegisterInstruction).registerA, register)
                        assertEquals(FIND_VIEW_CALL, (body[at - 2] as ReferenceInstruction).reference.toString())
                        // The carousel copies the found view into another register and casts the copy.
                        val copy = (body[at + 1] as? TwoRegisterInstruction)?.takeIf { it.opcode == Opcode.MOVE_OBJECT && it.registerB == register }
                        val cast = body.drop(at + 1).first { it.opcode == Opcode.CHECK_CAST }
                        assertEquals(copy?.registerA ?: register, (cast as OneRegisterInstruction).registerA)
                        views += ((cast as ReferenceInstruction).reference as TypeReference).type
                    } else if (((before as? ReferenceInstruction)?.reference as? MethodReference)?.name == "<init>") {
                        // Built in place (14.40.0's second closeup): handed over right after its constructor.
                        assertEquals("${build.name}: $method hands over the view it just built", before.namedRegisters().first(), register)
                        views += ((before as ReferenceInstruction).reference as MethodReference).definingClass
                    } else {
                        val answer = body[at + 1]
                        assertEquals("${build.name}: $method hands over the view it answers", Opcode.RETURN_OBJECT, answer.opcode)
                        assertEquals(register, (answer as OneRegisterInstruction).registerA)
                        views += method.returnType
                        // A branch that jumped to the return now jumps to the hook, or that path would skip it.
                        val kept = body.indices.filter { it !in hooks }
                        val jumps = branchesTo(original, kept.indexOf(at + 1)).map { kept[it] }
                        assertEquals("${build.name}: $method's branches to its return", jumps, branchesTo(body, at))
                        assertEquals("${build.name}: $method's return keeps no branch", emptyList<Int>(), branchesTo(body, at + 1))
                        relabeled += jumps.size
                    }
                }
            }
            val backs = hooked.getValue(CLOSEUP_BACK_HOOK)
            assertEquals("${build.name}: the back arrow's finds, $backs", 4, backs.size)
            assertTrue("${build.name}: both closeup fragments hand the arrow over, $backs", backs.containsAll(fragments))
            val lenses = hooked.getValue(CLOSEUP_LENS_HOOK)
            assertEquals("${build.name}: the lens's builders and the carousel's find, $lenses", 3, lenses.size)
            assertTrue("${build.name}: a pin with several pictures hands its lens over, $lenses", CAROUSEL in lenses)
            assertTrue("${build.name}: a builder whose return is a branch target", relabeled > 0)

            assertEquals("${build.name}: the button classes", 2, views.size)
            assertTrue(build.name, ICON_BUTTON in views)
            for (type in views) {
                val view = context.mutableClassDefBy(type)
                val code = view.methods.single { it.name == "setVisibility" }.implementation!!.instructions.toList()
                assertEquals("${build.name}: $type", listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.INVOKE_SUPER_RANGE,
                    Opcode.RETURN_VOID), code.map { it.opcode })
                assertEquals(build.name, CLOSEUP_VISIBILITY_HOOK, (code[0] as ReferenceInstruction).reference.toString())
                assertEquals("${build.name}: the view and what Pinterest asked for", listOf(0, 1), registers(code[0]))
                assertEquals("${build.name}: the answer replaces the request", 1, (code[1] as OneRegisterInstruction).registerA)
                val inherited = (code[2] as ReferenceInstruction).reference as MethodReference
                assertEquals(build.name, "${view.superclass}->setVisibility(I)V",
                    "${inherited.definingClass}->${inherited.name}(${inherited.parameterTypes.joinToString("")})${inherited.returnType}")
            }
        }
    }

    @Test
    fun `a missing button, an uncast find, a final setVisibility or a missing status stub refuses before any change`() {
        val build = read(Fixtures.declaredBuilds().last())
        val extension = ExtensionDex.classes()
        val backReaders = build.readers.filter { owner -> owner.methods.any { it.reads(CLOSEUP_BACK_ID) } }
        val lensReaders = build.readers.filter { owner -> owner.methods.any { it.reads(CLOSEUP_LENS_ID) } }
        val organic = build.readers.single { it.type == fragments[0] }
        // The cast after the find becomes no-ops of the same size, so nothing says what the found view is.
        val uncast = replace(organic, { it.reads(CLOSEUP_BACK_ID) }) { instructions ->
            val find = instructions.indices.first { (instructions[it] as? ReferenceInstruction)?.reference?.toString() == FIND_VIEW_CALL &&
                instructions[it - 1].reads(CLOSEUP_BACK_ID) }
            val cast = (find + 2 until instructions.size).first { instructions[it].opcode == Opcode.CHECK_CAST }
            instructions.subList(0, cast) + List(instructions[cast].codeUnits) { ImmutableInstruction10x(Opcode.NOP) } +
                instructions.subList(cast + 1, instructions.size)
        }
        val ancestor = build.others.first { it.type == build.others.single { view -> view.type == ICON_BUTTON }.superclass }
        val finalVisibility = ImmutableClassDef(ancestor.type, ancestor.accessFlags, ancestor.superclass, ancestor.interfaces,
            ancestor.sourceFile, ancestor.annotations, ancestor.fields, ancestor.methods + ImmutableMethod(ancestor.type, "setVisibility",
                listOf(ImmutableMethodParameter("I", null, null)), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)))
        fun with(vararg swaps: ClassDef) = extension + build.classes.map { owner -> swaps.firstOrNull { it.type == owner.type } ?: owner }
        val cases = linkedMapOf(
            "no back arrow" to (extension + build.classes - backReaders.toSet() to "nothing finds the closeup's back arrow"),
            "no lens" to (extension + build.classes - lensReaders.toSet() to "nothing builds the closeup's visual search button"),
            "find without a cast" to (with(uncast) to "without casting it to its class"),
            "final setVisibility above the icon button" to (with(finalVisibility) to "cannot override final"),
            "missing status stub" to (build.classes + extension.map { owner -> if (owner.type != SETTINGS_STATUS) owner else
                ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile,
                    owner.annotations, owner.fields, owner.methods.filterNot { it.name == "closeupButtons" }) }
                to "no boolean method closeupButtons()"),
            "missing label status stub" to (build.classes + extension.map { owner -> if (owner.type != SETTINGS_STATUS) owner else
                ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile,
                    owner.annotations, owner.fields, owner.methods.filterNot { it.name == "closeupSearchLabel" }) }
                to "no boolean method closeupSearchLabel()"),
        )
        for ((reason, case) in cases) {
            val (input, message) = case
            val context = PatchContexts.of(input)
            val before = snapshot(context, input)
            val refusal = assertThrows(reason, PatchException::class.java) { hideCloseupButtonsPatch.execute(context) }
            assertTrue("$reason refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(message))
            assertArrayEquals("$reason left retained edits", before, snapshot(context, input))
            assertEquals("$reason left the family flag set", 0, flag(context, "hideCloseupButtons"))
        }
    }

    @Test
    fun `a builder that reads the lens id but never gives its view the id is passed over`() {
        val build = read(Fixtures.declaredBuilds().last())
        val builder = build.readers.single { it.type == LENS_BUILDER }
        val untied = replace(builder, { it.reads(CLOSEUP_LENS_ID) }) { instructions ->
            val setId = instructions.indices.single { (instructions[it] as? ReferenceInstruction)?.reference?.toString() == SET_ID_CALL }
            instructions.subList(0, setId) + List(instructions[setId].codeUnits) { ImmutableInstruction10x(Opcode.NOP) } +
                instructions.subList(setId + 1, instructions.size)
        }
        val context = PatchContexts.of(ExtensionDex.classes() + build.classes.map { if (it.type == LENS_BUILDER) untied else it })
        hideCloseupButtonsPatch.execute(context)
        for (method in untied.methods.filter { it.reads(CLOSEUP_LENS_ID) }) {
            assertEquals("$method is left as it was", method.implementation!!.instructions.map { it.opcode },
                patched(context, method).map { it.opcode })
        }
        assertEquals("the other builder still hands its lens over", 1, flag(context, "closeupButtons"))
    }

    @Test
    fun `a build without the carousel's lens still hides the lens on every other pin`() {
        val build = read(Fixtures.declaredBuilds().last())
        val context = PatchContexts.of(ExtensionDex.classes() + build.classes.filterNot { it.type == CAROUSEL })
        val warnings = PatchLogCapture.warnings { hideCloseupButtonsPatch.execute(context) }
        assertEquals(listOf("Hide closeup buttons: nothing finds the picture carousel's visual search button"), warnings)
        assertEquals(1, flag(context, "closeupButtons"))
        assertEquals(1, flag(context, "closeupSearchLabel"))
    }

    @Test
    fun `each declared original APK answers every read of the lens's label flag through its own switch`() {
        for (build in Fixtures.declaredBuilds().map(::read)) {
            val context = PatchContexts.of(ExtensionDex.classes() + build.classes)
            hideCloseupButtonsPatch.execute(context)
            assertEquals("${build.name}: closeupSearchLabel", 1, flag(context, "closeupSearchLabel"))
            val labelFlag = labelFlag(build.states.single())
            var sites = 0
            for (method in build.readers.single { it.type == LENS_BUILDER }.methods) {
                val original = method.implementation?.instructions?.toList() ?: continue
                val reads = original.indices.filter { original[it].readsFlag(labelFlag) }
                val body = patched(context, method)
                val hooks = body.indices.filter { (body[it] as? ReferenceInstruction)?.reference?.toString() == CLOSEUP_SEARCH_LABEL_HOOK }
                assertEquals("${build.name}: $method's label flag reads", reads.size, hooks.size)
                if (reads.isEmpty()) continue
                for ((read, at) in reads.zip(hooks)) {
                    val register = (original[read] as TwoRegisterInstruction).registerA
                    assertTrue("${build.name}: $method asks right after the read", body[at - 1].readsFlag(labelFlag))
                    assertEquals("${build.name}: $method passes the flag it read", listOf(register), registers(body[at]))
                    assertEquals(Opcode.MOVE_RESULT, body[at + 1].opcode)
                    assertEquals("${build.name}: the answer replaces the flag", register, (body[at + 1] as OneRegisterInstruction).registerA)
                    assertEquals("${build.name}: nothing jumps into the hook", emptyList<Int>(), branchesTo(body, at))
                    assertEquals("${build.name}: branches to the next instruction still land on it",
                        branchesTo(original, read + 1).size, branchesTo(body, at + 2).size)
                }
                val added = hooks.flatMap { listOf(it, it + 1) }.toSet()
                assertEquals("${build.name}: $method keeps every original instruction", original.map { it.opcode },
                    body.filterIndexed { at, _ -> at !in added }.map { it.opcode })
                sites += hooks.size
            }
            assertEquals("${build.name}: where the lens wears its label and where it plays the label's animation", 2, sites)
        }
    }

    @Test
    fun `without the closeup state or the lens builder's label text, the buttons still work and the label keeps no switch`() {
        val build = read(Fixtures.declaredBuilds().last())
        val builder = build.readers.single { it.type == LENS_BUILDER }
        val labelFlag = labelFlag(build.states.single())
        val unlabeled = replace(builder, { method -> method.implementation?.instructions?.any { it.reads(SEARCH_IMAGE_TEXT) } == true }) { instructions ->
            instructions.flatMap { if (it.reads(SEARCH_IMAGE_TEXT)) List(it.codeUnits) { ImmutableInstruction10x(Opcode.NOP) } else listOf(it) }
        }
        val cases = linkedMapOf(
            "no closeup state" to (build.classes - build.states.toSet() to "0 closeup states describe their lens label flag"),
            "no search_image text" to (build.classes.map { if (it.type == LENS_BUILDER) unlabeled else it } to "0 lens builders read its label flag"),
        )
        for ((reason, case) in cases) {
            val (input, message) = case
            val context = PatchContexts.of(ExtensionDex.classes() + input)
            val warnings = PatchLogCapture.warnings { hideCloseupButtonsPatch.execute(context) }
            assertEquals("$reason: $warnings", 1, warnings.size)
            assertTrue("$reason warned for another reason: $warnings", warnings.single().contains(message))
            assertTrue("$reason: $warnings", warnings.single().endsWith("Hide Search image label isn't in this build."))
            assertEquals("$reason took the buttons out with it", 1, flag(context, "closeupButtons"))
            assertEquals("$reason left the label flag set", 0, flag(context, "closeupSearchLabel"))
            for (method in builder.methods.filter { method -> method.implementation?.instructions?.any { it.readsFlag(labelFlag) } == true }) {
                assertTrue("$reason left a hook in $method", patched(context, method).none {
                    (it as? ReferenceInstruction)?.reference?.toString() == CLOSEUP_SEARCH_LABEL_HOOK })
            }
        }
    }

    /** The boolean [state]'s toString reads right after naming the label flag. */
    private fun labelFlag(state: ClassDef): String {
        val description = state.methods.single { it.name == "toString" }.implementation!!.instructions.toList()
        val named = description.indexOfFirst { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SEARCH_LABEL_FLAG }
        assertTrue("${state.type} names the label flag", named >= 0)
        val read = description.drop(named + 1).first { it.opcode == Opcode.IGET_BOOLEAN }
        return ((read as ReferenceInstruction).reference as FieldReference).also { assertEquals(state.type, it.definingClass) }.toString()
    }

    private fun Instruction.readsFlag(field: String) = opcode == Opcode.IGET_BOOLEAN && (this as ReferenceInstruction).reference.toString() == field

    private fun Instruction.reads(id: String) = opcode == Opcode.SGET &&
        ((this as ReferenceInstruction).reference as FieldReference).let { it.name == id && it.type == "I" }

    private fun Method.reads(id: String) = implementation?.instructions?.any { it.reads(id) } == true
    private fun Method.readsAnId() = ids.any { reads(it) }

    private fun flag(context: BytecodePatchContext, name: String) =
        (context.mutableClassDefBy(SETTINGS_STATUS).methods.single { it.name == name }.implementation!!.instructions.first()
            as NarrowLiteralInstruction).narrowLiteral

    private fun snapshot(context: BytecodePatchContext, classes: List<ClassDef>): ByteArray {
        val pool = DexPool(Opcodes.getDefault())
        classes.forEach { pool.internClass(context.mutableClassDefBy(it.type)) }
        val store = MemoryDataStore()
        try {
            pool.writeTo(store)
            return store.data
        } finally {
            store.close()
        }
    }

    private fun patched(context: BytecodePatchContext, method: Method) =
        context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }.implementation!!.instructions.toList()

    /** The indexes of the branches in [body] that land on the instruction at [index]. */
    private fun branchesTo(body: List<Instruction>, index: Int): List<Int> {
        val address = body.runningFold(0) { at, instruction -> at + instruction.codeUnits }
        return body.indices.filter { (body[it] as? OffsetInstruction)?.let { branch -> address[it] + branch.codeOffset == address[index] } == true }
    }

    private fun registers(instruction: Instruction): List<Int> {
        val call = instruction as FiveRegisterInstruction
        return listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG).take(call.registerCount)
    }

    /** A copy of [owner] whose methods matching [which] carry [change]d instructions. */
    private fun replace(owner: ClassDef, which: (Method) -> Boolean, change: (List<Instruction>) -> List<Instruction>) =
        ImmutableClassDef(owner.type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile,
            owner.annotations, owner.fields, owner.methods.map { method ->
                val instructions = method.implementation?.instructions?.toList()
                if (!which(method) || instructions == null) method else ImmutableMethod(method.definingClass, method.name, method.parameters,
                    method.returnType, method.accessFlags, method.annotations, method.hiddenApiRestrictions,
                    ImmutableMethodImplementation(method.implementation!!.registerCount, change(instructions),
                        method.implementation!!.tryBlocks, null))
            })

    /**
     * The classes reading either id, and the classes their methods return or cast to with the
     * APK's own ancestors of each, which covers both buttons' classes.
     */
    private fun read(build: File): Build {
        val parents = mutableMapOf<String, String?>()
        val readers = linkedMapOf<String, ClassDef>()
        val states = linkedMapOf<String, ClassDef>()
        val wanted = mutableSetOf<String>()
        FixtureDex.forEach(build) { dex ->
            for (classDef in dex.classes) {
                parents.putIfAbsent(classDef.type, classDef.superclass)
                if (classDef.methods.any { method -> method.name == "toString" && method.implementation?.instructions?.any {
                        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == SEARCH_LABEL_FLAG } == true })
                    states.putIfAbsent(classDef.type, ImmutableClassDef.of(classDef))
                val reading = classDef.methods.filter { it.readsAnId() }
                if (reading.isEmpty() || classDef.type in readers) continue
                readers[classDef.type] = ImmutableClassDef.of(classDef)
                for (method in reading) {
                    wanted += method.returnType
                    method.implementation!!.instructions.filter { it.opcode == Opcode.CHECK_CAST }
                        .forEach { wanted += ((it as ReferenceInstruction).reference as TypeReference).type }
                }
            }
        }
        val types = wanted.filter { it in parents }.flatMap { type -> generateSequence(type) { parents[it] }.filter { it in parents }.toList() }
            .toSet() - readers.keys - states.keys
        val others = FixtureDex.classes(build, types)
        return Build(build.name, readers.values.toList(), others.values.toList(), states.values.filterNot { it.type in readers }.toList())
    }

    private companion object {
        const val FIND_VIEW_CALL = "Landroid/view/View;->findViewById(I)Landroid/view/View;"
        const val CAROUSEL = "Lcom/pinterest/ads/onetap/view/CloseupCarouselView;"
        const val ICON_BUTTON = "Lcom/pinterest/gestalt/iconbutton/GestaltIconButton;"
        const val LENS_BUILDER = "Lyt/d;"
        const val SET_ID_CALL = "Landroid/view/View;->setId(I)V"
    }
}
