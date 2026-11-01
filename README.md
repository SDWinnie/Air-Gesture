# ✋ Air Gesture v3.0.0 — Single-Hand Spatial AI, On-Screen Circular App Wheel & Custom Gesture Studio

[![Release](https://img.shields.io/github/v/release/SDWinnie/Air-Gesture?color=00E5FF&label=APK%20Release)](https://github.com/SDWinnie/Air-Gesture/releases/latest)
[![Security](https://img.shields.io/badge/Security-Zero%20Overlay%20Perm%20%E2%80%A2%20Play%20Protect%20Safe-00E676)](#%EF%B8%8F-100-privacy-security--binary-permission-footprint)
[![Privacy](https://img.shields.io/badge/Privacy-100%25%20Offline%20%E2%80%A2%20Zero%20Internet-8C62FF)](#%EF%B8%8F-100-privacy-security--binary-permission-footprint)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%20to%2016%2B%20%26%20HyperOS-1E88E5)](https://developer.android.com/)

ระบบสั่งงานสมาร์ทโฟนและแท็บเล็ต Android แบบ **ไร้สัมผัสเจเนอเรชันที่ 3 (v3.0 Single-Hand Spatial AI)** ประมวลผลโครงกระดูกมือด้วย **Edge-AI ภายในเครื่อง 100% (Isotropic 1:1 Volatile RAM • Zero Cloud)** มาพร้อม **ระบบล็อกจับเฉพาะ 1 มือ (Single-Hand Lock)**, **วงกลมเลือกแอปเด้งกลางหน้าจอจริง (On-Screen 360° Spatial Joystick Wheel)**, **แถบสถานะลดเสียง/เพิ่มเสียงชัดเจนบนหน้าจอ** และ **สตูดิโอตั้งค่าท่ามือมนุษย์ & รูปฝ่ามือ HUD ได้อิสระ**

---

## 📥 ดาวน์โหลดไฟล์ติดตั้ง (.apk)

| เวอร์ชัน | มาตรฐานความปลอดภัย (Binary Audit) | รองรับระบบ | ลิงก์ดาวน์โหลด |
| :--- | :--- | :--- | :--- |
| **v3.0.0 (Latest — Signed v1/v2/v3/v4)** | Zero Internet • Zero `SYSTEM_ALERT_WINDOW` • `canRetrieveWindowContent="false"` | Android 8.0 – **Android 14 / 15 / 16+ & Xiaomi HyperOS 2/3** | [👉 **ดาวน์โหลด AirGesture-v3.0.0.apk**](https://github.com/SDWinnie/Air-Gesture/releases/download/v3.0.0/AirGesture-v3.0.0.apk) |

---

## 🚀 อัปเกรดครั้งใหญ่ใน v3.0.0 (แก้ตรงจุดจากการใช้งานจริง)

1. **🔒 ระบบล็อกจับเฉพาะ 1 มือ + แก้อาการจับมั่ว 100% (Single-Hand Lock & Isotropic 1:1 Vision):**
   * **แก้ปัญหาจับ 2 มือสลับไปมา**: เพิ่ม [`SingleHandLockTracker`](app/src/main/java/com/sdwinnie/airgesture/engine/SingleHandLockTracker.kt) ล็อกมือข้างแรกที่ยกขึ้นและบล็อกมือที่ 2 หรือการกระโดดข้ามหน้าจออัตโนมัติ พร้อมให้เลือกโหมด **`ล็อก 1 มืออัตโนมัติ`**, **`เฉพาะมือขวาเท่านั้น`** หรือ **`เฉพาะมือซ้ายเท่านั้น`**
   * **แก้ปัญหาภาพมือบีบเบี้ยว & ROI สั่น**: ปรับ [`VolatileRoiDownscaler`](app/src/main/java/com/sdwinnie/airgesture/vision/VolatileRoiDownscaler.kt) เป็น **Isotropic 1:1 Letterbox (`224×224` / `256×256` RAM)** รักษาสัดส่วนนิ้วมือตามจริง 1:1 ทุกองศา ยกระดับความมั่นใจ AI (`0.64`) และเพิ่มตัวกรองสัดส่วนกระดูกมือจริง (`isAnatomicallyPlausibleHand`) ตัดปัญหาจับหน้าคน หมอน หรือฉากหลังมั่ว
2. **🎡 วงกลมเลือกแอปเด้งกลางหน้าจอจริง + เล็งแบบจอยสติ๊กที่ไม่ต้องบิดข้อมือ:**
   * แสดง **วงกลมเลือกแอปขนาดใหญ่กลางหน้าจอทับทุกแอป** ผ่าน `TYPE_ACCESSIBILITY_OVERLAY` (ไม่ต้องขอสิทธิ์ `SYSTEM_ALERT_WINDOW` ปลอดภัยต่อ Play Protect 100%)
   * **Spatial Virtual Joystick**: ไม่ต้องบิดข้อมือฝืนธรรมชาติ เพียงขยับมือขึ้น/ลง/ซ้าย/ขวาเบาๆ (`4–8 ซม.`) จุดลูกศรเรืองแสงจะวิ่งไปยังแอปในวงกลม พร้อมแสดงไอคอนและชื่อแอปชัดเจน แล้ว **แบมือออกเพื่อเปิดแอป** (มี **โซนปลอดภัยตรงกลางวงกลม** หากแบมือตรงกลางจะยกเลิกโดยไม่เปิดแอปผิด)
   * ปรับจำนวนแอปในวงกลมได้ตามถนัด: **`6 แอป`**, **`8 แอป (แนะนำ)`**, **`12 แอป`** หรือ **`15 แอป`**
3. **🔉 สถานะลดเสียง / เพิ่มเสียง / ปิดเสียง แจ้งเตือนชัดเจนบนหน้าจอ:**
   * แสดงแคปซูลสถานะเสียงแบบเรียลไทม์บนขอบบนหน้าจอ บอกทิศทางชัดเจนทั้ง **`🔉 กำลังลดเสียง ▼ 34% (-8%)`**, **`🔊 กำลังเพิ่มเสียง ▲ 76% (+10%)`**, **`🔇 ปิดเสียงแล้ว (MUTE) 0%`** พร้อมเชื่อมต่อแถบเสียงระบบ Android (`FLAG_SHOW_UI` เปิด/ปิดได้)
4. **🖐️ ตั้งค่าท่าทางมนุษย์ (MediaPipe Ergonomic DB) & ตั้งค่ารูปฝ่ามือ/สี HUD ได้เอง:**
   * เลือกท่ามือมาตรฐานจากฐานข้อมูล MediaPipe ที่มนุษย์ทำได้สบาย ไม่เมื่อยนิ้ว:
     * **เลื่อนหน้าจอ**: `✋ แบมือ 5 นิ้ว` / `☝️ ชี้นิ้วเดียว` / `✌️ ชู 2 นิ้ว (V)`
     * **ลดเสียง / เพิ่มเสียง**: `🤏 จีบนิ้ว OK เลื่อนขึ้น-ลง` / **`👍 ชูโป้งขึ้น=เพิ่ม • 👎 โป้งลง=ลดเสียง`** / `✌️ ชู 2 นิ้วเลื่อนขึ้น-ลง`
     * **เปิดวงกลมเลือกแอป**: `✊ กำมือ` / `🤟 ท่า I Love You (โป้ง+ชี้+ก้อย)` / `🖖 ชู 3 นิ้ว`
     * **ย้อนกลับด่วน**: `↔️ ปัดซ้าย-ขวา` / `✌️ ชู 2 นิ้วค้าง` / `🤟 ท่า I Love You ค้าง`
   * ปรับแต่ง **รูปฝ่ามือบน Dynamic Island** (`🦴 โครงกระดูก 21 ข้อ`, `✋ รูปฝ่ามือเรืองแสง`, `🎭 ไอคอนท่าทางอัจฉริยะ`, `🔮 วงแหวนพลังงานมินิมอล`) และ **5 โทนสีเรืองแสง** ได้ทันทีในแท็บ **`🖐️ ท่ามือ & รูป`**

---

## 🛡️ 100% Privacy, Security & Binary Permission Footprint

ตรวจสอบไบนารี APK ได้ด้วย `aapt2 dump permissions AirGesture-v3.0.0.apk`:
* `android.permission.CAMERA` — ประมวลผลเฉพาะในหน่วยความจำชั่วคราว (`224×224` / `256×256` Volatile RAM)
* `android.permission.FOREGROUND_SERVICE` & `android.permission.FOREGROUND_SERVICE_CAMERA`
* `android.permission.POST_NOTIFICATIONS`
* ❌ **ไม่มี `android.permission.INTERNET`** หรือ `ACCESS_NETWORK_STATE` (ออฟไลน์ 100% ระดับ Kernel)
* ❌ **ไม่มี `android.permission.SYSTEM_ALERT_WINDOW`**
* 🔒 **ปิดถาวร `android:canRetrieveWindowContent="false"`** (อ่านข้อความหรือรหัสผ่านบนหน้าจอไม่ได้ 100%)

---

## 📄 ใบอนุญาต (License)
MIT License — Copyright (c) 2026 SDWinnie
