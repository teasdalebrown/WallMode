# Speech volume controls — 2026-10-04

Normal speech uses the accepted communication profile introduced in 4f4d696,
which prevented self-cutoff on the fixed audible comparison. That change also
moved speech from media to the independent voice-call volume stream. The Honor
incident confirmed USAGE_VOICE_COMMUNICATION maps to STREAM_VOICE_CALL, while
the local preview uses USAGE_MEDIA/STREAM_MUSIC; lowering media need not lower
speech. Android reported voice-call speaker8/10 and media speaker9/15. No
percentage/gain equivalence between those streams is assumed.

The Activity now targets STREAM_VOICE_CALL while its speech-turn lease is
active and restores the exact previous Activity volumeControlStream when the
lease closes. The same cleanup covers completion, physical Cancel, request
failure, pause and destroy. One lease spans opening cues and answer chunks.
No volume level, PCM gain, capture source, audio usage, Stop detector or Cancel
handler changes. Source: Android Activity.setVolumeControlStream foreground
hardware-volume contract:
https://developer.android.com/reference/android/app/Activity#setVolumeControlStream(int)

106 Android tests pass and assembleDebug succeeds. New tests exercise one
volume target across successive chunks, completion/pause restoration,
unavailable capture, and target failure rollback. Independent source review
accepted the patch. A silent existing fixed-audio diagnostic can verify runtime
target selection/restoration without unsolicited audible speech or volume keys.
Physical-key volume adjustment itself remains owner-operated.

Separate approved cue asset update in Pulse Voice repository retains the
canonical cue text/cache key, replacing only opening1 waveform. Accepted WAV
SHA6639b7136bc3100d1161172698137e320000bb077f7ba187f33bbf898076ad1c,
6.656s, mono24kHz PCM16; render text: Ah, hello. Hang on. I'll have to check that
first. Opening2 unchanged; no new synthesis or endpoint playback.

Complete source, APK, tests, exact previous APK/preferences/cue and deployment
verification are preserved on healthy NAS under
/volume1/pulse_life/voice-training/morris-cues-20261004/volume-cue-fix.
