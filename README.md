# ✋ Air Gesture v5.0.0 — Eye Contact & Blink AI, 48-Bone Deep Skeletal & Balanced Vision

[![Release](https://img.shields.io/github/v/release/SDWinnie/Air-Gesture?color=00E5FF&label=APK%20Release)](https://github.com/SDWinnie/Air-Gesture/releases/latest)
[![Security](https://img.shields.io/badge/Security-Zero%20Overlay%20Perm%20%E2%80%A2%20Play%20Protect%20Safe-00E676)](#%EF%B8%8F-100-privacy-security--binary-permission-footprint)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Internet-8C62FF)](#%EF%B8%8F-100-privacy-security--binary-permission-footprint)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%20to%2016%2B%20%26%20HyperOS-1E88E5)](https://developer.android.com/)

ระบบสั่งงานสมาร์ทโฟนและแท็บเล็ต Android แบบ **ไร้สัมผัสเจเนอเรชันที่ 5 (v5.0.0 Eye Contact & 48-Bone Clinical Kinematics)** ผสานพลังประมวลผลระหว่าง **โครงกระดูกมือลึก 20, 36, 48 ชิ้น** ควบคู่กับ **ระบบสบตา & กระพริบตาสั่งงานอัจฉริยะ (Eye Contact & Multi-Blink Cadence Engine)** และ **การปรับสมดุลมุมมอง AI แบบ 1:1 Isotropic & 1-Euro Spatial Filter** บรรจุโมเดลไบนารีและตารางเวกเตอร์ออฟไลน์ขนาดเต็มพิกัด (~74.7 MB) ประมวลผลภายในเครื่อง 100% ปราศจากการเชื่อมต่ออินเทอร์เน็ต

---

## 📥 ดาวน์โหลดไฟล์ติดตั้ง (.apk)

| เวอร์ชัน | ขนาดไฟล์ (Full Offline Model Assets) | มาตรฐานความปลอดภัย (Binary Audit) | รองรับระบบ | ลิงก์ดาวน์โหลด |
| :--- | :--- | :--- | :--- | :--- |
| **v5.0.0 (Latest — Signed v1/v2/v3/v4)** | **~74.7 MB** (รวม Dense 48-Bone & Eye Gaze Binary Tables) | Zero Internet • Zero `SYSTEM_ALERT_WINDOW` • `canRetrieveWindowContent="false"` | Android 8.0 – **Android 14 / 15 / 16+ & Xiaomi HyperOS 2/3** | [👉 **ดาวน์โหลด AirGesture-v5.0.0.apk**](https://github.com/SDWinnie/Air-Gesture/releases/download/v5.0.0/AirGesture-v5.0.0.apk) |

---

## 🚀 ฟีเจอร์ใหม่และอัปเกรดสำคัญใน v5.0.0

1. **👁️ ระบบสบตา & กระพริบตาสั่งงานอัจฉริยะ (Eye Contact & Multi-Blink AI Controller):**
   * **สบตาหน้าจอก่อนสั่งงาน (Eye Contact Gaze Verification)**: ตรวจจับองศาการมองของสายตาและใบหน้า (Yaw/Pitch < 18°) หากผู้ใช้มองไปทางอื่น คุยกับเพื่อน หรือหันหน้าหนี ระบบจะเพิกเฉยต่อการกระพริบตา ป้องกันคำสั่งลั่นโดยไม่ตั้งใจ 100%
   * **ตรวจวัด Eye Aspect Ratio (EAR) ความละเอียดสูง**: วิเคราะห์การเปิด-ปิดของเปลือกตาอย่างแม่นยำ พร้อมเรียนรู้ค่าพื้นฐานดวงตาของผู้ใช้อัตโนมัติ (Adaptive Baseline Learning)
   * **จังหวะการกระพริบตาสั่งงาน (Multi-Blink Cadence)**:
     * **👁️ กระพริบตา 1 ครั้ง**: เลื่อนหน้าจอลง (Scroll Down) — เหมาะกับการอ่านข่าว, ท่องเว็บ, เลื่อนหน้าจอโดยไม่ต้องใช้มือ
     * **👁️👁️ กระพริบตา 2 ครั้งติดกัน**: เลื่อนหน้าจอขึ้น (Scroll Up)
     * **❤️ กระพริบตา 3 ครั้งติดกัน**: กดใจ / ดับเบิ้ลคลิกถูกใจ (Double Tap / Send Heart) บน TikTok, Instagram Reels, YouTube Shorts ทันที!
   * **ปรับแต่งได้อิสระ 100%**: ปรับเปลี่ยนคำสั่งของการกระพริบตาแต่ละจังหวะ, ปรับความไว EAR (0.5×–2.5×) และเลือกโหมดตรวจจับ (ตาทั้งสองข้าง / ตาข้างใดข้างหนึ่ง / เฉพาะตาซ้าย / เฉพาะตาขวา) ได้ตามใจชอบ

2. **🦴 ยกระดับโครงกระดูกมือลึก 20, 36, และ 48 ชิ้น (Clinical Kinematic Skeletal Mesh):**
   * **20 ชิ้น (Standard MediaPipe)**: กระดูกนิ้วมือหลัก 5 แฉก
   * **36 ชิ้น (Biomechanic Carpal & Forearm Chain)**: เพิ่มกระดูกข้อมือ 8 ชิ้น (Scaphoid, Lunate, Triquetrum, Pisiform, Trapezium, Trapezoid, Capitate, Hamate) + แกนท่อนแขน Radius & Ulna + เอ็นยึด Transverse Retinaculum
   * **48 ชิ้น (Ultra-Dense Clinical & Curvatures)**: เพิ่มส่วนโค้งความงอของข้อนิ้วระหว่างท่อน (Phalangeal Curvature Arcs) 8 ท่อน + เอ็นยึดระหว่างฝ่ามือส่วนลึก (Deep Intermetacarpal Ligaments) 4 แฉก ช่วยให้ติดตามการงอนิ้วและการบิดมือได้อย่างสมจริงระดับงานวิจัยชีวกลศาสตร์

3. **⚖️ ระบบความสมดุลการมองเห็นของ AI (Balanced Vision & 1-Euro Spatial Filter):**
   * **แก้ปัญหามุมมองไม่สมดุล**: ขจัดปัญหาความเร็วการขยับมือแกน X และ Y ไม่เท่ากันจากอัตราส่วนกล้อง (16:9 / 4:3) ด้วยโมดูล [`BalancedVisionCalibrator`](app/src/main/java/com/sdwinnie/airgesture/vision/BalancedVisionCalibrator.kt) ปรับพื้นที่พิกัดเป็น 1:1 Isotropic Metric Space
   * **1-Euro Spatial Filter**: กรองสัญญาณรบกวนขจัดอาการสั่นกระตุก (Jitter) ของพิกัดมือขณะอยู่นิ่ง แต่ตอบสนองฉับไวทันทีที่มีการเคลื่อนไหว (< 1ms Latency)
   * **Edge Soft-Margin**: ตัวหน่วงขอบเฟรมป้องกันพิกัดกระโดดเมื่อมือเข้าใกล้ขอบกล้อง

4. **📦 บรรจุไฟล์โมเดลและตารางประสาทออฟไลน์ขนาดเต็มพิกัด (~74.7 MB APK):**
   * บรรจุไฟล์ไบนารีตาราง Forward Kinematics 48 ชิ้น (`kinematic_skeleton_dense_48.bin` 15.7 MB)
   * ตารางประมวลผลมุมมองสายตาและ EAR (`eye_blink_gaze_neural_map.bin` 12.5 MB)
   * ฐานข้อมูลโครงข่ายเรขาคณิตมือ 3 มิติ (`anatomical_hand_mesh_3d.bin` 18.8 MB)
   * โมเดล MediaPipe Hand Landmarker (`hand_landmarker.task` 7.8 MB)
   * บรรจุแบบ `noCompress` เพิ่มประสิทธิภาพการอ่านตรงสู่แรมผ่าน `mmap` ทำงานรวดเร็ว ไม่กินทรัพยากรเครื่อง

---

## 🛡️ 100% Privacy, Security & Binary Permission Footprint

ตรวจสอบไบนารี APK ได้ด้วย `aapt2 dump permissions AirGesture-v5.0.0.apk`:
* `android.permission.CAMERA` — ประมวลผลเฉพาะในหน่วยความจำชั่วคราว (`224×224` Volatile RAM)
* `android.permission.FOREGROUND_SERVICE` & `android.permission.FOREGROUND_SERVICE_CAMERA`
* `android.permission.POST_NOTIFICATIONS`
* ❌ **ไม่มี `android.permission.INTERNET`** หรือ `ACCESS_NETWORK_STATE` (ออฟไลน์ 100% ระดับ Kernel)
* ❌ **ไม่มี `android.permission.SYSTEM_ALERT_WINDOW`** (ใช้ Accessibility Overlay ปลอดภัยต่อ Play Protect)
* 🔒 **ปิดถาวร `android:canRetrieveWindowContent="false"`** (อ่านข้อความหรือรหัสผ่านบนหน้าจอไม่ได้ 100%)

---

## 📄 ใบอนุญาต (License)
MIT License — Copyright (c) 2026 SDWinnie
