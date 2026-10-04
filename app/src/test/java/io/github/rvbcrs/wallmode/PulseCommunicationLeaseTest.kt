package io.github.rvbcrs.wallmode

import org.junit.Assert.*
import org.junit.Test

class PulseCommunicationLeaseTest {
    @Test fun completionAndCancelReleaseRecorderBeforeRestoringExactPriorMode() {
        val events = mutableListOf<String>()
        val lease = PulseCommunicationLease(2, 3, { events.add("mode=$it") }, { enabled, resume -> events.add("capture=$enabled/$resume"); true })
        assertTrue(lease.enter()); lease.close(true); lease.close(true)
        assertEquals(listOf("mode=3", "capture=true/true", "capture=false/false", "mode=2", "capture=false/true"), events)
    }
    @Test fun pauseRestoresModeWithoutRestartingMicrophone() {
        val events = mutableListOf<String>()
        val lease = PulseCommunicationLease(0, 3, { events.add("mode=$it") }, { e, r -> events.add("capture=$e/$r"); true })
        lease.enter(); lease.close(false)
        assertEquals(listOf("mode=3", "capture=true/true", "capture=false/false", "mode=0"), events)
    }
    @Test fun unavailableCommunicationMicrophoneFallsBackBeforeAnyPlayback() {
        var mode = 0; val captures = mutableListOf<Pair<Boolean, Boolean>>()
        val lease = PulseCommunicationLease(0, 3, { mode=it }, { e,r -> captures.add(e to r); !e })
        assertFalse(lease.enter()); assertEquals(0, mode)
        assertEquals(listOf(true to true, false to false, false to true), captures)
    }
    @Test fun captureExceptionStillRestoresModeAndOrdinaryRecorder() {
        var mode=0; var ordinaryStarted=false
        val lease=PulseCommunicationLease(0,3,{mode=it},{e,r -> if(e) throw IllegalStateException("unavailable"); if(r) ordinaryStarted=true; true})
        assertFalse(lease.enter()); assertEquals(0,mode); assertTrue(ordinaryStarted)
    }
    @Test fun rejectedCommunicationModeAbortsAndRestoresOrdinaryCapture() {
        var mode=0; var ordinary=false
        val lease=PulseCommunicationLease(0,3,{ if(it==3) throw SecurityException("denied"); mode=it },{e,r -> if(!e && r) ordinary=true; true})
        assertFalse(lease.enter()); assertEquals(0,mode); assertTrue(ordinary)
    }
    @Test fun successiveSpeechChunksKeepOneRecorderAndClosedTurnCannotReenter() {
        var modes=0; var communicationStarts=0
        val lease=PulseCommunicationLease(0,3,{modes++},{e,r -> if(e && r) communicationStarts++; true})
        repeat(4) { assertTrue(lease.enter()) }
        assertEquals(1,modes); assertEquals(1,communicationStarts)
        lease.close(true); assertFalse(lease.enter())
        assertEquals(2,modes); assertEquals(1,communicationStarts)
    }
}
