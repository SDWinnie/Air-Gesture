package com.sdwinnie.airgesture.input

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.sdwinnie.airgesture.engine.HandGeometry
import com.sdwinnie.airgesture.model.RadialAppSlot
import com.sdwinnie.airgesture.security.AirLogger
import com.sdwinnie.airgesture.security.PreferenceStore

/**
 * Manages the circular app shortcuts (6, 8, 12, or 15 segments) for the On-Screen 360° Circular App Wheel.
 */
class RadialAppSlotManager(
    private val context: Context,
    private val preferenceStore: PreferenceStore
) {
    data class InstalledAppEntry(
        val packageName: String,
        val label: String
    )

    private val packageManager = context.applicationContext.packageManager
    private val iconCache = HashMap<String, Bitmap>(24)

    fun queryInstalledLauncherApps(): List<InstalledAppEntry> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = packageManager.queryIntentActivities(launcherIntent, 0)
        val selfPkg = context.packageName

        return activities
            .asSequence()
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == selfPkg) return@mapNotNull null
                val label = ri.loadLabel(packageManager)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                InstalledAppEntry(packageName = pkg, label = label)
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun loadResolvedSlots(
        activeCount: Int = preferenceStore.loadConfig().radialSlotCount.count
    ): List<RadialAppSlot> {
        val total = activeCount.coerceIn(6, HandGeometry.MAX_RADIAL_SLOTS)
        val installed = queryInstalledLauncherApps()
        val installedMap = installed.associateBy { it.packageName }
        val result = ArrayList<RadialAppSlot>(total)

        for (slotIdx in 0 until total) {
            val savedPkg = preferenceStore.getRadialSlotPackage(slotIdx)
            val resolvedEntry = if (savedPkg != null && installedMap.containsKey(savedPkg)) {
                installedMap[savedPkg]!!
            } else if (installed.isNotEmpty()) {
                val fallback = installed[slotIdx % installed.size]
                if (savedPkg == null) {
                    preferenceStore.setRadialSlotPackage(slotIdx, fallback.packageName)
                }
                fallback
            } else {
                InstalledAppEntry(packageName = "", label = "ช่อง ${slotIdx + 1}")
            }

            result.add(
                RadialAppSlot(
                    slotIndex = slotIdx,
                    packageName = resolvedEntry.packageName,
                    label = resolvedEntry.label,
                    totalActiveSlots = total
                )
            )
        }
        return result
    }

    @Synchronized
    fun getCachedAppIcon(packageName: String, iconSizePx: Int = 84): Bitmap? {
        if (packageName.isBlank()) return null
        iconCache[packageName]?.takeIf { !it.isRecycled }?.let { return it }

        val drawable = runCatching { packageManager.getApplicationIcon(packageName) }.getOrNull() ?: return null
        val bmp = drawableToBitmap(drawable, iconSizePx)
        iconCache[packageName] = bmp
        return bmp
    }

    fun launchSlot(slotIndex: Int, activeCount: Int = preferenceStore.loadConfig().radialSlotCount.count): RadialAppSlot? {
        val slots = loadResolvedSlots(activeCount)
        val slot = slots.getOrNull(slotIndex) ?: return null
        if (slot.packageName.isBlank()) return null

        val launchIntent = packageManager.getLaunchIntentForPackage(slot.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        } ?: return null

        return runCatching {
            context.startActivity(launchIntent)
            AirLogger.d("Launcher", "Launched circular slot $slotIndex -> ${slot.packageName}")
            slot
        }.getOrNull()
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }
}
