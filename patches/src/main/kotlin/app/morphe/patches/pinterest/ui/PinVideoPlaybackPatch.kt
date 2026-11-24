/*
 * Original HushPinterest implementation, 2026.
 * Copyright 2026 HushPinterest contributors
 * https://github.com/SysAdminDoc/HushPinterest
 * SPDX-License-Identifier: GPL-3.0-only
 */
package app.morphe.patches.pinterest.ui

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.pinterest.actions.PIN_CLOSEUP_PRESENTER
import app.morphe.patches.pinterest.misc.extension.enableCapability
import app.morphe.patches.pinterest.misc.extension.enableStatus
import app.morphe.patches.pinterest.misc.extension.freeLocalsAt
import app.morphe.patches.pinterest.misc.extension.pinterestExtensionPatch
import app.morphe.patches.pinterest.misc.extension.requireStatusMethod
import app.morphe.patches.pinterest.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Pin video playback"

internal const val PIN_VIDEO_WAITS_HOOK = "$UI_HOOKS->pinVideoWaits()I"
internal const val PIN_VIDEO_MUTED_HOOK = "$UI_HOOKS->pinVideoStartsMuted()I"

/** The ad pin page. Its unbind resets its video's saved state the same way the organic page's does. */
internal const val PROMOTED_CLOSEUP_PRESENTER = "Lcom/pinterest/feature/pin/closeup/presenter/PromotedPinCloseupPresenter;"

/** The lambda each pin page's unbind runs on its video's saved state, a Kotlin name that stays. */
internal const val UNBIND_RESET = "onUnbind\$lambda\$0"

/** An experiment the video manager checks in the method that picks the video on screen. Only used to find that method. */
internal const val VIDEO_MANAGER_EXPERIMENT = "android_global_nav_impression_tracking_modification"

/** The surface a pin page's video reports, an enum constant whose name stays. */
internal const val PIN_CLOSEUP_SURFACE = "PIN_CLOSEUP"

/** The base class of the idea pin page view, a name that stays. */
internal const val ROUNDED_CORNERS_LAYOUT = "Lcom/pinterest/design/widget/RoundedCornersLayout;"

/**
 * Pinterest's saved state for each video, kept by the video's id. [getter] hands back a copy of a
 * video's state, or a fresh one when it has none, and [paused] is the state's flag that keeps a
 * video from starting. [resets] are the two pin pages' unbind lambdas with the index of the
 * `const/4 vA, 0` each stores in [paused] right after, which is how a pin you leave plays again
 * when you come back.
 */
internal class VideoStates(val getter: MethodReference, val paused: FieldReference, val resets: List<Pair<Method, Int>>)

/**
 * Read off the organic pin page's unbind (14.45.0: `onUnbind` @15 to @17). For a video pin it reads
 * its pin's saved state with the getter, a static call taking the pin's id, then hands what came
 * back to [UNBIND_RESET], which clears the paused flag.
 */
internal fun BytecodePatchContext.videoStates(): VideoStates {
    val organic = classDefByOrNull(PIN_CLOSEUP_PRESENTER) ?: throw PatchException("$PATCH: no pin page presenter $PIN_CLOSEUP_PRESENTER")
    val unbind = organic.methods.filter { it.name == "onUnbind" && it.parameterTypes.isEmpty() && it.returnType == "V" }
        .one("$PATCH: the pin page's unbind")
    val body = unbind.instructions()
    val reset = body.indices.filter { at ->
        val called = (body[at] as? ReferenceInstruction)?.reference as? MethodReference
        body[at].opcode == Opcode.INVOKE_STATIC && called?.definingClass == PIN_CLOSEUP_PRESENTER && called.name == UNBIND_RESET
    }.one("$PATCH: the pin page's reset of its video's state")
    val getter = (body.getOrNull(reset - 2) as? ReferenceInstruction)?.reference as? MethodReference
    val lambda = (body[reset] as ReferenceInstruction).reference as MethodReference
    if (getter == null || body[reset - 2].opcode != Opcode.INVOKE_STATIC || getter.parameters() != listOf("Ljava/lang/String;") ||
        lambda.parameters() != listOf(getter.returnType) || body[reset - 1].opcode != Opcode.MOVE_RESULT_OBJECT ||
        body[reset].passed() != listOf((body[reset - 1] as OneRegisterInstruction).registerA)
    ) throw PatchException("$PATCH: the pin page no longer resets the saved state it reads for its video")
    val resets = listOf(PIN_CLOSEUP_PRESENTER, PROMOTED_CLOSEUP_PRESENTER).map { type ->
        val owner = classDefByOrNull(type) ?: throw PatchException("$PATCH: no pin page presenter $type")
        val method = owner.methods.filter { it.name == UNBIND_RESET && it.parameters() == listOf(getter.returnType) && it.implementation != null }
            .one("$PATCH: $type's reset of its video's state")
        method to clearIn(method, getter.returnType)
    }
    val paused = resets.map { (method, at) -> (method.instructions()[at + 1] as ReferenceInstruction).reference as FieldReference }
        .distinct().one("$PATCH: the flag both pin pages clear")
    return VideoStates(getter, paused, resets)
}

