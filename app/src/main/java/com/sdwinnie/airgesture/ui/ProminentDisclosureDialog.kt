package com.sdwinnie.airgesture.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sdwinnie.airgesture.R
import com.sdwinnie.airgesture.security.PreferenceStore

/**
 * Google Play Protect Compliant Prominent Disclosure Modal + Android 13–16+ Restricted Settings Helper.
 * Requires explicit affirmative user consent before requesting Camera, Overlay, or Accessibility access.
 */
object ProminentDisclosureDialog {

    fun show(
        context: Context,
        preferenceStore: PreferenceStore,
        onAccepted: () -> Unit,
        onDeclined: () -> Unit = {}
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.disclosure_title))
            .setMessage(context.getString(R.string.disclosure_body))
            .setCancelable(false)
            .setPositiveButton(context.getString(R.string.disclosure_accept)) { dialog, _ ->
                preferenceStore.isProminentDisclosureAccepted = true
                dialog.dismiss()
                onAccepted()
            }
            .setNegativeButton(context.getString(R.string.disclosure_decline)) { dialog, _ ->
                dialog.dismiss()
                onDeclined()
            }
            .show()
    }

    fun showRestrictedSettingsGuide(context: Context) {
        val guideMessage = """
            บน Android 13 / 14 / 15 / 16+ (รวมถึง Xiaomi HyperOS และ Samsung One UI) หากติดตั้งไฟล์ .apk โดยตรง ระบบอาจล็อกปุ่ม "การช่วยเหลือพิเศษ (Accessibility)" ไว้ชั่วคราว

            วิธีปลดล็อกใน 2 ขั้นตอน:
            1. แตะปุ่ม "เปิดหน้าข้อมูลแอป (App Info)" ด้านล่าง
            2. แตะไอคอนจุด 3 จุด (⋮) มุมขวาบน (หรือเลื่อนลงล่างสุดบน Xiaomi) แล้วเลือก "อนุญาตการตั้งค่าที่จำกัด (Allow restricted settings)"

            หลังจากนั้นจะสามารถเปิดสวิตช์ Air Gesture ในหน้าการช่วยเหลือพิเศษได้ทันที
        """.trimIndent()

        MaterialAlertDialogBuilder(context)
            .setTitle("วิธีปลดล็อกการตั้งค่าที่จำกัด (Android 13–16+)")
            .setMessage(guideMessage)
            .setPositiveButton("เปิดหน้าข้อมูลแอป (App Info)") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
            .setNegativeButton("ปิด", null)
            .show()
    }
}
