# ✋ Air Gesture v2.0.0 — 100% On-Device Spatial Touchless Control & PiP Dynamic Island

[![Release](https://img.shields.io/github/v/release/SDWinnie/Air-Gesture?color=00E5FF&label=APK%20Release)](https://github.com/SDWinnie/Air-Gesture/releases/latest)
[![Security](https://img.shields.io/badge/Security-Zero%20Overlay%20%E2%80%A2%20Play%20Protect%20Hardened-00E676)](#%EF%B8%8F-100-privacy-security--google-play-protect-compliance)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Internet-8C62FF)](#%EF%B8%8F-100-privacy-security--google-play-protect-compliance)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%20to%2016%2B%20%26%20HyperOS-1E88E5)](https://developer.android.com/)

ระบบควบคุมสมาร์ทโฟนและแท็บเล็ต Android แบบ **ไร้สัมผัสความหน่วงต่ำ (Ultra-Low Latency Spatial Touchless Control)** ประมวลผลโครงกระดูกมือ 21 จุดด้วย **Edge-AI ภายในเครื่อง 100% (MediaPipe XNNPACK SIMD • Volatile RAM Only)** มาพร้อม **Modern 2026 Spatial Bento Glass UI**, แคปซูลลอยตัว **Official Android Picture-in-Picture (PiP) Dynamic Island HUD**, **วงล้อลัด 15 แอปแบบ 360°** และระบบ **ตรวจสอบความปลอดภัยไบนารีสดในเครื่อง (Live Binary Security Self-Audit)** — **ไม่ต้องรูท (No Root / No ADB)**, **ไม่ใช้สิทธิ์วาดทับหน้าจอ (`Zero SYSTEM_ALERT_WINDOW`)** และ **ไม่มีสิทธิ์เชื่อมต่ออินเทอร์เน็ต (`Zero INTERNET Permission`)**

---

## 📥 ดาวน์โหลดไฟล์ติดตั้ง (.apk)

| เวอร์ชัน | มาตรฐานความปลอดภัย (Binary Audit) | รองรับระบบ | ลิงก์ดาวน์โหลด |
| :--- | :--- | :--- | :--- |
| **v2.0.0 (Latest — Signed v1/v2/v3/v4)** | Zero Internet • Zero `SYSTEM_ALERT_WINDOW` • Zero `SPECIAL_USE` • `canRetrieveWindowContent="false"` | Android 8.0 – **Android 14 / 15 / 16+ & Xiaomi HyperOS 2/3** | [👉 **ดาวน์โหลด AirGesture-v2.0.0.apk**](https://github.com/SDWinnie/Air-Gesture/releases/download/v2.0.0/AirGesture-v2.0.0.apk) |

---

## 🚀 มีอะไรใหม่ใน v2.0.0 (Major Stability, Security & Modern UI Upgrade)

1. **🛠️ Crash-Proof Vision & Service Engine (แก้ปัญหาแอปปิดตัวเอง 100%):**
   * แก้บั๊ก `MPImage.close()` รีไซเคิล `Bitmap` บัฟเฟอร์ร่วมใน `VolatileRoiDownscaler` ที่ทำให้เธรดกล้องเด้งในเฟรมที่ 2 พร้อมแยก Stride ระนาบ `U`/`V` รองรับชิปกล้องทุกรุ่น
   * เปลี่ยนไปใช้ **XNNPACK Multi-Thread CPU SIMD Delegate** แทน Background OpenGL/EGL Context ที่เสี่ยงเกิด Native `SIGABRT` บนไดรเวอร์ GPU เมื่อรันใน Service เบื้องหลัง
   * ใช้ `START_NOT_STICKY` + `foregroundServiceType="camera"` มาตรฐานเดียว และเปิด `useLegacyPackaging = true` รองรับอุปกรณ์ **16KB Page Size** บน Android 15/16+
2. **🛡️ Google Play Protect & HyperOS Security Hardening (ปลอดภัยตรวจสอบได้จริง):**
   * **ตัดสิทธิ์ `SYSTEM_ALERT_WINDOW` (`TYPE_APPLICATION_OVERLAY`) และ `FOREGROUND_SERVICE_SPECIAL_USE` ออกจากไบนารี APK 100%** เพื่อไม่ให้เข้าข่ายพฤติกรรม Overlay + Accessibility Tapjacking Heuristic ของ Google Play Protect
   * เปลี่ยนมาใช้ **Official Android Picture-in-Picture (PiP) Floating Dynamic Island** ที่ปลอดภัยตามมาตรฐาน OS และช่วยให้วงล้อ 15 แอปเปิดแอปบน Xiaomi HyperOS ได้โดยไม่ติดบล็อก Background Activity
   * เพิ่มหน้าต่าง **🛡️ Live Security Self-Audit** ตรวจสอบสิทธิ์ของไฟล์ APK ที่ติดตั้งจริง พื้นที่แคช (`0 ไฟล์ภาพ`) และแฮช SHA-256 ของโมเดล AI แบบเรียลไทม์ พร้อมลงนามครบทั้ง **APK Signature Scheme v1, v2, v3 และ v4**
3. **✨ Modern 2026 Spatial Bento Glass UI/UX:**
   * ดีไซน์ใหม่ทั้งหมดในโทน **Obsidian Spatial Glass & Bento Grid** แบ่งเป็น 3 แท็บ (`⚡ ควบคุมหลัก`, `🎥 กล้อง & AI`, `🎡 วงล้อ 15 แอป`)
   * มี **Live Dynamic Island Showcase Dock** แสดงโครงกระดูกมือ 21 จุดและแถบเสียงสดในแอป, สไลเดอร์ปรับความไว Scroll/Volume และ **ตาราง Bento 3 คอลัมน์** พร้อมวงล้อ 360° แบบโต้ตอบสด

---

## ✨ 5 ท่าทางสั่งงานอัจฉริยะ (Gesture Mapping & Dynamic Island Specs)

ระบบใช้ตัวกรองความถี่ปรับตัว **1€ (One-Euro) Temporal Filter** ร่วมกับการหารสเกลความยาวฝ่ามือ ($L_{\text{ref}} = \|\mathbf{p}_0 - \mathbf{p}_9\|_2$) ทำให้สั่งงานแม่นยำทั้งระยะใกล้ `25 ซม.` จนถึงระยะไกล `1.5 เมตร`:

1. **✋ แบมือปลุก Dynamic Island (Palm Detection & HUD Activation):**
   * ยกฝ่ามือขึ้นหน้ากล้อง แคปซูล **Dynamic Island** จะตื่นขึ้นพร้อมแสดง **โครงกระดูกมือ 21 ข้อแบบสด (Live Hand Silhouette)** และพักตัวเองอัตโนมัติเมื่อเอามือออกครบ 3 วินาที
2. **↕️ ปัดฝ่ามือขึ้น / ลง (Smooth Native Vertical Scroll):**
   * กวาดฝ่ามือแนวตั้งเพื่อเลื่อนหน้าฟีด (Chrome, YouTube, TikTok, Reels, เอกสาร) อย่างนุ่มนวลผ่าน `dispatchGesture()` พร้อมระบบ **Return-Stroke Clutch Protection** ป้องกันหน้าจอเลื่อนย้อนกลับจังหวะชักมือตั้งหลัก
3. **↔️ ปัดฝ่ามือซ้าย / ขวา (Horizontal Back & Task Switch):**
   * ปัดซ้ายเพื่อสั่ง **ย้อนกลับ (`GLOBAL_ACTION_BACK`)** และปัดขวาเพื่อเปิด **สลับแอป (`GLOBAL_ACTION_RECENTS` / Quick-Switch แอปก่อนหน้า)**
4. **🤏 ท่ามือจีบปรับระดับเสียง (Pinch Gesture — Smooth Volume Pill Slider):**
   * แตะปลายนิ้วโป้งและนิ้วชี้เข้าหากันค้างไว้แล้วขยับขึ้น/ลงเพื่อเพิ่ม-ลดเสียงหลักของเครื่องอย่างละเอียด โดย Dynamic Island จะแปลงร่างเป็น **แถบสไลเดอร์ระดับเสียง (Volume Pill Slider)** ทันที
5. **✊ ท่ากำมือเรียกวงล้อ 15 แอป (Fist Gesture — 360° 15-App Radial Pie Selector):**
   * กำมือค้างสั้นๆ เพื่อเรียก **วงล้อลัด 360 องศา (15 ช่องแอป • ช่องละ 24°)** (หน้าต่างลอย PiP จะขยายสัดส่วนเป็น `1:1` อัตโนมัติ)
   * เอียงองศาข้อมือไปยังแอปที่ต้องการ (มีระบบ Angular Hysteresis `±2.5°` กันช่องกระพริบ) แล้ว **คลายกำปั้นออก** เพื่อเปิดแอปนั้นทันที!

---

## 🛡️ 100% Privacy, Security & Binary Permission Footprint

สามารถตรวจสอบสิทธิ์ในไบนารี APK ได้โดยตรงด้วย `aapt2 dump permissions AirGesture-v2.0.0.apk` ซึ่งมีเพียงสิทธิ์มาตรฐานขั้นต่ำเท่านั้น:
* `android.permission.CAMERA` — ประมวลผลเฉพาะในหน่วยความจำชั่วคราว (`192×192` / `256×256` Volatile RAM)
* `android.permission.FOREGROUND_SERVICE` & `android.permission.FOREGROUND_SERVICE_CAMERA` — บริการกล้องมาตรฐาน Android 14–16+
* `android.permission.POST_NOTIFICATIONS` — แจ้งสถานะการทำงานอย่างโปร่งใสพร้อมปุ่ม พัก/ปิด ทันที
* **ไม่มี** `android.permission.INTERNET` หรือ `ACCESS_NETWORK_STATE` (ตัดช่องทางส่งข้อมูลออกนอกเครื่องระดับ Linux Kernel UID)
* **ไม่มี** `android.permission.SYSTEM_ALERT_WINDOW` (ป้องกันความเสี่ยง Overlay Tapjacking 100%)
* **ปิดถาวร** `android:canRetrieveWindowContent="false"` ใน `accessibility_service_config.xml` (อ่านข้อความหรือรหัสผ่านบนหน้าจอไม่ได้ 100%)

---

## 🛠️ โครงสร้างโปรเจกต์ & วิธีบิลด์จาก Source Code

```text
air-gesture/
├── app/src/main/
│   ├── assets/hand_landmarker.task          # Bundled float16 Edge-AI Hand Model (SHA-256 Verified)
│   ├── AndroidManifest.xml                  # Zero INTERNET, Zero Overlay, Official PiP & Camera FGS
│   ├── res/xml/
│   │   ├── accessibility_service_config.xml # canPerformGestures=true, canRetrieveWindowContent=false
│   │   └── network_security_config.xml      # Zero cleartext & empty trust-anchors
│   └── java/com/sdwinnie/airgesture/
│       ├── engine/                          # 1€ Filter, Scale Normalizer, Gesture State Machine
│       ├── vision/                          # Camera2 Dual-Stream, Volatile ROI Downscaler, XNNPACK Tracker
│       ├── input/                           # Touchless Dispatcher, Smooth Volume, 15-App Radial Manager
│       ├── overlay/                         # Dynamic Island Capsule, PiP Floating HUD & 360° Radial Wheel
│       ├── service/                         # Crash-Hardened Camera FGS, AccessibilityService, QS Tile
│       ├── security/                        # Live Binary Self-Auditor, SHA-256 RAM Verifier, Prefs
│       └── ui/                              # Modern 2026 Spatial Bento Dashboard & 3-Column Slot Grid
└── app/src/test/                            # JUnit 4 Gesture Physics & FSM Trajectory Test Suite
```

### คำสั่งทดสอบและบิลด์ APK
```bash
git clone https://github.com/SDWinnie/Air-Gesture.git
cd Air-Gesture
./gradlew clean testReleaseUnitTest lintRelease assembleRelease
```

---

## 📄 ใบอนุญาต (License)
MIT License — Copyright (c) 2026 SDWinnie