/** The index of the `const/4 vA, 0` [method] stores in a boolean of [state], its only store, right after (14.45.0: @1). */
private fun clearIn(method: Method, state: String): Int {
    val body = method.instructions()
    val stores = body.indices.filter { body[it].opcode == Opcode.IPUT_BOOLEAN }
    val store = stores.singleOrNull()
        ?: throw PatchException("$PATCH: ${method.definingClass}->${method.name} stores ${stores.size} flags, expected 1")
    val flag = (body[store] as ReferenceInstruction).reference as FieldReference
    val constant = body.getOrNull(store - 1)
    val parameter = method.implementation!!.registerCount - 1
    if (constant?.opcode != Opcode.CONST_4 || (constant as NarrowLiteralInstruction).narrowLiteral != 0 ||
        (constant as OneRegisterInstruction).registerA != (body[store] as TwoRegisterInstruction).registerA ||
        (body[store] as TwoRegisterInstruction).registerB != parameter || flag.definingClass != state || flag.type != "Z"
    ) throw PatchException("$PATCH: ${method.definingClass}->${method.name} no longer clears a flag of the video's state")
    return store - 1
}

/** Whether a try range covers the instruction at [index]. */
private fun Method.videoTryCovers(index: Int): Boolean {
    val code = implementation ?: return false
    val address = instructions().take(index).sumOf { it.codeUnits }
    return code.tryBlocks.any { address >= it.startCodeAddress && address < it.startCodeAddress + it.codeUnitCount }
}

/** The state getter's return of a fresh state, built for a video it has nothing saved for, and the register that state is in. */
internal class FreshState(val method: Method, val at: Int, val state: Int)

/**
 * In the getter (14.45.0: `mc3/u.b(String)`, @18), the cached state is cast and tested for null.
 * Where there's none, a fresh state is built in one straight run and returned. That fresh state
 * plays, which is what lets a pin page's video start on its own.
 */
