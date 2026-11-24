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
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.pinterest.actions.PIN_CLOSEUP_PRESENTER
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** #48: the saved video states, the pin pages' reset of them, the idea pin page's own play and the video manager's pick of the video on screen. */
@Category(FixtureTests::class)
class PinVideoPlaybackFixtureTest {
    private val flags = listOf("pinVideoPlayback", "pinVideoAutoplay", "pinVideoSound")

    /** Both pin page presenters, the video manager, every class that plays a video, and the class keeping the saved states. */
    private fun read(build: File): List<ClassDef> {
        val found = FixtureDex.classesWhere(build, { true }) { method ->
            method.definingClass == PIN_CLOSEUP_PRESENTER || method.definingClass == PROMOTED_CLOSEUP_PRESENTER ||
                VIDEO_MANAGER_EXPERIMENT in method.strings() ||
                method.calls().any { it.name == "play" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        }
        val states = PatchContexts.of(found).videoStates().getter.definingClass
        return (found + FixtureDex.classes(build, setOf(states)).values).distinctBy { it.type }
    }

    private fun mutableOf(context: BytecodePatchContext, method: Method) =
        context.mutableClassDefBy(method.definingClass).methods.single {
            it.name == method.name && it.parameters() == method.parameters() && it.returnType == method.returnType
        } as MutableMethod

    /** Where each jump in [method] onto [at] came from, not counting the instruction before it. */
    private fun jumpsOnto(method: Method, at: Int): List<Int> {
        val flow = ControlFlow.of(method)
        return flow.normal.indices.filter { it != at - 1 && at in flow.normal[it] }
    }

    /** The patched instruction a jump [from] in the stock method lands on, with [added] instructions put in at [at]. */
    private fun landing(context: BytecodePatchContext, method: Method, from: Int, at: Int, added: Int): Int {
        val moved = if (from < at) from else from + added
        return ((mutableOf(context, method).implementation!!.instructions[moved]) as BuilderOffsetInstruction).target.location.index
    }

    private fun reg(instruction: com.android.tools.smali.dexlib2.iface.instruction.Instruction) = (instruction as OneRegisterInstruction).registerA

    @Test
    fun `each declared APK asks before a fresh video state, a page's reset, an idea pin's play and a pin page video's sound`() {
        for (build in Fixtures.declaredBuilds()) {
            val classes = read(build)
            val clean = PatchContexts.of(classes)
            val states = clean.videoStates()
            val fresh = clean.freshState(states)
            val idea = clean.ideaPinStart()
            val activation = clean.videoActivation(states)
            val context = PatchContexts.of(ExtensionDex.classes() + classes)
            pinVideoPlaybackPatch.execute(context)
            for (flag in flags) assertEquals("${build.name}: $flag", 1, PinPageKit.flag(context, flag))

            // A fresh state: the hook's answer goes into its paused flag right before it's handed back.
            val got = fresh.method.instructions()
            val made = PinPageKit.patched(context, fresh.method)
            val at = fresh.at
            assertEquals(build.name, PIN_VIDEO_WAITS_HOOK, PinPageKit.target(made[at]))
            assertTrue("${build.name}: the hook takes nothing", made[at].passed().isEmpty())
            assertEquals(build.name, Opcode.MOVE_RESULT, made[at + 1].opcode)
            val answer = reg(made[at + 1])
            assertTrue("${build.name}: the answer's register v$answer holds the state", answer != fresh.state && answer <= 15)
            assertEquals(build.name, Opcode.IPUT_BOOLEAN, made[at + 2].opcode)
            assertEquals(build.name, states.paused.toString(), PinPageKit.target(made[at + 2]))
            assertEquals(build.name, answer, (made[at + 2] as TwoRegisterInstruction).registerA)
            assertEquals("${build.name}: into the state returned", fresh.state, (made[at + 2] as TwoRegisterInstruction).registerB)
            assertEquals(build.name, Opcode.RETURN_OBJECT, made[at + 3].opcode)
            assertEquals(build.name, fresh.state, reg(made[at + 3]))
            assertEquals("${build.name}: the getter is otherwise Pinterest's", PinPageKit.kinds(got),
                PinPageKit.kinds(made.take(at) + made.drop(at + 3)))
            for (from in jumpsOnto(fresh.method, at)) {
                assertEquals("${build.name}: a jump to the return runs the hook first", at, landing(context, fresh.method, from, at, 3))
            }

            // Each pin page's reset: the hook's answer replaces its false right before the store. Pinterest's constant stays.
            assertEquals(build.name, listOf(PIN_CLOSEUP_PRESENTER, PROMOTED_CLOSEUP_PRESENTER), states.resets.map { it.first.definingClass })
            for ((method, clear) in states.resets) {
                val before = method.instructions()
                val after = PinPageKit.patched(context, method)
                val register = reg(before[clear])
                assertEquals(build.name, Opcode.CONST_4, after[clear].opcode)
                assertEquals(build.name, PIN_VIDEO_WAITS_HOOK, PinPageKit.target(after[clear + 1]))
                assertEquals(build.name, Opcode.MOVE_RESULT, after[clear + 2].opcode)
                assertEquals("${build.name}: the answer goes where the false was", register, reg(after[clear + 2]))
                assertEquals(build.name, Opcode.IPUT_BOOLEAN, after[clear + 3].opcode)
                assertEquals(build.name, states.paused.toString(), PinPageKit.target(after[clear + 3]))
                assertEquals(build.name, register, (after[clear + 3] as TwoRegisterInstruction).registerA)
                assertEquals("${build.name}: ${method.definingClass} is otherwise Pinterest's", PinPageKit.kinds(before),
                    PinPageKit.kinds(after.take(clear + 1) + after.drop(clear + 3)))
            }

            // The idea pin page's start: on yes only the play is skipped, the seek to the start stays.
            val ideaBefore = idea.method.instructions()
            val ideaAfter = PinPageKit.patched(context, idea.method)
            val play = idea.play
            assertEquals(build.name, PIN_VIDEO_WAITS_HOOK, PinPageKit.target(ideaAfter[play]))
            assertEquals(build.name, Opcode.MOVE_RESULT, ideaAfter[play + 1].opcode)
            val waits = reg(ideaAfter[play + 1])
            assertTrue("${build.name}: v$waits holds the video", waits != idea.video)
            assertEquals(build.name, Opcode.IF_NEZ, ideaAfter[play + 2].opcode)
            assertEquals(build.name, waits, reg(ideaAfter[play + 2]))
            assertEquals("${build.name}: yes lands past the play", play + 4,
                (mutableOf(context, idea.method).implementation!!.instructions[play + 2] as BuilderOffsetInstruction).target.location.index)
            assertEquals(build.name, PinPageKit.target(ideaBefore[play]), PinPageKit.target(ideaAfter[play + 3]))
            assertEquals(build.name, listOf(idea.video), ideaAfter[play + 3].passed())
            assertEquals("${build.name}: the idea pin page is otherwise Pinterest's", PinPageKit.kinds(ideaBefore),
                PinPageKit.kinds(ideaAfter.take(play) + ideaAfter.drop(play + 3)))

            // The video manager: a pin page's video that just became the one on screen is muted on yes.
            val pick = activation.at
            val managerBefore = activation.method.instructions()
            val managerAfter = PinPageKit.patched(context, activation.method)
            val manager = mutableOf(context, activation.method).implementation!!.instructions
            assertEquals(build.name, Opcode.IF_EQZ, managerAfter[pick].opcode)
            assertEquals("${build.name}: only a pin page's video", activation.closeup, reg(managerAfter[pick]))
            assertEquals(build.name, pick + 6, (manager[pick] as BuilderOffsetInstruction).target.location.index)
            assertEquals(build.name, PIN_VIDEO_MUTED_HOOK, PinPageKit.target(managerAfter[pick + 1]))
            assertEquals(build.name, Opcode.MOVE_RESULT, managerAfter[pick + 2].opcode)
            val muted = reg(managerAfter[pick + 2])
            assertTrue("${build.name}: v$muted is free", muted !in listOf(activation.closeup, activation.video) && muted <= 15)
            assertEquals(build.name, Opcode.IF_EQZ, managerAfter[pick + 3].opcode)
            assertEquals(build.name, muted, reg(managerAfter[pick + 3]))
            assertEquals(build.name, pick + 6, (manager[pick + 3] as BuilderOffsetInstruction).target.location.index)
            assertEquals(build.name, Opcode.SPUT_BOOLEAN, managerAfter[pick + 4].opcode)
            assertEquals(build.name, muted, reg(managerAfter[pick + 4]))
            assertEquals(build.name, activation.mute.toString(), PinPageKit.target(managerAfter[pick + 4]))
            assertEquals(build.name, activation.muteVideo.toString(), PinPageKit.target(managerAfter[pick + 5]))
            assertEquals("${build.name}: the video and the answer", listOf(activation.video, muted), managerAfter[pick + 5].passed())
            assertEquals(build.name, PinPageKit.target(managerBefore[pick]), PinPageKit.target(managerAfter[pick + 6]))
            assertEquals(build.name, managerBefore[pick].passed(), managerAfter[pick + 6].passed())
            val onto = jumpsOnto(activation.method, pick)
            assertEquals("${build.name}: the two jumps that let a video through", 2, onto.size)
            for (from in onto) {
                assertEquals("${build.name}: a jump from $from runs the hook first", pick, landing(context, activation.method, from, pick, 6))
            }
            assertEquals("${build.name}: the video manager is otherwise Pinterest's", PinPageKit.kinds(managerBefore),
                PinPageKit.kinds(managerAfter.take(pick) + managerAfter.drop(pick + 6)))
        }
    }

    @Test
    fun `a missing stub, pin page, idea pin page, video manager or state getter refuses with Pinterest's code untouched`() {
        val classes = read(Fixtures.declaredBuilds().last())
        val clean = PatchContexts.of(classes)
        val states = clean.videoStates()
        val idea = clean.ideaPinStart().method.definingClass
        val manager = clean.videoActivation(states).method.definingClass
        val cases = linkedMapOf(
            "no pinVideoPlayback stub" to (PinPageKit.extensionWithout("pinVideoPlayback") + classes to "pinVideoPlayback"),
            "no pinVideoAutoplay stub" to (PinPageKit.extensionWithout("pinVideoAutoplay") + classes to "pinVideoAutoplay"),
            "no pinVideoSound stub" to (PinPageKit.extensionWithout("pinVideoSound") + classes to "pinVideoSound"),
            "no ad pin page" to (ExtensionDex.classes() + classes.filter { it.type != PROMOTED_CLOSEUP_PRESENTER } to PROMOTED_CLOSEUP_PRESENTER),
            "no state getter" to (ExtensionDex.classes() + classes.filter { it.type != states.getter.definingClass } to "video state getter"),
            "no idea pin page" to (ExtensionDex.classes() + classes.filter { it.type != idea } to "idea pin page"),
            "no video manager" to (ExtensionDex.classes() + classes.filter { it.type != manager } to "video manager"),
        )
        for ((reason, case) in cases) {
            val (input, message) = case
            val context = PatchContexts.of(input)
            val thrown = assertThrows(reason, PatchException::class.java) { pinVideoPlaybackPatch.execute(context) }
            assertTrue("$reason: ${thrown.message}", thrown.message.orEmpty().contains(message))
            for (flag in flags) assertEquals("$reason: $flag", 0, PinPageKit.flagOrZero(context, flag))
            val present = input.map { it.type }.toSet()
            for (method in classes.filter { it.type in present }.flatMap { it.methods }.filter { it.implementation != null }) {
                assertEquals("$reason changed ${method.definingClass}->${method.name}",
                    method.instructions().map { it.opcode }, PinPageKit.patched(context, method).map { it.opcode })
            }
        }
    }
}
