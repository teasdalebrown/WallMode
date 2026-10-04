# Honor capture endpoint and playback Stop scope — 2026-10-04

The owner flask question woke Morris at 11:41:32.328850 UTC. Capture was
submitted at 11:41:39.465125 with exactly 136,000 samples / 8,500 ms.
The source ceiling was 24,000 pre-wake samples plus 112,000 post-wake
samples (7 seconds). The 80 ms microphone loop stops at the first chunk
at or beyond that ceiling and truncates storage to exactly 136,000.
Speech-start occurred at 11:41:33.520344. STT retained a partial request
ending at “vacuum shiny wall”; the owner was still speaking “stopper”.
The record proves the capture reached the hard ceiling. The previous trace
had no ending-reason/silence count, so a simultaneous silence decision
cannot independently be excluded. There is no evidence of a lifecycle
failure: normal AudioCaptured then request_started occurred.

Correction: allow up to 30 seconds after wake (31.5 seconds including
pre-roll), retaining the existing 2-second silence endpoint and 1.5-second
no-speech timeout. Short commands do not wait for the ceiling. Record
end_reason, post_wake_samples and silence_samples on submission so future
endpoint decisions are explicit. No VAD threshold, model or backend change.
A finite ceiling remains necessary for noise/uninterrupted input; this is
not a promise to capture requests longer than 30 seconds after wake.

Separate issue: acoustic Stop cancelled the backend request at 11:41:40.438737,
before any cue/answer playback. The owner's corrected scope preserves Stop
outside actual assistant playback. Each AudioTrack playback now holds a
suppression token from just before hardware play until its cleanup; overlapping
cue/answer tokens cannot rearm Stop prematurely. Queued Stop events are also
checked against playback suppression and a playback/turn epoch on the UI
thread, including detections delivered after a complete playback cycle. Physical Cancel and
normal Morris/Annabel wake, communication capture/volume lease, cue content,
request preparation and answer priority remain unchanged.

Regression checks exercise the production endpoint decision with 80 ms chunk
accounting through 16 seconds of ongoing speech, short-command silence,
no-speech timeout, the ceiling, playback handover and physical cancellation.
Synthetic endpoint tests establish capture timing; they do not establish live
STT accuracy. Owner speech verification has not been repeated automatically.
