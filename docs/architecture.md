# Air Gesture — System Architecture & Engineering Specification

## 1. Decoupled Dual-Stream Volatile Vision Pipeline
```
Camera2 Hardware ISP (480p / 720p / 1080p / 2K / 4K @ 60 or 120 FPS)
   ├── Stream A (Optional GPU SurfaceTexture) ──► Zero-CPU-Readback Calibration View
   └── Stream B (YUV_420_888 ImageReader, maxImages=2, Lock-Free Drop-Oldest)
          │
          ▼
   [VolatileRoiDownscaler] ──► Single-pass ROI crop + upright rotation + mirror
          │                    into pre-allocated 192×192 or 256×256 ARGB RAM buffer
          ▼
   [MediaPipeHandTracker]  ──► SHA-256 RAM-verified hand_landmarker.task (GPU / CPU SIMD)
          │
          ▼
   [LandmarkBankOneEuroFilter + HandGeometry Scale Normalizer (L_ref)]
          │
          ▼
   [AirGestureStateMachine] ──► Hysteresis & Return-Stroke Clutch Protected FSM
          │
   ┌──────┴───────────────────────────────┐
   ▼                                      ▼
[TouchlessActionDispatcher]        [OverlayHudManager]
• AirAccessibilityService          • Top-Edge Dynamic Island Capsule
• SystemVolumeController           • Volume Pill Slider (Pinch)
• RadialAppSlotManager (15 Apps)   • 360° 15-App Radial Pie Menu (Fist)
```

## 2. Android 14, 15 & 16+ OS Enforcement Compliance
- **Foreground Service Types**: Runs as `camera|specialUse` with explicit `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` declaration and automatic `CameraDevice.StateCallback.onDisconnected` + `AvailabilityCallback` yield/resume when foreground camera apps open.
- **Untrusted-Touch Passthrough Compliance**: Both `TYPE_APPLICATION_OVERLAY` windows use compact `WRAP_CONTENT` bounds, `FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE`, and `alpha <= InputManager.maximumObscuringOpacityForTouch`, guaranteeing zero tap-jacking touch occlusion on Android 12–16+.
- **Play Protect Privacy Posture**:
  - `android.permission.INTERNET` is **completely omitted** from `AndroidManifest.xml` (kernel UID socket block) alongside a zero-cleartext `network_security_config.xml`.
  - `accessibility_service_config.xml` sets `android:canRetrieveWindowContent="false"` and `android:accessibilityEventTypes=""`, proving statically that Air Gesture cannot read screen text, passwords, or UI hierarchies.
  - Bundled `hand_landmarker.task` is SHA-256 verified inside a `DirectByteBuffer` in volatile RAM prior to native execution.
