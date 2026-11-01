# Air Gesture v3.0 — System Architecture & Ergonomic Vision Specification

## 1. Isotropic 1:1 Single-Hand Vision Pipeline
```
Camera2 Hardware ISP (480p / 720p / 1080p / 2K / 4K @ 60 or 120 FPS)
   ├── Stream A (Optional GPU SurfaceTexture) ──► Zero-CPU-Readback Calibration View
   └── Stream B (YUV_420_888 ImageReader, maxImages=2)
          │
          ▼
   [VolatileRoiDownscaler v3.0] ──► Isotropic 1:1 Letterbox into 224×224 or 256×256 volatile RAM
          │                         (Zero aspect-ratio squashing, zero double-ROI oscillation)
          ▼
   [MediaPipeHandTracker v3.0]  ──► Strict numHands=1, conf >= 0.64, physical Left/Right normalization
          │
          ▼
   [SingleHandLockTracker + HandGeometry Anatomical Plausibility & PIP Joint-Angle Classifier]
          │
          ▼
   [AirGestureStateMachine v3.0] ──► Configurable MediaPipe Canonical Postures + Spatial Virtual Joystick
          │
   ┌──────┴──────────────────────────────────────────────┐
   ▼                                                     ▼
[TouchlessActionDispatcher]                     [AccessibilityScreenOverlay (TYPE_ACCESSIBILITY_OVERLAY)]
• Smooth Vertical Scroll & Back/Recents         • Top-Edge Dynamic Island & Directional Volume Status Pill
• Directional Volume (🔉 ลดเสียง ▼ / 🔊 เพิ่ม ▲)  • Centered On-Screen 360° Circular App Wheel (6/8/12/15 Apps)
• 6/8/12/15-App Circular Launcher               • Customizable Palm Graphics & 5 Neon Themes (Zero SYSTEM_ALERT_WINDOW)
```
