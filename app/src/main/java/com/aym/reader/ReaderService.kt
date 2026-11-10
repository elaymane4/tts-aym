package com.aym.reader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import java.util.Locale

// ---------- أدوات النصوص ----------
private val MARKS = Regex("[\\u064B-\\u065F\\u0670\\u0640]")
private val SPACES = Regex("\\s+")
private val PUNCT = Regex("[\\p{Punct}؟،؛]")

/** توحيد النص العربي: حذف التشكيل وتوحيد الهمزات والتاء المربوطة والألف المقصورة */
private fun norm(s: String): String {
    var t = s.lowercase(Locale.ROOT)
    t = MARKS.replace(t, "")
    t = t.replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
    t = PUNCT.replace(t, " ")
    return SPACES.replace(t, " ").trim()
}

// ترجمة تسميات الأزرار الإنجليزية الشائعة للعربية
private val TR = mapOf(
    "send" to "إرسال", "delete" to "حذف", "trash" to "حذف", "cancel" to "إلغاء",
    "back" to "رجوع", "navigate up" to "رجوع", "search" to "بحث", "call" to "اتصال",
    "voice call" to "مكالمة صوتية", "video call" to "مكالمة فيديو",
    "voice message" to "رسالة صوتية", "pause" to "إيقاف مؤقت", "play" to "تشغيل",
    "settings" to "الإعدادات", "camera" to "الكاميرا", "attach" to "إرفاق",
    "more options" to "خيارات أخرى", "menu" to "القائمة", "emoji" to "وجوه تعبيرية",
    "message" to "رسالة", "type a message" to "اكتب رسالة", "ok" to "موافق",
    "yes" to "نعم", "no" to "لا", "share" to "مشاركة", "close" to "إغلاق",
    "done" to "تم", "save" to "حفظ", "next" to "التالي", "profile" to "الملف الشخصي",
    "chats" to "المحادثات", "calls" to "المكالمات", "status" to "الحالة", "new chat" to "محادثة جديدة"
)
// عربي موحّد -> قائمة مرادفات إنجليزية
private val SYN: Map<String, List<String>> by lazy {
    TR.entries.groupBy({ norm(it.value) }, { it.key })
}

private val YES = setOf("نعم", "ايوه", "ايه", "اجل", "اكيد", "تمام", "موافق", "ارسل", "احذف", "اوكي", "ok", "yes").map { norm(it) }.toSet()
private val NO = setOf("لا", "كلا", "الغ", "الغاء", "توقف", "ايقاف").map { norm(it) }.toSet()

private val NOISE = setOf(
    "اتصل", "كلم", "مكالمه", "اتصال", "صوتيه", "صوتي", "فيديو", "سجل", "ارسل", "رساله",
    "تسجيل", "فويس", "لي", "ل", "ب", "من", "فضلك", "واتساب", "الي", "عبر", "بواسطه"
).map { norm(it) }.toSet()

private val APPS: Map<String, String> = mapOf(
    "واتساب" to "whatsapp", "تيليجرام" to "telegram", "تلغرام" to "telegram",
    "يوتيوب" to "youtube", "فيسبوك" to "facebook", "فيس بوك" to "facebook",
    "انستغرام" to "instagram", "انستجرام" to "instagram", "كروم" to "chrome",
    "الكاميرا" to "camera", "كاميرا" to "camera", "الاعدادات" to "settings",
    "اعدادات" to "settings", "جيميل" to "gmail", "الخرائط" to "maps",
    "تيك توك" to "tiktok", "الهاتف" to "phone", "الرسائل" to "messages",
    "الساعه" to "clock", "الصور" to "photos", "الحاسبه" to "calculator",
    "الالله الحاسبه" to "calculator", "سناب شات" to "snapchat", "تويتر" to "x"
).mapKeys { norm(it.key) }

private const val MIME_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
private const val MIME_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
private const val MIME_CHAT = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
private const val WA = "com.whatsapp"
private val MIC = listOf("voice message", "رسالة صوتية", "تسجيل رسالة صوتية")
private val SEND = listOf("send", "إرسال", "ارسال")
private val DELETE = listOf("delete", "حذف", "trash", "cancel", "إلغاء")
private val PAUSE = listOf("pause", "إيقاف مؤقت")

private class Hit(val id: Long, val name: String, val exact: Boolean)

class ReaderService : AccessibilityService(), TextToSpeech.OnInitListener {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var wm: WindowManager? = null
    private var button: Button? = null

