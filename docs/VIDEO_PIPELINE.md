# Video Engine Architecture & Real-Time Pipeline

## 1. Frame Processing Invariant (Rule 24)
*Rule 24: Never process every video frame using a heavyweight model.*
Real-time 4K/60fps and 4K/30fps video requires strict frame time budgets:
- At 30 fps: Frame budget is $\le 33.3\text{ ms}$.
- At 60 fps: Frame budget is $\le 16.6\text{ ms}$.

To guarantee zero dropped frames without thermal throttling:
1. **Direct Hardware Surface Encoding**: Intermediate frames are passed directly from `SurfaceTexture` / `HardwareBuffer` to `MediaCodec` without CPU readback or synchronous model inference.
2. **Keyframe Neural Adaptation**: Lightweight neural tone/exposure stabilization executes asynchronously every $N$-th frame (e.g. keyframe interval = 15 frames, $\sim 2\text{ Hz}$).
3. **Hardware Gyro Stabilization**: Electronic image stabilization uses high-frequency IMU gyro telemetry synced with sensor scanlines, running in sub-millisecond hardware/NDK transforms rather than heavyweight neural frame synthesis.

## 2. Dynamic Video Quality Scaling
- **Normal Conditions**: 4K/30fps SDR/HDR10, gyro-assisted stabilization active, keyframe neural color adaptation enabled.
- **Moderate Thermal Throttling**: 4K/30fps with neural keyframe disabled (classical EIS only).
- **Severe Thermal Throttling**: Downgrade to 1080p/30fps to protect hardware and avoid sudden recording termination.
