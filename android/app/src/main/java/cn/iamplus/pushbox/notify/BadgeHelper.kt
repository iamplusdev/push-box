package cn.iamplus.pushbox.notify

import android.content.Context
import android.content.Intent
import android.os.Build
import cn.iamplus.pushbox.MainActivity

/**
 * §7.3 桌面图标角标（未读总数）。AOSP 只给通知圆点、无数字，数字角标是各国产 ROM 的私有能力，
 * 这里按厂商逐个反射尝试设置；任一 ROM 不支持就静默跳过，绝不影响主流程。
 * 通用兜底交给 Notifier：把未读数写入常驻服务通知的 number 字段，支持读取 Notification.number 的
 * 壳层（三星 One UI、部分 EMUI）会自动把它显示为角标。
 */
object BadgeHelper {

    fun update(context: Context, count: Int) {
        val safe = count.coerceAtLeast(0)
        val device = Build.MANUFACTURER.lowercase()
        when {
            device.contains("xiaomi") || device.contains("redmi") || device.contains("poco") -> miui(context, safe)
            device.contains("huawei") || device.contains("honor") -> huawei(context, safe)
            device.contains("oppo") || device.contains("realme") || device.contains("oneplus") -> oppo(context, safe)
            device.contains("vivo") || device.contains("iqoo") -> vivo(context, safe)
            device.contains("meizu") -> meizu(context, safe)
            // 其他 ROM：只依赖 Notifier 里服务通知的 number，不再反射
        }
    }

    /** MIUI：官方广播接口，携带组件名与数字。 */
    private fun miui(context: Context, count: Int) = runCatching {
        val intent = Intent("android.intent.action.APPLICATION_MESSAGE_UPDATE")
            .putExtra("android.intent.extra.update_application_component_name",
                "${context.packageName}/${MainActivity::class.java.name}")
            .putExtra("android.intent.extra.update_application_message_text", count.toString())
        context.sendBroadcast(intent)
    }

    /** EMUI/鸿蒙：BadgeManager 反射 setBadgeCount；低版本走 provider。 */
    private fun huawei(context: Context, count: Int) = runCatching {
        val cls = Class.forName("com.huawei.android.app.BadgeManager")
        val method = cls.getMethod("setBadgeCount", Context::class.java,
            Int::class.javaPrimitiveType)
        method.invoke(null, context, count)
    }

    /** ColorOS：Oppo launcher 通过 provider 更新角标。 */
    private fun oppo(context: Context, count: Int) = runCatching {
        val uri = android.net.Uri.parse("content://com.oppo.launcher.permission.WRITE_SETTINGS")
        context.contentResolver.update(uri,
            android.content.ContentValues().apply {
                put("package", context.packageName)
                put("badgeCount", count)
            }, null, null)
    }

    /** OriginOS/Funtouch：provider 写角标。 */
    private fun vivo(context: Context, count: Int) = runCatching {
        val values = android.content.ContentValues().apply {
            put("packageName", context.packageName)
            put("className", MainActivity::class.java.name)
            put("badgeCount", count)
        }
        context.contentResolver.insert(
            android.net.Uri.parse("content://com.vivo.settings.launcher.badge"), values)
    }

    /** Flyme：provider 更新。 */
    private fun meizu(context: Context, count: Int) = runCatching {
        context.contentResolver.call(
            android.net.Uri.parse("content://com.meizu.flyme.launcher.provider/badge/1"),
            "set_badge", null,
            android.os.Bundle().apply {
                putString("package_name", context.packageName)
                putString("class_name", MainActivity::class.java.name)
                putInt("badge_count", count)
            })
    }
}