internal fun BytecodePatchContext.freshState(states: VideoStates): FreshState {
    val getter = states.getter
    val method = classDefByOrNull(getter.definingClass)?.methods?.singleOrNull {
        it.name == getter.name && it.parameters() == getter.parameters() && it.returnType == getter.returnType && it.implementation != null
    } ?: throw PatchException("$PATCH: the video state getter $getter has no body here")
    val body = method.instructions()
    val flow = ControlFlow.of(method)
    val cast = body.indices.filter { at ->
        body[at].opcode == Opcode.CHECK_CAST && ((body[at] as ReferenceInstruction).reference as TypeReference).type == getter.returnType
    }.one("$PATCH: the video state getter's saved state")
    val test = body.getOrNull(cast + 1)
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != (body[cast] as OneRegisterInstruction).registerA) {
        throw PatchException("$PATCH: the video state getter no longer checks for a saved state")
    }
    val fresh = flow.normal[cast + 1].filter { it != cast + 2 }.one("$PATCH: the video state getter's way to a fresh state")
    var at = fresh
    while (at < body.size && body[at].opcode != Opcode.RETURN_OBJECT) {
        if (flow.normal[at] != listOf(at + 1)) throw PatchException("$PATCH: the video state getter's fresh state isn't built in one straight run")
        at++
    }
    if (at >= body.size) throw PatchException("$PATCH: the video state getter never returns its fresh state")
    for (inside in fresh + 1..at) {
        if (flow.normal.indices.any { from -> from != inside - 1 && inside in flow.normal[from] }) {
            throw PatchException("$PATCH: something jumps into the video state getter's fresh state at $inside")
        }
    }
    val state = (body[at] as OneRegisterInstruction).registerA
    val built = (fresh until at).any { k ->
        body[k].opcode == Opcode.NEW_INSTANCE && (body[k] as OneRegisterInstruction).registerA == state &&
            ((body[k] as ReferenceInstruction).reference as TypeReference).type == getter.returnType
    }
    if (!built) throw PatchException("$PATCH: the video state getter's fresh state isn't a new ${getter.returnType}")
    if (state > 15) throw PatchException("$PATCH: the fresh video state is in v$state, out of reach of iput-boolean")
    if (method.videoTryCovers(at)) throw PatchException("$PATCH: the fresh video state's return sits inside a try range")
    return FreshState(method, at, state)
}

/** The idea pin page's start of its video: the method, its `play()`, and the register the video is in. */
internal class IdeaPinStart(val method: Method, val play: Int, val video: Int)

/**
 * An idea pin page plays its page's video itself when the page becomes the one on screen
 * (14.45.0: `fx1/n1.e()` @15, from `fx1/b1.C1`), without asking the saved state first. The method
 * clears a pending flag of the page, seeks the video to its start and plays it.
 */
internal fun BytecodePatchContext.ideaPinStart(): IdeaPinStart {
    val found = mutableListOf<IdeaPinStart>()
    classDefForEach { owner ->
        if (owner.superclass != ROUNDED_CORNERS_LAYOUT) return@classDefForEach
        owner.methods.mapNotNullTo(found, ::ideaPinStartIn)
    }
    return found.one("$PATCH: the idea pin page's start of its video")
}

