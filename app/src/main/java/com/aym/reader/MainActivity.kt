package com.aym.reader

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(pad, pad, pad, pad)
        box.layoutDirection = View.LAYOUT_DIRECTION_RTL

        fun label(s: String, size: Float): TextView {
            val t = TextView(this)
            t.text = s
            t.textSize = size
            t.textDirection = View.TEXT_DIRECTION_RTL
            t.setPadding(0, pad / 2, 0, pad / 2)
            return t
        }
        fun button(s: String, action: () -> Unit): Button {
            val b = Button(this)
            b.text = s
            b.setOnClickListener { action() }
            return b
        }

        box.addView(label("القارئ الصوتي", 26f))
        box.addView(label("اتبع الخطوات الثلاث بالترتيب:", 18f))
        box.addView(button("1) منح إذن الميكروفون وجهات الاتصال") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS), 1)
        })
        box.addView(button("2) فتح إعدادات التطبيق (للسماح بالإعدادات المقيّدة)") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        })
        box.addView(button("3) تفعيل خدمة إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        box.addView(label(
            "بعد التفعيل يظهر زر 🎤 على حافة الشاشة اليسرى. اضغطه مرة ثم قل الأمر.\n\n" +
            "الأوامر:\n" +
            "• رجوع / الرئيسية / الإشعارات\n" +
            "• اقرأ الشاشة / كرر / توقف\n" +
            "• التالي / السابق / انقر\n" +
            "• انقر على + اسم العنصر\n" +
            "• انزل / اصعد\n" +
            "• افتح + اسم تطبيق\n" +
            "• اتصل بـ + الاسم\n" +
            "• اتصل بـ + الاسم + فيديو\n" +
            "• سجل رسالة صوتية لـ + الاسم\n" +
            "• أرسل التسجيل / احذف التسجيل\n" +
            "• إيقاف مؤقت (أثناء التسجيل)\n\n" +
            "أثناء تسجيل رسالة في واتساب: الضغط المطوّل على الزر 🎤 يرسل التسجيل مباشرة.", 17f))

        val sv = ScrollView(this)
        sv.addView(box)
        setContentView(sv)
    }
}
