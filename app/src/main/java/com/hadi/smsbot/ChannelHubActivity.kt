package com.hadi.smsbot

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Consent-first organizer for approved contacts and platform-native sharing.
 *
 * IMPORTANT: Share intents cannot enroll recipients, create broadcast lists, read
 * messenger account contacts, confirm delivery, or schedule/automate delivery.
 * A human selects the real destination in the receiving app.
 */
@Suppress("DEPRECATION")
class ChannelHubActivity : Activity() {
    companion object {
        private const val MEDIA_REQUEST = 74
        private const val KEY_MEDIA = "mediaUri"
        private const val KEY_MIME = "mediaMime"
    }

    private enum class Platform(val label: String, val packages: List<String>, val officialUrl: String) {
        TELEGRAM("تلگرام", listOf("org.telegram.messenger", "org.telegram.messenger.web"), "https://telegram.org/faq"),
        WHATSAPP("واتساپ", listOf("com.whatsapp", "com.whatsapp.w4b"), "https://faq.whatsapp.com/861663048350950/?cms_platform=android"),
        RUBIKA("روبیکا", listOf("app.rbmain.a", "ir.resaneh1.iptv"), "https://rubika.ir/"),
        BALE("بله", listOf("ir.nasim"), "https://bale.ai/")
    }

