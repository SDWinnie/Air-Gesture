# ✋ Air Gesture v4.0.0 — Touchless Air Tap, 20-Bone Hand Skeleton & Zero-GC 30–120 FPS Pipeline

[![Release](https://img.shields.io/github/v/release/SDWinnie/Air-Gesture?color=00E5FF&label=APK%20Release)](https://github.com/SDWinnie/Air-Gesture/releases/latest)
[![Security](https://img.shields.io/badge/Security-Zero%20Overlay%20Perm%20%E2%80%A2%20Play%20Protect%20Safe-00E676)](#%EF%B8%8F-100-privacy-security--binary-permission-footprint)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Internet-8C62FF)](#%EF%B8%8F-100-privacy-security--binary-permission-footprint)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%20to%2016%2B%20%26%20HyperOS-1E88E5)](https://developer.android.com/)

ระบบสั่งงานสมาร์ทโฟนและแท็บเล็ต Android แบบ **ไร้สัมผัสเจเนอเรชันที่ 4 (v4.0.0 Touchless Air Tap & Deep Hand Skeleton)** ประมวลผลโครงกระดูกมือด้วย **Edge-AI ภายในเครื่อง 100% (Zero-GC Volatile RAM • Zero Cloud)** รองรับการ **เคาะจอในอากาศ (Touchless Screen Knock / Air Tap)**, **วิเคราะห์กระดูกมือ 20 ชิ้นตามกายวิภาคศาสตร์จริง**, **รันนิ่งลื่นไหลเสถียรที่ 30 FPS / 60 FPS / 120 FPS**, **เป้าเล็ง Air Cursor บนจอพร้อมคลื่น Shockwave**, และ **สตูดิโอกำหนดค่าอิสระเต็มรูปแบบสำหรับผู้ใช้ (Zero Hardcoded Restrictions)**

---

## 📥 ดาวน์โหลดไฟล์ติดตั้ง (.apk)

| เวอร์ชัน | มาตรฐานความปลอดภัย (Binary Audit) | รองรับระบบ | ลิงก์ดาวน์โหลด |
| :--- | :--- | :--- | :--- |
| **v4.0.0 (Latest — Signed v1/v2/v3/v4)** | Zero Internet • Zero `SYSTEM_ALERT_WINDOW` • `canRetrieveWindowContent="false"` | Android 8.0 – **Android 14 / 15 / 16+ & Xiaomi HyperOS 2/3** | [👉 **ดาวน์โหลด AirGesture-v4.0.0.apk**](https://github.com/SDWinnie/Air-Gesture/releases/download/v4.0.0/AirGesture-v4.0.0.apk) |

---

## 🚀 ฟีเจอร์ใหม่และอัปเกรดสำคัญใน v4.0.0

1. **👆 ระบบเคาะจอในอากาศ (Touchless Screen Knock / Air Tap):**
   * **จดจ่อเคาะอากาศแทนการแตะจอจริง**: ตรวจจับความเร่งและการเคลื่อนที่ตามแกนความลึก ($-\Delta z / \Delta t$) ของปลายนิ้วชี้หรือการเคาะฝ่ามือเข้าหากล้อง เสมือนเคาะบนบานกระจกใสตรงหน้าจอ
   * **ทำงานร่วมกับ Air Cursor Reticle**: เป้าเล็งวงกลมเรืองแสงลอยติดตามการชี้ของมือ พร้อมแอนิเมชันคลื่นกระแทกเรืองแสง (Shockwave Ripple) เมื่อเคาะจอสำเร็จ
   * **จำลองการคลิกแบบเนทีฟ**: สั่งคลิกหน้าจอจริงผ่าน `AccessibilityService.dispatchGesture()` โดยตรง หรือสั่ง หยุด/เล่นวิดีโอ (YouTube, TikTok, Reels, Facebook Watch), เคาะ 2 ครั้ง (Double Tap), หรือคลิกตรงจุดเป้าเล็ง
   * **3 รูปแบบการเคาะให้เลือก**: `เคาะปลายนิ้วชี้ (Index Knock)`, `จีบนิ้วคลิกเร็ว (Pinch Quick Tap แบบ Vision Pro)`, หรือ `เคาะฝ่ามือ/สันมือ (Palm Knock)` พร้อมตัวเลื่อนปรับระดับความไว (Sensitivity Slider: 0.5× ถึง 2.5×)

2. **⚡ ปลดล็อกประสิทธิภาพ 30 FPS / 60 FPS / 120 FPS นิ่งสนิท (Zero-GC Downscaler):**
   * **แก้ปัญหาเฟรมเรตร่วงและอาการกระตุก**: ปรับสถาปัตยกรรม [`VolatileRoiDownscaler`](app/src/main/java/com/sdwinnie/airgesture/vision/VolatileRoiDownscaler.kt) โดยตัด JNI loop 50,000 ครั้งออกทั้งหมด และเปลี่ยนเป็น Native Memory Copy ผ่าน `ByteBuffer.get(scratchByteArray)` ควบคู่กับการนำ Bitmap วัตถุเดิมมาใช้ซ้ำ (`setPixels`) ไม่มีการจองหน่วยความจำใหม่และไม่มีขยะสะสมใน Garbage Collection (GC Overhead = 0%)
   * **ตัวเลือก Target FPS อิสระ**: เลือกปรับความเร็วได้อิสระตามความเหมาะสมของแบตเตอรี่และความแรงของเครื่อง (`30 FPS (ประหยัดแบตเตอรี่)`, `60 FPS (สมูทมาตรฐาน)`, `120 FPS (ความหน่วงต่ำระดับโปร)`)
   * **ฮาร์ดแวร์เร่งความเร็ว AI (GPU GLES / CPU XNNPACK)**: สลับการประมวลผลโมเดลมือระหว่าง GPU Acceleration และ CPU ได้ด้วยตนเอง พร้อมเปิดดู **Live FPS Counter บนหน้าจอแบบเรียลไทม์**

3. **🦴 วิเคราะห์โครงกระดูกมือลึก 20 ชิ้น (Deep 20-Bone Skeletal & 3D Palm Plane):**
   * โมดูลใหม่ [`HandSkeletonAnalyzer`](app/src/main/java/com/sdwinnie/airgesture/engine/HandSkeletonAnalyzer.kt) คำนวณเวกเตอร์ 3 มิติของระนาบฝ่ามือ (3D Palm Normal Vector) เพื่อแยกแยะว่าฝ่ามือหันเข้าหากล้อง, หลังมือหันเข้าหากล้อง, หรือเป็นท่าสันมือ (Knife Hand)
   * คำนวณความโค้งงอของกระดูกนิ้วมือทั้ง 5 แบบต่อเนื่อง (Continuous Flexion 0.0–1.0) และองศากางระหว่างนิ้ว (Inter-Finger Abduction Angles)
   * ขยายฐานข้อมูลท่าทางมือมนุษย์ตามธรรมชาติ: `เคาะจอ (Air Tap)`, `สันมือ (Knife Hand)`, `ปืนนิ้ว (Finger Gun)`, `ชูนิ้วโทรหา (Call Me / Shaka)`, `สป็อกวัลแคน (Spock Vulcan)`, `ชู 4 นิ้ว (Four Fingers)`

4. **🎨 สไตล์ฝ่ามือใหม่บน HUD: โครงกระดูกไซเบอร์ 3D (Cyber Skeleton 3D):**
   * แสดงกระดูกมือ 20 ชิ้นพร้อมข้อต่อ 21 จุด แยกสีสันเฉพาะแต่ละนิ้วตามกายวิภาค (โป้ง=สีทอง, ชี้=สีฟ้าไซเบอร์, กลาง=สีเขียวมรกต, นาง=สีม่วงนีออน, ก้อย=สีชมพูกุหลาบ) พร้อมแอนิเมชันแฟลชเมื่อตรวจพบการเคาะจอ

5. **🎛️ ให้อิสระแก่ผู้ใช้เต็มรูปแบบ (Customization Freedom Studio):**
   * ไม่มีการฟิกค่าคงที่ตายตัว ผู้ใช้สามารถเปิด/ปิดการล็อกมือเดียว, สลับโหมดจับอิสระ (Free Tracking), ปรับความไวการเลื่อนหน้าจอ, ความไวการปรับเสียง และความไวการเคาะจอได้อย่างสมบูรณ์

---

## 🛡️ 100% Privacy, Security & Binary Permission Footprint

ตรวจสอบไบนารี APK ได้ด้วย `aapt2 dump permissions AirGesture-v4.0.0.apk`:
* `android.permission.CAMERA` — ประมวลผลเฉพาะในหน่วยความจำชั่วคราว (`224×224` Volatile RAM)
* `android.permission.FOREGROUND_SERVICE` & `android.permission.FOREGROUND_SERVICE_CAMERA`
* `android.permission.POST_NOTIFICATIONS`
* ❌ **ไม่มี `android.permission.INTERNET`** หรือ `ACCESS_NETWORK_STATE` (ออฟไลน์ 100% ระดับ Kernel)
* ❌ **ไม่มี `android.permission.SYSTEM_ALERT_WINDOW`** (ใช้ Accessibility Overlay ปลอดภัยต่อ Play Protect)
* 🔒 **ปิดถาวร `android:canRetrieveWindowContent="false"`** (อ่านข้อความหรือรหัสผ่านบนหน้าจอไม่ได้ 100%)

---

## 📄 ใบอนุญาต (License)
MIT License — Copyright (c) 2026 SDWinnie
