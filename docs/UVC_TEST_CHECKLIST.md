# Manual UVC Compatibility Checklist

Record phone/tablet model, Android version/build, camera make/model, VID/PID, cable/hub, external power, and negotiated resolution for every run.

## Preview gate (must pass before Milestone 4)

- [ ] App cold launch with no camera shows waiting state
- [ ] Attach camera while foregrounded
- [ ] USB prompt appears only when permission is absent
- [ ] Grant continues without another Connect action
- [ ] Camera attached before launch is discovered
- [ ] Android attach-launch route enters the same flow
- [ ] First frame appears and debug timings are captured
- [ ] Unplug closes preview without crash
- [ ] Replug resumes automatically when permission is retained/renewed
- [ ] Non-UVC USB device does not start the camera path
- [ ] Denial is retryable
- [ ] 1080p failure falls back to 720p/480p
- [ ] MJPEG failure falls back to YUY2

## Extended soak matrix

- [ ] 10 repeated unplug/replug cycles
- [ ] portrait/landscape during preview
- [ ] lock/unlock and background/foreground
- [ ] 30+ minute preview
- [ ] low-power and powered-hub cases
- [ ] Samsung, Pixel, OnePlus, Oppo, Realme, Vivo, Xiaomi/Redmi, Motorola

Later milestones add photo, recording, low-storage, long-recording, share/delete, and transform cases.