    private lateinit var status: TextView
    private lateinit var policyText: TextView
    private lateinit var groupPreview: TextView
    private lateinit var mediaText: TextView
    private lateinit var message: EditText
    private lateinit var chunkSize: EditText
    private lateinit var groupCount: TextView
    private lateinit var batchNav: LinearLayout
    private var mediaUri: Uri? = null
    private var mediaMime: String? = null
    private var currentPlatform: Platform = Platform.WHATSAPP
    private data class ChannelPlan(
        var groupSize: Int = 20,
        var currentIndex: Int = 0,
        var groups: List<List<Int>> = emptyList(),
        val manuallyReviewed: MutableSet<Int> = mutableSetOf()
    )
    private val plans = mutableMapOf<Platform, ChannelPlan>()
    private fun plan() = plans.getOrPut(currentPlatform) { ChannelPlan() }
    private var batchIndex = 0
    private var batches: List<List<Int>> = emptyList()
    private lateinit var reviewedButton: Button
    private var pendingMediaType = "image/*"
    private var names: List<String> = emptyList()
    private var phones: List<String> = emptyList()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        names = intent.getStringArrayListExtra("names").orEmpty()
        phones = intent.getStringArrayListExtra("phones").orEmpty()
        mediaUri = state?.getString(KEY_MEDIA)?.let(Uri::parse)
        mediaMime = state?.getString(KEY_MIME)
        createUi()
        if (mediaUri != null) mediaText.text = "رسانه انتخاب شده: ${mediaMime ?: "فایل"}"
        setPlatform(Platform.WHATSAPP)
    }

    private fun px(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 14f) = TextView(this).apply {
        this.text = value
        textSize = size
        setPadding(0, px(5), 0, px(5))
    }
    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        this.text = title
        textSize = 12f
        setOnClickListener { action() }
    }
    private fun rowParam() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun createUi() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14), px(14), px(14), px(14))
            layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        }
        scroll.addView(root)
        setContentView(scroll)
        root.addView(text("SMS Bot | پیام‌رسان‌ها و گروه‌ها", 20f))
        root.addView(text("گیرندگان انتخاب‌شده از دفترچه/SMS: ${phones.size} نفر. فقط شماره‌های مورد رضایت را انتخاب کنید.", 12f))
        root.addView(text("کانال ارسال را انتخاب کنید:"))

        val top = LinearLayout(this)
        root.addView(top)
        top.addView(button("تلگرام") { setPlatform(Platform.TELEGRAM) }, rowParam())
        top.addView(button("واتساپ") { setPlatform(Platform.WHATSAPP) }, rowParam())

        val bottom = LinearLayout(this)
        root.addView(bottom)
        bottom.addView(button("روبیکا") { setPlatform(Platform.RUBIKA) }, rowParam())
        bottom.addView(button("بله") { setPlatform(Platform.BALE) }, rowParam())

        policyText = text("", 12f)
        root.addView(policyText)
        root.addView(button("مشاهده راهنمای رسمی پیام‌رسان") {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentPlatform.officialUrl))) }
            catch (e: Exception) { status.text = "امکان باز کردن مرورگر وجود ندارد: ${e.javaClass.simpleName}" }
        })

        root.addView(text("متن مشترک برای اشتراک‌گذاری:", 15f))
        message = EditText(this).apply {
            hint = "متن پیام (اختیاری در صورت انتخاب رسانه)"
            minLines = 2
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(intent.getStringExtra("draft").orEmpty())
        }
        root.addView(message)
        val mediaRow = LinearLayout(this)
        root.addView(mediaRow)
        mediaRow.addView(button("انتخاب عکس") { pickMedia("image/*") }, rowParam())
        mediaRow.addView(button("انتخاب ویدیو") { pickMedia("video/*") }, rowParam())
        mediaRow.addView(button("حذف فایل") { mediaUri = null; mediaMime = null; mediaText.text = "فایلی انتخاب نشده" }, rowParam())
        mediaText = text("فایلی انتخاب نشده", 12f)
        root.addView(mediaText)

        root.addView(text("تقسیم‌بندی فهرست برای مدیریت دستی:", 15f))
        root.addView(text("این تقسیم‌بندی فقط فهرست شماره‌ها را آماده می‌کند؛ تعداد اعضای واقعی و محدودیت ارسال در اپ مقصد مستقل است.", 12f))
        val sizeRow = LinearLayout(this)
        root.addView(sizeRow)
        sizeRow.addView(text("تعداد هر دسته:"))
        chunkSize = EditText(this).apply {
            setText("20")
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelectAllOnFocus(true)
            setEms(4)
        }
        sizeRow.addView(chunkSize)
        sizeRow.addView(button("تقسیم خودکار فهرست") { makeBatches() })

        groupCount = text("", 13f)
        groupPreview = text("", 13f)
        root.addView(groupCount)
        root.addView(groupPreview)

        batchNav = LinearLayout(this)
        root.addView(batchNav)
        batchNav.addView(button("دسته قبل") { showBatch((batchIndex - 1).coerceAtLeast(0)) }, rowParam())
        batchNav.addView(button("دسته بعد") { showBatch((batchIndex + 1).coerceAtMost(batches.lastIndex)) }, rowParam())
        reviewedButton = button("علامت‌گذاری: این دسته را دستی بررسی کردم") { toggleReviewed() }
        root.addView(reviewedButton)
        root.addView(button("کپی شماره‌های این دسته (برای مدیریت دستی)") { copyBatch() })
        root.addView(text("وضعیت دسته‌ها فقط در زمان باز بودن این صفحه نگهداری می‌شود. «بررسی‌شده» به معنی ارسال یا تحویل پیام نیست.", 12f))

        root.addView(button("آزمایش باز شدن پیام‌رسان انتخاب‌شده") { testOpenApp() })
        root.addView(button("کپی متن پیام (راه جایگزین)") { copyDraft() })
        root.addView(button("اشتراک پیام / عکس / ویدیو در برنامه انتخاب‌شده") { shareInApp() })
        status = text("", 13f)
        root.addView(status)
        root.addView(text(
            "مهم: مخاطبان داخل تلگرام، واتساپ، روبیکا و بله الزاماً با شماره‌های دفترچه قابل تطبیق نیستند. " +
            "SMS Bot هیچ گیرنده‌ای را در برنامه مقصد انتخاب نمی‌کند و ارسال خودکار انبوه انجام نمی‌دهد. " +
            "در صفحه رسمی پیام‌رسان، گیرنده/گروه/کانال مورد نظر را خودتان انتخاب و ارسال را تأیید کنید.", 12f))
    }

    private fun setPlatform(platform: Platform) {
        currentPlatform = platform
        chunkSize.setText(plan().groupSize.toString())
        policyText.text = when (platform) {
            Platform.WHATSAPP ->
                "واتساپ | فهرست Broadcast تا ۲۵۶ مخاطب؛ گیرندگان باید شماره شما را ذخیره داشته باشند. " +
                "در بعضی کشورها ممکن است محدودیت ماهانه وجود داشته باشد. این محدودیت تعداد پیام روزانه نیست."
            Platform.TELEGRAM ->
                "تلگرام | گروه تا ۲۰۰٬۰۰۰ عضو و کانال با مشترکان نامحدود (راهنمای رسمی). " +
                "محدودیت ۲۰ پیام در دقیقه مربوط به «ربات داخل یک گروه» است، نه تعداد دریافت‌کنندگان در اشتراک‌گذاری عادی."
            Platform.RUBIKA ->
                "روبیکا | سقف قابل اتکای عمومی برای ارسال دسته‌ای از حساب شخصی تأیید نشد؛ برنامه عددی را به‌عنوان سقف رسمی اعمال نمی‌کند."
            Platform.BALE ->
                "بله | سقف قابل اتکای عمومی برای ارسال دسته‌ای از حساب شخصی تأیید نشد؛ برنامه عددی را به‌عنوان سقف رسمی اعمال نمی‌کند."
        }
        // A WhatsApp broadcast list has a documented maximum size, but batching a
        // separate address book DOES NOT create WhatsApp broadcast lists.
        makeBatches()
        status.text = "کانال: ${platform.label}. برای ارسال، مقصد را داخل اپ رسمی انتخاب کنید."
    }

    private fun makeBatches() {
        val state = plan()
        val requested = (chunkSize.text.toString().toIntOrNull() ?: 20)
            .coerceIn(1, BatchPlanner.MAX_LOCAL_GROUP_SIZE)
        chunkSize.setText(requested.toString())
        val revised = BatchPlanner.groupIndices(phones.size, requested)
        if (state.groupSize != requested || state.groups != revised) {
            // A changed address set/size invalidates earlier manual progress.
            state.manuallyReviewed.clear()
            state.currentIndex = 0
        }
        state.groupSize = requested
        state.groups = revised
        batches = revised
        batchIndex = state.currentIndex.coerceIn(0, (batches.size - 1).coerceAtLeast(0))
        showBatch(batchIndex)
    }

    private fun showBatch(index: Int) {
        if (batches.isEmpty()) {
            groupCount.text = "هیچ گیرنده‌ای انتخاب نشده است."
            groupPreview.text = "برای برنامه‌ریزی دسته‌ها، ابتدا در صفحه SMS مخاطبان را انتخاب کنید."
            reviewedButton.isEnabled = false
            return
        }
        reviewedButton.isEnabled = true
        val state = plan()
        batchIndex = index.coerceIn(0, batches.lastIndex)
        state.currentIndex = batchIndex
        val batch = batches[batchIndex]
        groupCount.text = "کانال ${currentPlatform.label} | دسته ${batchIndex + 1} از ${batches.size} | ${batch.size} شماره | مرور دستی ${state.manuallyReviewed.size}/${batches.size}"
        groupPreview.text = batch.take(12).joinToString("\n") { i ->
            "${i + 1}. ${names.getOrNull(i) ?: ""} | ${phones[i]}"
        } + if (batch.size > 12) "\\n... و ${batch.size - 12} شماره دیگر" else ""
        reviewedButton.text = if (batchIndex in state.manuallyReviewed) {
            "لغو علامت بررسی دستی این دسته"
        } else {
            "این دسته را خودم بررسی کردم (نه تأیید ارسال)"
        }
    }

    private fun toggleReviewed() {
        if (batches.isEmpty()) return
        val state = plan()
        if (!state.manuallyReviewed.add(batchIndex)) state.manuallyReviewed.remove(batchIndex)
        showBatch(batchIndex)
        status.text = "این علامت فقط برای پیگیری دستی است؛ SMS Bot ارسال در پیام‌رسان را تأیید یا گزارش نمی‌کند."
    }

    private fun copyBatch() {
        val batch = batches.getOrNull(batchIndex)
        if (batch == null) { status.text = "هیچ دسته‌ای موجود نیست."; return }
        val content = batch.joinToString("\n") { phones[it] }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("SMS Bot batch", content))
        status.text = "فهرست ${batch.size} شماره کپی شد. این کار به‌معنی ارسال یا عضویت افراد در پیام‌رسان نیست."
    }

    private fun pickMedia(mime: String) {
        pendingMediaType = mime
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(picker, MEDIA_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != MEDIA_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // The current picker may grant only transient access.
        }
        mediaUri = uri
        val detected = contentResolver.getType(uri).orEmpty()
        mediaMime = if (detected.startsWith("image/") || detected.startsWith("video/")) detected else pendingMediaType
        mediaText.text = "رسانه انتخاب شد: ${mediaMime}"
    }

    private fun copyDraft() {
        val draft = message.text.toString().trim()
        if (draft.isBlank()) { status.text = "برای کپی ابتدا متن وارد کنید."; return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("SMS Bot message", draft))
        status.text = "متن کپی شد. در پیام‌رسان مقصد گفتگوی مورد نظر را باز و متن را جای‌گذاری کنید."
    }

    private fun testOpenApp() {
        for (pkg in currentPlatform.packages) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: continue
                startActivity(launchIntent)
                status.text = "برنامه ${currentPlatform.label} باز شد؛ این آزمایش چیزی ارسال نمی‌کند."
                return
            } catch (_: ActivityNotFoundException) {
                // Try other installed variants.
            } catch (_: SecurityException) {
                // Try other installed variants.
            }
        }
        AlertDialog.Builder(this)
            .setTitle("برنامه ${currentPlatform.label} باز نشد")
            .setMessage("نسخه برنامه ممکن است نصب نباشد، شناسه متفاوت داشته باشد یا از این مسیر پشتیبانی نکند. دکمه اشتراک را امتحان کنید تا انتخاب‌گر اندروید در دسترس باشد.")
            .setNegativeButton("بستن", null)
            .setPositiveButton("صفحه رسمی") { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentPlatform.officialUrl))) }
                catch (e: Exception) { status.text = "مرورگر هم در دسترس نیست: ${e.javaClass.simpleName}" }
            }.show()
    }

    private fun shareInApp() {
        val draft = message.text.toString().trim()
        val uri = mediaUri
        if (draft.isBlank() && uri == null) {
            status.text = "ابتدا متن، عکس یا ویدیو انتخاب کنید."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("اشتراک در ${currentPlatform.label}")
            .setMessage(
                "در برنامه مقصد، شما باید گیرندگان و دکمه ارسال را انتخاب کنید. " +
                "تقسیم‌بندی نمایش‌داده‌شده در SMS Bot به‌صورت خودکار در ${currentPlatform.label} ساخته نمی‌شود؛ " +
                "هیچ محدودیت ضداسپم دور زده نخواهد شد. ادامه می‌دهید؟")
            .setNegativeButton("لغو", null)
            .setPositiveButton("ورود به ${currentPlatform.label}") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = if (uri == null) "text/plain" else (mediaMime ?: "*/*")
                    if (draft.isNotBlank()) putExtra(Intent.EXTRA_TEXT, draft)
                    if (uri != null) {
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("media", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                var opened = false
                for (pkg in currentPlatform.packages) {
                    try {
                        startActivity(Intent(send).setPackage(pkg))
                        opened = true
                        break
                    } catch (_: ActivityNotFoundException) {
                        // Different app variant or not installed.
                    } catch (_: SecurityException) {
                        // Installed application did not accept this share intent.
                    }
                }
                if (!opened) {
                    AlertDialog.Builder(this).setTitle("برنامه مقصد پیدا نشد")
                        .setMessage(
                            "ممکن است ${currentPlatform.label} نصب نباشد یا از اشتراک این نوع فایل پشتیبانی نکند. " +
                            "می‌توانید برنامه مقصد را از پنجره اشتراک اندروید خودتان انتخاب کنید.")
                        .setNegativeButton("انصراف", null)
                        .setPositiveButton("انتخاب از برنامه‌ها") { _, _ ->
                            try {
                                startActivity(Intent.createChooser(send, "اشتراک با پیام‌رسان"))
                                status.text = "پنجره اشتراک اندروید باز شد؛ خودتان مقصد را انتخاب و ارسال را تأیید کنید."
                            } catch (e: Exception) {
                                status.text = "پنجره اشتراک باز نشد: ${e.javaClass.simpleName}. متن را کپی کنید یا نوع رسانه دیگری را امتحان کنید."
                            }
                        }.show()
                }
                if (opened) status.text = "درخواست اشتراک به پیام‌رسان داده شد؛ ارسال/تحویل تأیید نشده است."
            }.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mediaUri?.let { outState.putString(KEY_MEDIA, it.toString()) }
        mediaMime?.let { outState.putString(KEY_MIME, it) }
    }
}
