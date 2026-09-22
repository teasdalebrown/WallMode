# Hey Pulse model provenance

`hey_pulse.tflite` and `hey_pulse.json` are copied unchanged from the deployed
Pulse Voice firmware source:

`/home/manager/pulse-waveshare-s3-audio/firmware/models/hey_pulse/`

The JSON metadata remains the source of truth for the phrase and production
detection settings. The Android trial uses its `0.71` probability cutoff,
three-result sliding window and 10 ms feature step without retuning.
