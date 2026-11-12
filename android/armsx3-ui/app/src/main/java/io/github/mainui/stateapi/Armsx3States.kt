package io.github.mainui.stateapi

import com.armsx3.Rpcs3Bridge
import com.armsx3.core.ARMSX3
import com.armsx3.core.EmulatorState

/**
 * ARMSX3's side of the MainUI State API ([MainUIStateApi]). MainUI's slots 0-9 are the save
 * state picker's own; MainUI's resume state is slot 99 (10 is ARMSX3's own auto-save). Saving
 * and loading go through the picker's calls. Both are asynchronous in RPCS3 (a save stops the
 * game, writes the state and restarts from it; a load reboots into the state), so each waits
 * until the state is written or the game is back before answering.
 */
object Armsx3States : MainUIStateApi.Emulator {
    private const val SLOTS = 10
    private const val RESUME = 99

    private fun index(slot: Int) = if (slot == MainUIStateApi.RESUME_SLOT) RESUME else slot

    private fun state(): EmulatorState? = runCatching { ARMSX3.getState() }.getOrNull()

    override fun isRunning(): Boolean =
        state().let { it == EmulatorState.Running || it == EmulatorState.Paused } && Rpcs3Bridge.getTitleId().isNotEmpty()

    override fun isReady(): Boolean =
        state() == EmulatorState.Running && !Rpcs3Bridge.isPaused() && Rpcs3Bridge.getTitleId().isNotEmpty()

    override fun slotCount() = SLOTS

    /** MainUI's pause took effect (not one the user or ARMSX3's overlay holds). */
    @Volatile private var pausedByMainUi = false

    override fun pause(): Boolean {
        if (Rpcs3Bridge.isPaused()) return true
        Rpcs3Bridge.pause()
        pausedByMainUi = true
        return true
    }

    override fun resume() {
        if (!pausedByMainUi) return
        pausedByMainUi = false
        Rpcs3Bridge.resume()
    }

    override fun save(slot: Int): String {
        val i = index(slot)
        val before = Rpcs3Bridge.slotSavedAt(i)
        if (!Rpcs3Bridge.saveState(i)) throw IllegalStateException("ARMSX3 couldn't save the state")
        // Written once the slot's file changes (the game is stopped meanwhile)...
        if (!MainUIStateApi.await({ Rpcs3Bridge.slotSavedAt(i).let { it != 0L && it != before } }, 180_000)) {
            throw IllegalStateException("ARMSX3 didn't finish saving the state")
        }
        // ...and the game comes back up from it (not when saving is set to end the game).
        MainUIStateApi.await({ state() == EmulatorState.Running }, 60_000)
        return Rpcs3Bridge.slotFilePath(i) ?: ""
    }

    override fun load(slot: Int) {
        val i = index(slot)
        if (!Rpcs3Bridge.isStateReadable(i)) throw IllegalStateException("this ARMSX3 build can't read that state")
        if (!Rpcs3Bridge.loadState(i)) throw IllegalStateException("ARMSX3 couldn't load the state")
        // The game reboots into the state: it stops, then runs again.
        MainUIStateApi.await({ state() != EmulatorState.Running }, 15_000)
        if (!MainUIStateApi.await({ state() == EmulatorState.Running }, 180_000)) {
            throw IllegalStateException("the game didn't come back from the state")
        }
    }

    override fun slotTime(slot: Int): Long = Rpcs3Bridge.slotSavedAt(index(slot))
}
