# Air Gesture v2.0 — System Architecture & Security Specification

## 1. Decoupled Dual-Stream Volatile Vision Pipeline
```
Camera2 Hardware ISP (480p / 720p / 1080p / 2K / 4K @ 60 or 120 FPS)
   ├── Stream A (Optional GPU SurfaceTexture) ──► Zero-CPU-Readback Calibration View
   └── Stream B (YUV_420_888 ImageReader, maxImages=2, Lock-Free Drop-Oldest)
          │
          ▼
   [VolatileRoiDownscaler] ──► Single-pass ROI crop + upright rotation + mirror
          │                    into isolated 192×192 or 256×256 ARGB volatile RAM bitmap
          │                    (separate U/V plane strides, zero shared-recycle hazard)
          ▼
   [MediaPipeHandTracker]  ──► SHA-256 RAM-verified hand_landmarker.task (XNNPACK CPU SIMD)
          │
          ▼
   [LandmarkBankOneEuroFilter + HandGeometry Scale Normalizer (L_ref)]
          │
          ▼
   [AirGestureStateMachine] ──► Hysteresis & Return-Stroke Clutch Protected FSM
          │
   ┌──────┴──────────────────────────────────────┐
   ▼                                             ▼
[TouchlessActionDispatcher]               [AirGestureRuntimeBus -> Official PiP & Bento UI]
• AirAccessibilityService (Scroll/Nav)    • In-App Live Dynamic Island Showcase Dock
• SystemVolumeController (Pinch Volume)   • Official Android PiP Floating Dynamic Island (2.2:1)
• RadialAppSlotManager (15 Apps)          • Seamless PiP Resize (1:1) 360° 15-App Radial Wheel
```

## 2. Crash-Proof & Google Play Protect Hardened Architecture (v2.0)
- **Zero Overlay Tapjacking Posture**: `SYSTEM_ALERT_WINDOW`, `TYPE_APPLICATION_OVERLAY`, and `FOREGROUND_SERVICE_SPECIAL_USE` are completely absent from both `AndroidManifest.xml` and `classes.dex`. Floating HUD visuals use Android's official **Picture-in-Picture (`supportsPictureInPicture="true"`)** windowing contract (`2.2:1` Dynamic Island capsule & `1:1` 360° Radial Wheel).
- **Crash-Hardened Foreground Lifecycle**: Runs strictly as `foregroundServiceType="camera"` with `START_NOT_STICKY` (eliminating Android 14–16 background camera FGS restart crash loops), headless-safe `DisplayManager.getDisplay(DEFAULT_DISPLAY)`, XNNPACK SIMD CPU inference (avoiding background `HandlerThread` EGL/GPU driver `SIGABRT`), and `useLegacyPackaging = true` for 16KB memory-page compatibility.
- **Verifiable Zero-Trust Privacy**:
  - `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` are stripped via `tools:node="remove"` (kernel-enforced UID socket block).
  - `accessibility_service_config.xml` enforces `android:canRetrieveWindowContent="false"` and `android:accessibilityEventTypes=""`.
  - `SecuritySelfAuditor` performs live on-device inspection of installed `PackageInfo` permissions, zero-media cache footprint, and SHA-256 `DirectByteBuffer` model integrity.
  - Signed with **APK Signature Scheme v1 + v2 + v3 + v4**.