    private var listening = false
    private var recordingMode = false
    private var pendingConfirm: (() -> Unit)? = null
    private val doneCallbacks = HashMap<String, () -> Unit>()
    private var uttCounter = 0
    private var lastSpoken = ""
    private var lastSpokenAt = 0L
    private var curKey = ""
    private var items: List<AccessibilityNodeInfo> = emptyList()
    private var idx = -1

    // ---------- دورة حياة الخدمة ----------
    override fun onServiceConnected() {
        super.onServiceConnected()
        tts = TextToSpeech(this, this)
        addButton()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "تعذّر تشغيل محرك النطق", Toast.LENGTH_LONG).show()
            return
        }
        val r = tts?.setLanguage(Locale("ar"))
        ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        if (!ttsReady) {
            Toast.makeText(this, "ثبّت صوت العربية في إعدادات محرك Google للنطق", Toast.LENGTH_LONG).show()
            return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { finish(id) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { finish(id) }
        })
        speak("تم تشغيل القارئ الصوتي. اضغط زر الميكروفون وتكلم")
    }

    private fun finish(id: String?) {
        if (id == null) return
        val cb = doneCallbacks.remove(id)
        if (cb != null) main.post(cb)
    }

    override fun onInterrupt() { tts?.stop() }

    override fun onDestroy() {
        try { button?.let { wm?.removeView(it) } } catch (e: Exception) {}
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- النطق ----------
    private fun speak(text: String, flush: Boolean = true, then: (() -> Unit)? = null) {
        if (!ttsReady) { if (then != null) main.post(then); return }
        if (flush) doneCallbacks.clear()
        val id = "u" + (++uttCounter)
        if (then != null) doneCallbacks[id] = then
        tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
    }

    private fun feedback(msg: String) { if (recordingMode) buzz() else speak(msg) }

    private fun buzz() {
        try {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {}
    }

    private fun beep() {
        try {
            val g = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            g.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ g.release() }, 500)
        } catch (e: Exception) {}
    }

    private fun tr(s: String): String = TR[s.trim().lowercase(Locale.ROOT)] ?: s

    // ---------- الزر العائم ----------
    private fun addButton() {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = manager
        val b = Button(this)
        b.text = "🎤"
        b.textSize = 24f
        b.contentDescription = "زر الأوامر الصوتية. اضغط مرة ثم تكلم"
        b.setOnClickListener { onButtonTap() }
        b.setOnLongClickListener { onButtonLong(); true }
        val size = (72 * resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
        manager.addView(b, lp)
        button = b
    }

    private fun onButtonTap() {
        if (listening) return
        tts?.stop()
        doneCallbacks.clear()
        if (!recordingMode) beep()
        main.postDelayed({ startListening() }, 350)
    }

    private fun onButtonLong() {
        if (recordingMode) finishRecording(true)
        else speak("اضغط مرة واحدة ثم قل الأمر")
    }

    // ---------- التعرف على الكلام ----------
    private fun startListening() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            speak("افتح التطبيق وامنح إذن الميكروفون")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("خدمة التعرف على الكلام غير متوفرة على هذا الهاتف")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(listener)
        recognizer = r
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        listening = true
        r.startListening(i)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) {
            listening = false
            pendingConfirm = null
            feedback("لم أسمع جيدا، حاول مرة أخرى")
        }
        override fun onResults(results: Bundle?) {
            listening = false
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (list.isNullOrEmpty()) { pendingConfirm = null; feedback("لم أسمع جيدا"); return }
            handle(list)
        }
    }

    private fun handle(results: List<String>) {
        val pc = pendingConfirm
        if (pc != null) {
            pendingConfirm = null
            val words = results.flatMap { norm(it).split(" ") }
            when {
                words.any { it in NO } -> speak("تم الإلغاء")
                words.any { it in YES } -> pc()
                else -> speak("تم الإلغاء")
            }
            return
        }
        for (r in results) if (execute(norm(r))) return
        feedback("لم أفهم الأمر: " + results[0])
    }

    private fun ask(question: String, onYes: () -> Unit) {
        pendingConfirm = onYes
        speak(question) { startListening() }
    }

    // ---------- تنفيذ الأوامر ----------
    private fun execute(t: String): Boolean {
        fun has(vararg k: String) = k.any { t.contains(norm(it)) }
        fun sw(vararg k: String) = k.any { t.startsWith(norm(it)) }

        if (recordingMode) {
            if (has("ارسل")) { finishRecording(true); return true }
            if (has("احذف", "الغ", "امسح")) { finishRecording(false); return true }
            if (has("ايقاف مؤقت", "وقف مؤقت")) { clickLabels(PAUSE); return true }
        }

        if (sw("افتح ", "شغل ")) {
            openApp(t.substringAfter(" ").trim())
            return true
        }

        if (has("ارسل التسجيل", "ارسل الرسالة", "ارسل الفويس")) {
            ask("هل تريد إرسال التسجيل؟") { finishRecording(true) }
            return true
        }
        if (has("احذف التسجيل", "الغ التسجيل", "امسح التسجيل", "احذف الرسالة")) {
            ask("هل تريد حذف التسجيل؟") { finishRecording(false) }
            return true
        }

        val clickVerb = listOf("انقر", "اضغط", "كبس").firstOrNull { t.startsWith(norm(it)) }
        if (clickVerb != null) {
            var label = t.removePrefix(norm(clickVerb)).trim()
            if (label.startsWith("علي ")) label = label.removePrefix("علي ").trim()
            if (label.isBlank() || label == "هنا") clickCurrent() else clickLabel(label)
            return true
        }

        val name = extractName(t)
        if ((has("سجل") || has("رسالة صوتية", "تسجيل صوتي", "فويس")) && !has("اتصل", "مكالمة")) {
            if (name.isBlank()) feedback("لمن تريد التسجيل؟") else startVoiceMessage(name)
            return true
        }
        if (has("اتصل", "اتصال", "مكالمة", "كلم")) {
            if (name.isBlank()) feedback("بمن تريد الاتصال؟") else startCall(name, has("فيديو"))
            return true
        }

        when {
            has("رجوع", "ارجع", "للخلف") -> performGlobalAction(GLOBAL_ACTION_BACK)
            has("الرئيسية", "البيت") -> performGlobalAction(GLOBAL_ACTION_HOME)
            has("الاشعارات") -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            has("الاعدادات السريعة") -> performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            has("التطبيقات الاخيرة", "الاعمال الاخيرة") -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            has("اقرا") -> readScreen()
            has("انزل", "اسفل") -> scroll(true)
            has("اصعد", "اعلي") -> scroll(false)
            has("التالي") -> step(1)
            has("السابق") -> step(-1)
            has("كرر") -> speak(lastSpoken)
            has("توقف", "اسكت") -> { tts?.stop(); doneCallbacks.clear() }
            else -> return false
        }
        return true
    }

    private fun extractName(t: String): String =
        t.replace("واتس اب", "واتساب").split(" ").filter { it.isNotBlank() && it !in NOISE }.joinToString(" ")

    private fun candidates(name: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(n: String) {
            out.add(n)
            if (n.length > 2 && (n[0] == 'ب' || n[0] == 'ل')) out.add(n.substring(1))
            if (n.startsWith("لل") && n.length > 3) out.add("ال" + n.substring(2))
        }
        add(name)
        val parts = name.split(" ")
        if (parts.size > 1 && parts[0] == "علي") add(parts.drop(1).joinToString(" "))
        return out.toList()
    }

    // ---------- جهات الاتصال وواتساب ----------
    private fun findContacts(mime: String, spoken: String): List<Hit> {
        val rows = ArrayList<Pair<Long, String>>()
        val c = contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data._ID, ContactsContract.Data.DISPLAY_NAME),
            ContactsContract.Data.MIMETYPE + "=?", arrayOf(mime), null
        )
        if (c != null) {
            while (c.moveToNext()) {
                val n = c.getString(1)
                if (n != null) rows.add(Pair(c.getLong(0), n))
            }
            c.close()
        }
        for (cand in candidates(spoken)) {
            val m = rows.filter { norm(it.second).contains(cand) }
            if (m.isEmpty()) continue
            val exact = m.filter { val nn = norm(it.second); nn == cand || nn.split(" ").contains(cand) }
            val pick = if (exact.isNotEmpty()) exact else m
            return pick.distinctBy { it.second }.map { Hit(it.first, it.second, exact.isNotEmpty()) }
        }
        return emptyList()
    }

    private fun withContact(mime: String, spoken: String, action: (Hit) -> Unit) {
        if (checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            speak("افتح التطبيق وامنح إذن جهات الاتصال")
            return
        }
        val hits = findContacts(mime, spoken)
        when {
            hits.isEmpty() -> feedback("لم أجد $spoken في جهات اتصال واتساب")
            hits.size > 1 -> speak("وجدت ${hits.size} جهات: " + hits.take(4).joinToString(" ، ") { it.name } + ". كرر الأمر مع الاسم الكامل")
            hits[0].exact || recordingMode -> action(hits[0])
            else -> ask("هل تقصد " + hits[0].name + "؟") { action(hits[0]) }
        }
    }

    private fun openRow(id: Long, mime: String): Boolean {
        val uri = Uri.parse("content://com.android.contacts/data/$id")
        for (pkg in listOf(WA, "com.whatsapp.w4b")) {
            try {
                val i = Intent(Intent.ACTION_VIEW)
                i.setDataAndType(uri, mime)
                i.setPackage(pkg)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return true
            } catch (e: Exception) {}
        }
        return false
    }

    private fun startCall(name: String, video: Boolean) {
        val mime = if (video) MIME_VIDEO else MIME_CALL
        withContact(mime, name) { hit ->
            val what = if (video) "مكالمة فيديو مع " else "الاتصال بـ "
            speak("جاري $what" + hit.name) {
                if (!openRow(hit.id, mime)) speak("تعذّر فتح واتساب")
            }
        }
    }

    private fun startVoiceMessage(name: String) {
        withContact(MIME_CHAT, name) { hit ->
            speak("جاري التسجيل إلى " + hit.name) {
                if (!openRow(hit.id, MIME_CHAT)) {
                    speak("تعذّر فتح واتساب")
                } else {
                    main.postDelayed({
                        waitForNode(MIC, WA, 20) { mic ->
                            if (mic == null) {
                                speak("لم أجد زر التسجيل")
                            } else {
                                holdAndSwipeUp(mic) { ok ->
                                    if (ok) { recordingMode = true; buzz() } else speak("فشل بدء التسجيل")
                                }
                            }
                        }
                    }, 1500)
                }
            }
        }
    }

    private fun finishRecording(send: Boolean) {
        val n = findNode(rootInActiveWindow, if (send) SEND else DELETE, WA)
        if (n == null) { feedback(if (send) "لم أجد زر الإرسال" else "لم أجد زر الحذف"); return }
        clickNode(n)
        recordingMode = false
        main.postDelayed({ speak(if (send) "تم الإرسال" else "تم الحذف") }, 900)
    }

    private fun waitForNode(labels: List<String>, pkg: String?, tries: Int, cb: (AccessibilityNodeInfo?) -> Unit) {
        val n = findNode(rootInActiveWindow, labels, pkg)
        if (n != null || tries <= 0) cb(n)
        else main.postDelayed({ waitForNode(labels, pkg, tries - 1, cb) }, 400)
    }

    // ---------- الإيماءات ----------
    private fun holdAndSwipeUp(n: AccessibilityNodeInfo, done: (Boolean) -> Unit) {
        val r = Rect()
        n.getBoundsInScreen(r)
        val x = r.exactCenterX()
        val y = r.exactCenterY()
        val up = 170f * resources.displayMetrics.density
        val hold = Path()
        hold.moveTo(x, y)
        hold.lineTo(x + 1f, y)
        val s1 = GestureDescription.StrokeDescription(hold, 0L, 700L, true)
        val cb2 = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { done(true) }
            override fun onCancelled(g: GestureDescription?) { done(false) }
        }
        val cb1 = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                val move = Path()
                move.moveTo(x + 1f, y)
                move.lineTo(x + 1f, y - up)
                val s2 = s1.continueStroke(move, 0L, 700L, false)
                dispatchGesture(GestureDescription.Builder().addStroke(s2).build(), cb2, null)
            }
            override fun onCancelled(g: GestureDescription?) { done(false) }
        }
        dispatchGesture(GestureDescription.Builder().addStroke(s1).build(), cb1, null)
    }

    private fun tap(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0L, 60L)).build()
        dispatchGesture(g, null, null)
    }

    // ---------- البحث عن العناصر والنقر ----------
    private fun labelOf(n: AccessibilityNodeInfo): String =
        if (n.isPassword) "" else (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()

    private fun findNode(root: AccessibilityNodeInfo?, labels: List<String>, pkg: String?): AccessibilityNodeInfo? {
        if (root == null) return null
        if (pkg != null && root.packageName?.toString()?.startsWith(pkg) != true) return null
        val ls = labels.map { norm(it) }
        for (exact in listOf(true, false)) {
            var found: AccessibilityNodeInfo? = null
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null || found != null) return
                val cands = listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).map { norm(it) }
                if (cands.any { c -> ls.any { l -> if (exact) c == l else c.contains(l) } }) { found = n; return }
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(root)
            if (found != null) return found
        }
        return null
    }

    private fun clickNode(n: AccessibilityNodeInfo?): Boolean {
        if (n == null) return false
        var c: AccessibilityNodeInfo? = n
        while (c != null && !c.isClickable) c = c.parent
        if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        n.getBoundsInScreen(r)
        tap(r.exactCenterX(), r.exactCenterY())
        return true
    }

    private fun clickLabels(labels: List<String>) {
        val n = findNode(rootInActiveWindow, labels, null)
        if (n == null) feedback("لم أجد الزر") else { clickNode(n); buzz() }
    }

    private fun clickLabel(label: String) {
        val labels = listOf(label) + (SYN[label] ?: emptyList())
        val n = findNode(rootInActiveWindow, labels, null)
        if (n == null) feedback("لم أجد $label") else { clickNode(n); buzz() }
    }

    private fun clickCurrent() {
        val n = items.getOrNull(idx)
        if (n == null) { speak("قل التالي أولا لاختيار عنصر"); return }
        n.refresh()
        clickNode(n)
        buzz()
    }

    // ---------- القراءة والتنقل ----------
    private fun gather(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null || !n.isVisibleToUser) return
        if (labelOf(n).isNotBlank() && (n.childCount == 0 || n.isClickable)) out.add(n)
        for (i in 0 until n.childCount) gather(n.getChild(i), out)
    }

    private fun keyOf(n: AccessibilityNodeInfo): String {
        val r = Rect()
        n.getBoundsInScreen(r)
        return labelOf(n) + r.toShortString()
    }

    private fun describeItem(n: AccessibilityNodeInfo): String {
        var s = tr(labelOf(n))
        if (n.isEditable) s += "، حقل نص" else if (n.isClickable) s += "، زر"
        return s
    }

    private fun readScreen() {
        val list = ArrayList<AccessibilityNodeInfo>()
        gather(rootInActiveWindow, list)
        if (list.isEmpty()) { speak("لا يوجد نص على الشاشة"); return }
        speak(list.joinToString(". ") { tr(labelOf(it)) }.take(3000))
    }

    private fun step(dir: Int) {
        val root = rootInActiveWindow
        if (root == null) { speak("لا توجد نافذة"); return }
        val list = ArrayList<AccessibilityNodeInfo>()
        gather(root, list)
        if (list.isEmpty()) { speak("لا توجد عناصر"); return }
        val cur = list.indexOfFirst { keyOf(it) == curKey }
        val ni = when {
            cur >= 0 -> cur + dir
            dir > 0 -> 0
            else -> list.size - 1
        }
        if (ni !in list.indices) { speak(if (dir > 0) "هذه نهاية الشاشة" else "هذه بداية الشاشة"); return }
        items = list
        idx = ni
        curKey = keyOf(list[ni])
        speak(describeItem(list[ni]))
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null || !n.isVisibleToUser) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) {
            val r = findScrollable(n.getChild(i))
            if (r != null) return r
        }
        return null
    }

    private fun scroll(forward: Boolean) {
        val n = findScrollable(rootInActiveWindow)
        if (n == null) { feedback("لا يوجد ما يمكن تمريره"); return }
        n.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        buzz()
    }

    // ---------- فتح التطبيقات ----------
    private fun openApp(spoken: String) {
        if (spoken.isBlank()) { speak("أي تطبيق تريد؟"); return }
        val key = norm(APPS[spoken] ?: spoken)
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == key }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(key) }
        if (hit == null) { feedback("لم أجد تطبيق $spoken"); return }
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        i.setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        speak("جاري فتح " + hit.loadLabel(pm)) {
            try { startActivity(i) } catch (e: Exception) {}
        }
    }

    // ---------- قراءة أحداث الواجهة ----------
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null) return
        if (e.packageName?.toString() == packageName) return
        if (listening || recordingMode || pendingConfirm != null) return
        val text: String = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val n = e.source
                if (n != null && n.isPassword) "حقل كلمة المرور"
                else (n?.text ?: n?.contentDescription ?: e.contentDescription)?.toString()
                    ?: e.text.joinToString(" ")
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                e.text.joinToString(" ").ifBlank { e.contentDescription?.toString() ?: "" }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> "إشعار. " + e.text.joinToString(" ")
            else -> return
        }
        announce(text, e.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
    }

    private fun announce(raw: String, flush: Boolean) {
        val s = tr(raw.trim())
        if (s.isBlank()) return
        val now = System.currentTimeMillis()
        if (s == lastSpoken && now - lastSpokenAt < 1200) return
        lastSpoken = s
        lastSpokenAt = now
        speak(s.take(400), flush)
    }
}