private fun ideaPinStartIn(method: Method): IdeaPinStart? {
    if (method.parameterTypes.isNotEmpty() || method.returnType != "V" || method.implementation == null) return null
    val body = method.instructions()
    val play = body.indices.filter { at ->
        val called = (body[at] as? ReferenceInstruction)?.reference as? MethodReference
        body[at].opcode == Opcode.INVOKE_INTERFACE && called?.name == "play" && called.parameterTypes.isEmpty() && called.returnType == "V"
    }.singleOrNull() ?: return null
    if (play < 1 || play + 1 >= body.size) return null
    val video = body[play].passed().singleOrNull() ?: return null
    val played = (body[play] as ReferenceInstruction).reference as MethodReference
    val seek = (body[play - 1] as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if (body[play - 1].opcode != Opcode.INVOKE_INTERFACE || seek.definingClass != played.definingClass ||
        seek.parameters() != listOf("J", "Z") || body[play - 1].passed().firstOrNull() != video
    ) return null
    // It runs once for each time the page comes on screen: it reads the page's pending flag and clears it first.
    val read = body.take(play).filter { it.opcode == Opcode.IGET_BOOLEAN }.map { (it as ReferenceInstruction).reference as FieldReference }
    val cleared = body.take(play).filter { it.opcode == Opcode.IPUT_BOOLEAN }.map { (it as ReferenceInstruction).reference as FieldReference }
    if (read.none { it in cleared && it.definingClass != method.definingClass }) return null
    return IdeaPinStart(method, play, video)
}

/**
 * Where the video manager makes a video the one on screen: [at] is its call that marks the video
 * active, reached only by the two jumps that let a video through, one of them testing [closeup],
 * the register that says the video sits on a pin page. [video] is that video, [mute] the app-wide
 * sound flag a pin page reads when it builds its player, [muteVideo] the video's own mute setter
 * and [muteCall] the invoke the manager uses for it.
 */
internal class VideoActivation(
    val method: Method, val at: Int, val closeup: Int, val video: Int,
    val mute: FieldReference, val muteVideo: MethodReference, val muteCall: String,
)

/**
 * 14.45.0: `vb3/u1.u()`. @395 to @401 set v10 to 1 for a video whose surface is [PIN_CLOSEUP_SURFACE],
 * @412 skips a video that's already the active one, @414 and @415 let a new one through to @417,
 * the call that makes it active, and @416 sends everything else away. A pin page's video comes
 * through there once each time you open or swipe to its pin. @421 and @422 read the sound flag.
 */
internal fun BytecodePatchContext.videoActivation(states: VideoStates): VideoActivation =
    methodsWithString(VIDEO_MANAGER_EXPERIMENT).mapNotNull { activationIn(it, states) }.toList()
        .one("$PATCH: the video manager's pick of the video on screen")

private fun activationIn(method: Method, states: VideoStates): VideoActivation? {
    if (method.parameterTypes.isNotEmpty() || method.returnType != "V" || method.implementation == null) return null
    val body = method.instructions()
    val video = body.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
        .filter { it.name == "play" && it.parameterTypes.isEmpty() && it.returnType == "V" }.map { it.definingClass }.distinct()
        .singleOrNull() ?: return null
    val flow = ControlFlow.of(method)
    fun into(at: Int) = flow.normal.indices.filter { at in flow.normal[it] }
    val at = body.indices.filter { k ->
        val called = (body[k] as? ReferenceInstruction)?.reference as? MethodReference
        if (body[k].opcode != Opcode.INVOKE_VIRTUAL || called == null || called.parameters() != listOf(video) || called.returnType != "V") {
            return@filter false
        }
        val from = into(k)
        from.size == 2 && k - 1 !in from && from.map { body[it].opcode }.toSet() == setOf(Opcode.IF_EQZ, Opcode.IF_NEZ)
    }.singleOrNull() ?: return null
    val closeup = (body[into(at).single { body[it].opcode == Opcode.IF_NEZ }] as OneRegisterInstruction).registerA
    // That register is 1 only right after the video's surface compared equal to the pin page's.
    val marked = (2 until at).any { k ->
        val surface = (body[k - 2] as? ReferenceInstruction)?.reference as? FieldReference
        body[k - 2].opcode == Opcode.SGET_OBJECT && surface?.name == PIN_CLOSEUP_SURFACE && body[k - 1].opcode == Opcode.IF_NE &&
            body[k].opcode == Opcode.CONST_4 && (body[k] as OneRegisterInstruction).registerA == closeup &&
            (body[k] as NarrowLiteralInstruction).narrowLiteral == 1
    }
    if (!marked) return null
    val shown = body[at].passed().getOrNull(1) ?: return null
    // The app-wide sound flag, read right before the same class's getter for it. It lives with the saved states.
    val mute = (1 until body.size).mapNotNull { k ->
        val flag = (body[k - 1] as? ReferenceInstruction)?.reference as? FieldReference
        val read = (body[k] as? ReferenceInstruction)?.reference as? MethodReference
        if (body[k - 1].opcode == Opcode.SGET_BOOLEAN && body[k].opcode == Opcode.INVOKE_STATIC && flag != null && read != null &&
            flag.type == "Z" && flag.definingClass == read.definingClass && read.parameterTypes.isEmpty() && read.returnType == "Z" &&
            flag.definingClass == states.getter.definingClass
        ) flag else null
    }.distinct().singleOrNull() ?: return null
    val setters = body.filter { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == video && called.parameters() == listOf("Z") && called.returnType == "V"
    }
    val muteVideo = setters.map { (it as ReferenceInstruction).reference as MethodReference }.distinct().singleOrNull() ?: return null
    val muteCall = when (setters.map { it.opcode }.distinct().singleOrNull()) {
        Opcode.INVOKE_INTERFACE -> "invoke-interface"
        Opcode.INVOKE_VIRTUAL -> "invoke-virtual"
        else -> return null
    }
    return VideoActivation(method, at, closeup, shown, mute, muteVideo, muteCall)
}

/**
 * Pin video playback (#48), two switches. Don't autoplay pin videos: Pinterest's pin pages start a
 * video unless its saved state says paused, and a video with nothing saved gets a fresh state that
 * plays. The extension's answer goes into that fresh state's paused flag, and into the flag each
 * pin page clears when you leave it, so a pin you come back to doesn't start either. Idea pin pages
 * start their page's video themselves, and that one play asks too. Start pin videos muted: each
 * time a pin page's video becomes the one on screen, the app-wide sound flag and the video itself
 * go to muted when the extension says so. Tapping the speaker works as before.
 */
@Suppress("unused")
val pinVideoPlaybackPatch = bytecodePatch(
    name = PATCH,
    description = "Lets a video on a pin you open or swipe to wait for you to tap play, and start with the sound off. " +
        "Two switches, both start off. Turn them on in HushPinterest settings > Interface.",
) {
    category("Pin page")
    dependsOn(settingsPatch, pinterestExtensionPatch)
    compatibleWith(*AppCompatibilities.pinterest())

    execute {
        requireStatusMethod("pinVideoPlayback")
        requireStatusMethod("pinVideoAutoplay")
        requireStatusMethod("pinVideoSound")
        val states = videoStates()
        val fresh = freshState(states)
        val idea = ideaPinStart()
        val activation = videoActivation(states)
        val answer = fresh.method.freeLocalsAt("$PATCH: a fresh video state", fresh.at, 1, reads = listOf(fresh.state)).single()
        val waits = idea.method.freeLocalsAt("$PATCH: the idea pin page's start", idea.play, 1, highest = 255, reads = listOf(idea.video)).single()
        if (activation.video > 15) throw PatchException("$PATCH: the video on screen is in v${activation.video}, out of reach of its mute setter")
        if (activation.closeup > 255) throw PatchException("$PATCH: the pin page test is in v${activation.closeup}, out of reach of if-eqz")
        val muted = activation.method.freeLocalsAt("$PATCH: the video on screen", activation.at, 1,
            reads = listOf(activation.closeup, activation.video)).single()

        // Don't autoplay pin videos: a fresh state waits, a pin page you leave keeps its video waiting,
        // and an idea pin page skips its own play.
        mutable(fresh.method).addInstructionsAtControlFlowLabel(fresh.at, """
            invoke-static {}, $PIN_VIDEO_WAITS_HOOK
            move-result v$answer
            iput-boolean v$answer, v${fresh.state}, ${states.paused}
        """)
        for ((method, at) in states.resets) {
            val register = (method.instructions()[at] as OneRegisterInstruction).registerA
            mutable(method).addInstructionsAtControlFlowLabel(at + 1, """
                invoke-static {}, $PIN_VIDEO_WAITS_HOOK
                move-result v$register
            """)
        }
        val start = mutable(idea.method)
        start.addInstructionsAtControlFlowLabel(idea.play, """
            invoke-static {}, $PIN_VIDEO_WAITS_HOOK
            move-result v$waits
            if-nez v$waits, :hush_video_waits
        """, ExternalLabel("hush_video_waits", start.getInstruction(idea.play + 1)))
        enableCapability("pinVideoAutoplay")

        // Start pin videos muted: the hook's answer is 0 or 1, the value Pinterest's sound flag takes.
        val manager = mutable(activation.method)
        manager.addInstructionsAtControlFlowLabel(activation.at, """
            if-eqz v${activation.closeup}, :hush_sound_kept
            invoke-static {}, $PIN_VIDEO_MUTED_HOOK
            move-result v$muted
            if-eqz v$muted, :hush_sound_kept
            sput-boolean v$muted, ${activation.mute}
            ${activation.muteCall} {v${activation.video}, v$muted}, ${activation.muteVideo}
        """, ExternalLabel("hush_sound_kept", manager.getInstruction(activation.at)))
        enableCapability("pinVideoSound")
        enableStatus("pinVideoPlayback")
    }
}
