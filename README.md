# ✋ Air Gesture v1.0.0 — 100% On-Device Touchless Control & Dynamic Island HUD

[![Release](https://img.shields.io/github/v/release/SDWinnie/Air-Gesture?color=00E5FF&label=APK%20Release)](https://github.com/SDWinnie/Air-Gesture/releases/latest)
[![Security](https://img.shields.io/badge/Security-Google%20Play%20Protect%20Safe-00E676)](https://developers.google.com/android/play-protect)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Internet-7C4DFF)](#%EF%B8%8F-100-privacy-security--google-play-protect-compliance)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%20to%2016%2B-1E88E5)](https://developer.android.com/)

ระบบควบคุมสมาร์ทโฟนและแท็บเล็ต Android แบบ **ไร้สัมผัสความหน่วงต่ำ (Ultra-Low Latency Touchless Control)** ประมวลผลโครงกระดูกมือ 21 จุดด้วย **Edge-AI ภายในเครื่อง 100% (MediaPipe Hand Landmarker • Volatile RAM Only)** พร้อมหน้าต่าง **Dynamic Island HUD** และ **วงล้อลัด 15 แอปแบบ 360° (Radial Pie Menu)** โดย **ไม่ต้องรูท (No Root / No ADB)** และ **ไม่มีสิทธิ์เชื่อมต่ออินเทอร์เน็ต (`Zero INTERNET Permission`)**

---

## 📥 ดาวน์โหลดไฟล์ติดตั้ง (.apk)

| เวอร์ชัน | มาตรฐานความปลอดภัย | รองรับระบบ | ลิงก์ดาวน์โหลด |
| :--- | :--- | :--- | :--- |
| **v1.0.0 (Latest — Signed v2/v3/v4)** | Zero Internet • Zero Disk Write • `canRetrieveWindowContent="false"` | Android 8.0 – **Android 14 / 15 / 16+** (Phones & Tablets) | [👉 **ดาวน์โหลด AirGesture-v1.0.0.apk**](https://github.com/SDWinnie/Air-Gesture/releases/download/v1.0.0/AirGesture-v1.0.0.apk) |

---

## ✨ 5 ท่าทางสั่งงานอัจฉริยะ (Gesture Mapping & Dynamic Island Specs)

ระบบใช้ตัวกรองความถี่ปรับตัว **1€ (One-Euro) Temporal Filter** ร่วมกับการหารสเกลความยาวฝ่ามือ ($L_{\text{ref}} = \|\mathbf{p}_0 - \mathbf{p}_9\|_2$) ทำให้สั่งงานแม่นยำทั้งระยะใกล้ `25 ซม.` จนถึงระยะไกล `1.5 เมตร`:

1. **✋ แบมือปลุก Dynamic Island (Palm Detection & HUD Activation):**
   * ยกฝ่ามือขึ้นหน้ากล้อง แคปซูล **Dynamic Island** ขอบบนหน้าจอจะตื่นขึ้นพร้อมแสดง **โครงกระดูกมือ 21 ข้อแบบสด (Live Hand Silhouette)** และพักตัวเองอัตโนมัติเมื่อเอามือออกครบ 3 วินาที
2. **↕️ ปัดฝ่ามือขึ้น / ลง (Smooth Native Vertical Scroll):**
   * กวาดฝ่ามือแนวตั้งเพื่อเลื่อนหน้าฟีด (Chrome, YouTube, TikTok, Reels, เอกสาร) อย่างนุ่มนวลผ่าน `dispatchGesture()` พร้อมระบบ **Return-Stroke Clutch Protection** ป้องกันหน้าจอเลื่อนย้อนกลับจังหวะชักมือตั้งหลัก
3. **↔️ ปัดฝ่ามือซ้าย / ขวา (Horizontal Back & Task Switch):**
   * ปัดซ้ายเพื่อสั่ง **ย้อนกลับ (`GLOBAL_ACTION_BACK`)** และปัดขวาเพื่อเปิด **สลับแอป (`GLOBAL_ACTION_RECENTS` / Quick-Switch แอปก่อนหน้า)** ปรับเปลี่ยนโหมดได้ในหน้าตั้งค่า
4. **🤏 ท่ามือจีบปรับระดับเสียง (Pinch Gesture — Smooth Volume Pill Slider):**
   * แตะปลายนิ้วโป้งและนิ้วชี้เข้าหากันค้างไว้แล้วขยับขึ้น/ลงเพื่อเพิ่ม-ลดเสียงหลักของเครื่องอย่างละเอียด โดย Dynamic Island จะแปลงร่างเป็น **แถบสไลเดอร์ระดับเสียง (Volume Pill Slider)** ทันที
5. **✊ ท่ากำมือเรียกวงล้อ 15 แอป (Fist Gesture — 360° 15-App Radial Pie Selector):**
   * กำมือค้างสั้นๆ เพื่อเรียก **วงล้อลัด 360 องศา (15 ช่องแอป • ช่องละ 24°)** กลางหน้าจอ
   * เอียงองศาข้อมือไปยังแอปที่ต้องการ (มีระบบ Angular Hysteresis `±2.5°` กันช่องกระพริบ) แล้ว **คลายกำปั้นออก** เพื่อเปิดแอปนั้นทันที! (ตั้งค่าเลือกแอปทั้ง 15 ช่องได้อย่างอิสระ)

---

## ⚡ Decoupled Dual-Stream Camera Pipeline & Thermal Guard

* **รองรับความละเอียด**: `480p`, `720p`, `1080p`, `2K QHD`, `4K UHD` และเป้าหมายเฟรมเรต **`60 FPS` / `120 FPS`**
* **แยกสายข้อมูลภาพ 2 ท่ออิสระ (Decoupled Dual-Stream):**
  * ภาพความละเอียดสูง (`2K`/`4K`) **ไม่ถูกส่งเข้าโมเดล AI โดยตรง**
  * เธรดประมวลผลเฉพาะทางจะครอปเฉพาะกรอบมือ (**Dynamic ROI Tracking**) แล้วย่อลงบัฟเฟอร์ขนาดจิ๋ว **`192×192` หรือ `256×256` RGB ในหน่วยความจำชั่วคราว (Volatile RAM)** แบบ Single-Pass Zero-Allocation ก่อนส่งให้ MediaPipe
* **Eco-Sentry & Hardware Thermal Throttling:** ลดรอบตรวจจับเหลือ `15 FPS` อัตโนมัติเมื่อไม่มีมือในเฟรม และเชื่อมกับ `PowerManager.OnThermalStatusChangedListener` เพื่อลดภาระอัตโนมัติเมื่อเครื่องเริ่มอุ่น

---

## 🛡️ 100% Privacy, Security & Google Play Protect Compliance

1. **Zero Network Egress (ตัดสิทธิ์อินเทอร์เน็ตระดับ Kernel):**
   * ไม่มีการประกาศ `android.permission.INTERNET` ใน [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) และบล็อกทุกช่องทางใน [`network_security_config.xml`](app/src/main/res/xml/network_security_config.xml)
2. **Zero Disk Persistence (ไม่แตะพื้นที่จัดเก็บข้อมูลถาวร 100%):**
   * เฟรมภาพกล้องประมวลผลใน Volatile RAM Ring Buffer (`maxImages=2`) และถูกล้างค่าเป็นศูนย์ (`wipeVolatileMemory()`) ทันทีที่พักหรือปิดบริการ
3. **Hardened Accessibility (`canRetrieveWindowContent="false"`):**
   * ใน [`accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml) ปิดสิทธิ์การอ่านเนื้อหาบนหน้าจออย่างถาวร แอปจึง **ไม่สามารถอ่านข้อความ รหัสผ่าน แชท หรือหน้าจอธนาคารใดๆ ได้**
4. **SHA-256 In-RAM Model Verification & APK Signature v2/v3/v4:**
   * ตรวจสอบแฮช SHA-256 ของโมเดล `hand_landmarker.task` ใน `DirectByteBuffer` ก่อนรันทุกครั้ง ปราศจาก Dynamic DEX Loading และลงนาม APK ครบทั้ง **Scheme v2, v3 และ v4**
5. **Android 14 / 15 / 16+ Native Lifecycle:**
   * ทำงานผ่าน `foregroundServiceType="camera|specialUse"` พร้อมระบบ **Smart Camera Yield & Auto-Resume** คืนกล้องให้แอปถ่ายรูปหลักทันทีเมื่อผู้ใช้เปิดกล้อง และกลับมาทำงานต่ออัตโนมัติเมื่อปิดแอปกล้อง

---

## 🛠️ โครงสร้างโปรเจกต์ & วิธีบิลด์จาก Source Code

```text
air-gesture/
├── app/src/main/
│   ├── assets/hand_landmarker.task          # Bundled float16 Edge-AI Hand Model (Offline 100%)
│   ├── AndroidManifest.xml                  # Zero INTERNET, camera|specialUse FGS (Target SDK 36)
│   ├── res/xml/
│   │   ├── accessibility_service_config.xml # canPerformGestures=true, canRetrieveWindowContent=false
│   │   └── network_security_config.xml      # Zero cleartext & empty trust-anchors
│   └── java/com/sdwinnie/airgesture/
│       ├── engine/                          # 1€ Filter, Scale Normalizer, Gesture State Machine
│       ├── vision/                          # Camera2 Dual-Stream, Volatile ROI Downscaler, Thermal Guard
│       ├── input/                           # Touchless Dispatcher, Smooth Volume, 15-App Radial Manager
│       ├── overlay/                         # Dynamic Island Capsule & 360° 15-App Radial Pie View
│       ├── service/                         # ForegroundService, AccessibilityService, Quick Settings Tile
│       ├── security/                        # SHA-256 RAM Model Verifier, Zero-Leak Logger, Prefs
│       └── ui/                              # Control Center, Prominent Disclosure, 15-Slot Configurator
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
