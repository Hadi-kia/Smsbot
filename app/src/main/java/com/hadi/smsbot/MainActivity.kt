package com.hadi.smsbot

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telephony.SmsManager
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Manual, consent-first private SMS prototype.
 * No SMS sent on application start, permission grant or import.
 */
@Suppress("DEPRECATION")
class MainActivity : Activity() {
    companion object {
        private const val REQUEST_READ = 41
        private const val REQUEST_SEND = 42
        private const val REQUEST_CSV = 43
        private const val BATCH_LIMIT = 100
        private const val SEND_INTERVAL_MS = 6_000L
    }

    private lateinit var numbers: NumberLoader
    private lateinit var status: TextView
    private lateinit var count: TextView
    private lateinit var list: ListView
    private lateinit var messageInput: EditText
    private lateinit var consent: CheckBox
    private lateinit var sendButton: Button
    private lateinit var stopButton: Button
    private lateinit var nextManualButton: Button
    private var manualBatch: List<Recipient> = emptyList()
    private var manualPosition = 0
    private var manualText = ""

    private var phoneCandidates: List<Candidate> = emptyList()
    private var fileCandidates: List<Candidate> = emptyList()
    private var recipients: List<Recipient> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private var sending = false
    private var destinationIndex = 0
    private var submitted = 0
    private var rejected = 0
    private var currentBatch: List<Recipient> = emptyList()
    private var currentMessage = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        numbers = NumberLoader(this)
        createUi()
        status.text = "برای شروع «خواندن دفترچه + SMS» را انتخاب کنید. هیچ پیامی خودکار ارسال نمی‌شود."
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(px(12), px(12), px(12), px(12))
        }
        setContentView(root)
        root.addView(TextView(this).apply { text = "SMS Bot — نسخه آزمایشی"; textSize = 21f })
        status = TextView(this).apply { textSize = 13f }
        root.addView(status)

        val importRow = LinearLayout(this)
        root.addView(importRow)
        importRow.addView(button("خواندن دفترچه + SMS") { readWithPermission() }, rowLayout())
        importRow.addView(button("افزودن CSV") { chooseCsv() }, rowLayout())

        root.addView(button("تلگرام / واتساپ / روبیکا / بله و ارسال رسانه") { openChannelHub() })

        val selectRow = LinearLayout(this)
        root.addView(selectRow)
        selectRow.addView(button("انتخاب همه") { selectAll(true) }, rowLayout())
        selectRow.addView(button("لغو انتخاب") { selectAll(false) }, rowLayout())

        count = TextView(this).apply { text = "هیچ شماره‌ای بارگذاری نشده"; textSize = 14f }
        root.addView(count)
        list = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            setOnItemClickListener { _, _, _, _ -> updateCount() }
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(this).apply { text = "متن پیام:" })
        messageInput = EditText(this).apply {
            hint = "پیامک خود را بنویسید"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
        }
        root.addView(messageInput)
        consent = CheckBox(this).apply {
            text = "تأیید می‌کنم همه گیرندگان انتخاب‌شده رضایت دریافت این پیام را دارند."
            textSize = 12f
        }
        root.addView(consent)

        val actions = LinearLayout(this)
        root.addView(actions)
        sendButton = button("ارسال مستقیم با مجوز SMS") { preview() }
        actions.addView(sendButton, rowLayout())
        stopButton = button("توقف") { stop("صف متوقف شد؛ پیامک‌های ارسال‌شده قابل بازگرداندن نیستند.") }
        stopButton.isEnabled = false
        actions.addView(stopButton, rowLayout())

        val manualRow = LinearLayout(this)
        root.addView(manualRow)
        manualRow.addView(button("SMS با برنامه گوشی") { prepareManualSms() }, rowLayout())
        nextManualButton = button("شماره بعدی") { nextManualSms() }
        nextManualButton.isEnabled = false
        manualRow.addView(nextManualButton, rowLayout())
        root.addView(button("تست SMS به شماره خودم (بدون مجوز ارسال)") { askOwnNumber() })

        root.addView(TextView(this).apply {
            textSize = 11f
            text = "سقف آزمایشی: ۱۰۰ شماره در هر نوبت، ۶ ثانیه فاصله. ارسال با سیم‌کارت هزینه دارد. «شروع ارسال» تضمین تحویل پیام نیست؛ خروج از برنامه صف را متوقف می‌کند."
        })
    }

    private fun rowLayout() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    private fun px(dp: Int) = (dp * resources.displayMetrics.density).toInt()
    private fun button(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 12f
        setOnClickListener { action() }
    }

    /**
     * ACTION_SENDTO with smsto: delegates to the installed/default SMS app.
     * No SEND_SMS permission is required by SMS Bot for this explicit compose flow.
     * Calling startActivity DOES NOT mean the message was sent.
     */
    private fun openSmsComposer(number: String, body: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).apply {
            putExtra("sms_body", body)
        }
        return try {
            startActivity(intent)
            status.text = "صفحه نوشتن SMS برای $number درخواست شد. ارسال را داخل برنامه پیامک خود تأیید کنید."
            true
        } catch (e: ActivityNotFoundException) {
            status.text = "هیچ برنامه SMS برای این گوشی پیدا نشد؛ یک برنامه پیامک پیش‌فرض فعال کنید."
            false
        } catch (e: SecurityException) {
            status.text = "اندروید باز کردن برنامه SMS را محدود کرد: ${e.javaClass.simpleName}"
            false
        } catch (e: Exception) {
            status.text = "باز کردن برنامه SMS ناموفق بود: ${e.javaClass.simpleName}"
            false
        }
    }

    private fun askOwnNumber() {
        if (sending) return
        val input = EditText(this).apply {
            hint = "مثلاً +98912..."
            inputType = InputType.TYPE_CLASS_PHONE
        }
        AlertDialog.Builder(this)
            .setTitle("آزمایش بدون دسترسی حساس SMS")
            .setMessage("فقط شماره‌ای متعلق به خودتان وارد کنید. این مرحله پیام را ارسال نمی‌کند؛ صفحه نوشتن پیام را باز می‌کند.")
            .setView(input)
            .setNegativeButton("لغو", null)
            .setPositiveButton("باز کردن برنامه پیامک") { _, _ ->
                val number = input.text.toString().trim()
                if (number.count(Char::isDigit) !in 7..15) {
                    status.text = "شماره تست معتبر نیست."
                    return@setPositiveButton
                }
                val body = messageInput.text.toString().trim().ifBlank { "آزمایش SMS Bot" }
                openSmsComposer(number, body)
            }.show()
    }

    private fun prepareManualSms() {
        if (sending) return
        val chosen = selected()
        val body = messageInput.text.toString().trim()
        when {
            chosen.isEmpty() -> { status.text = "یک یا چند گیرنده انتخاب کنید."; return }
            body.isEmpty() -> { status.text = "متن پیام را بنویسید."; return }
            !consent.isChecked -> { status.text = "ابتدا رضایت همه گیرندگان انتخاب‌شده را تأیید کنید."; return }
        }
        AlertDialog.Builder(this)
            .setTitle("آماده‌سازی دستی پیامک")
            .setMessage("${chosen.size} مخاطب انتخاب شده‌اند. هر بار فقط شماره یک نفر داخل برنامه پیامک باز می‌شود. بعد از بازگشت، برای شماره بعدی خودتان تصمیم می‌گیرید. SMS Bot از ارسال موفق مطلع نمی‌شود و ممکن است هزینه سیم‌کارت داشته باشد.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("شروع از اولین گیرنده") { _, _ ->
                manualBatch = chosen.toList()
                manualText = body
                manualPosition = 0
                nextManualSms()
            }.show()
    }

    private fun nextManualSms() {
        if (sending) return
        if (manualPosition >= manualBatch.size) {
            nextManualButton.isEnabled = false
            status.text = "پایان فهرست دستی: ${manualBatch.size} پنجره نوشتن SMS درخواست شده است؛ هیچ وضعیت ارسال یا تحویل ثبت نشده."
            return
        }
        val recipient = manualBatch[manualPosition]
        if (openSmsComposer(recipient.phone, manualText)) {
            manualPosition++
            nextManualButton.isEnabled = manualPosition < manualBatch.size
        }
    }

    private fun openChannelHub() {
        if (sending) {
            status.text = "ابتدا صف SMS را متوقف کنید."
            return
        }
        val chosen = selected()
        if (chosen.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("انتخاب گیرندگان")
                .setMessage("فعلاً گیرنده‌ای انتخاب نشده است. برای برنامه‌ریزی گروه‌ها مخاطبان را انتخاب کنید. برای اشتراک متن و رسانه می‌توانید بدون فهرست هم ادامه دهید.")
                .setNegativeButton("لغو", null)
                .setPositiveButton("ادامه") { _, _ -> startChannelHub(chosen) }
                .show()
        } else startChannelHub(chosen)
    }

    private fun startChannelHub(chosen: List<Recipient>) {
        startActivity(Intent(this, ChannelHubActivity::class.java).apply {
            putStringArrayListExtra("phones", ArrayList(chosen.map { it.phone }))
            putStringArrayListExtra("names", ArrayList(chosen.map { it.name }))
            putExtra("draft", messageInput.text.toString())
        })
    }

    private fun readWithPermission() {
        if (sending) return
        val missing = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_SMS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            loadPhone()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("دسترسی به شماره‌ها")
            .setMessage("شماره‌های دفترچه و فرستنده/گیرنده SMS خوانده می‌شوند؛ متن پیامک‌ها خوانده نمی‌شود. اگر مجوز تاریخچه مسدود بود CSV را وارد کنید.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("درخواست دسترسی") { _, _ -> requestPermissions(missing.toTypedArray(), REQUEST_READ) }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_READ -> loadPhone()
            REQUEST_SEND -> status.text =
                if (checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED)
                    "مجوز ارسال تأیید شد؛ پیش‌نمایش و تأیید نهایی را دوباره انجام دهید."
                else "مجوز ارسال مستقیم SMS داده نشد. از دکمه «SMS با برنامه گوشی» استفاده کنید."
        }
    }

    private fun loadPhone() {
        if (sending) return
        status.text = "در حال بررسی دفترچه و تاریخچه..."
        Thread {
            val result = numbers.fromDevice()
            runOnUiThread {
                if (isFinishing || isDestroyed || sending) return@runOnUiThread
                phoneCandidates = result.candidates
                rebuild()
                status.text = "دفترچه: ${if (result.contactsOk) "مجاز" else "ناموجود"} | " +
                    "تاریخچه SMS: ${if (result.smsOk) "مجاز" else "ناموجود"} | " +
                    "شماره یکتا: ${recipients.size}\n${result.info}"
            }
        }.start()
    }

    private fun rebuild() {
        recipients = RecipientMerge.merge(phoneCandidates + fileCandidates, numbers::normalize)
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, recipients.map {
            val sources = listOfNotNull(
                "دفترچه".takeIf { _ -> it.contacts },
                "SMS".takeIf { _ -> it.sms },
                "CSV".takeIf { _ -> it.csv }
            ).joinToString("+")
            "${it.name} • ${it.phone} ($sources)"
        })
        list.clearChoices()
        updateCount()
    }

    private fun selected(): List<Recipient> = recipients.filterIndexed { index, _ -> list.isItemChecked(index) }
    private fun updateCount() {
        count.text = "کل شماره‌های یکتا: ${recipients.size} | انتخاب‌شده: ${selected().size}"
    }

    private fun selectAll(yes: Boolean) {
        if (sending) return
        for (i in recipients.indices) list.setItemChecked(i, yes)
        updateCount()
    }

    private fun chooseCsv() {
        if (sending) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_CSV)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CSV || resultCode != RESULT_OK || sending) return
        val uri = data?.data ?: return
        status.text = "در حال واردکردن CSV..."
        Thread {
            try {
                val imported = numbers.fromCsv(uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed || sending) return@runOnUiThread
                    fileCandidates = (fileCandidates + imported).take(50_000)
                    rebuild()
                    status.text = "${imported.size} شماره از CSV بررسی شد؛ کل یکتا: ${recipients.size}"
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خواندن CSV ناموفق بود: ${e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun preview() {
        if (sending) return
        val chosen = selected()
        val message = messageInput.text.toString().trim()
        when {
            chosen.isEmpty() -> { status.text = "حداقل یک گیرنده انتخاب کنید."; return }
            chosen.size > BATCH_LIMIT -> { status.text = "حداکثر $BATCH_LIMIT گیرنده در هر نوبت مجاز است."; return }
            message.isBlank() -> { status.text = "متن پیام خالی است."; return }
            !consent.isChecked -> { status.text = "ابتدا رضایت گیرندگان را تأیید کنید."; return }
            !packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING) -> {
                status.text = "این دستگاه از SMS سیم‌کارت پشتیبانی نمی‌کند."; return
            }
        }
        val sms = try { SmsManager.getDefault() } catch (e: Exception) {
            status.text = "سرویس SMS در دسترس نیست."; return
        }
        val parts = try { sms.divideMessage(message).size } catch (e: Exception) {
            status.text = "متن پیام برای SMS قابل پردازش نیست."; return
        }
        if (parts !in 1..6) { status.text = "طول پیام از ۶ بخش SMS بیشتر است."; return }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this)
                .setTitle("مجوز ارسال واقعی SMS")
                .setMessage("برای ارسال باید سیستم مجوز SEND_SMS را تأیید کند. پس از درخواست، دوباره به‌صورت دستی پیام را تأیید کنید.")
                .setNegativeButton("لغو", null)
                .setNeutralButton("با برنامه پیامک گوشی") { _, _ -> prepareManualSms() }
                .setPositiveButton("درخواست مجوز") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.SEND_SMS), REQUEST_SEND)
                }.show()
            return
        }
        val sample = chosen.take(5).joinToString("\n") { "• ${it.name}: ${it.phone}" }
        AlertDialog.Builder(this)
            .setTitle("تأیید نهایی ارسال واقعی")
            .setMessage("گیرندگان: ${chosen.size}\nبخش SMS برای هر نفر: $parts\n" +
                "بخش‌های احتمالی قابل شارژ: ${chosen.size * parts}\n" +
                "فاصله شروع ارسال‌ها: ۶ ثانیه\n\nنمونه:\n$sample\n\nمتن پیام:\n$message\n\n" +
                "ارسال ممکن است هزینه داشته باشد و قابل برگشت نیست.")
            .setNegativeButton("انصراف", null)
            .setPositiveButton("ارسال به ${chosen.size} نفر") { _, _ -> begin(chosen, message) }
            .show()
    }

    private fun begin(chosen: List<Recipient>, message: String) {
        if (sending || checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) return
        currentBatch = chosen.toList()
        currentMessage = message
        destinationIndex = 0
        submitted = 0
        rejected = 0
        sending = true
        list.isEnabled = false
        sendButton.isEnabled = false
        stopButton.isEnabled = true
        next()
    }

    private fun next() {
        if (!sending) return
        if (destinationIndex >= currentBatch.size) {
            finishQueue()
            return
        }
        val recipient = currentBatch[destinationIndex++]
        try {
            if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                stop("مجوز SEND_SMS لغو شد.")
                return
            }
            val manager = SmsManager.getDefault()
            val parts = manager.divideMessage(currentMessage)
            if (parts.size == 1) {
                manager.sendTextMessage(recipient.phone, null, parts[0], null, null)
            } else {
                manager.sendMultipartTextMessage(recipient.phone, null, parts, null, null)
            }
            submitted++
        } catch (e: Exception) {
            rejected++
            status.text = "خطای شروع ارسال به ${recipient.phone}: ${e.javaClass.simpleName}"
        }
        showStats()
        handler.postDelayed({ next() }, SEND_INTERVAL_MS)
    }

    private fun showStats() {
        status.text = "${if (sending) "در حال ارسال" else "صف پایان یافته/متوقف شده"} | " +
            "بررسی‌شده: $destinationIndex/${currentBatch.size} | " +
            "شروع ارسال به شبکه: $submitted | خطای فوری: $rejected\n" +
            "تحویل به گیرنده توسط این نسخه تأیید نمی‌شود."
    }

    private fun finishQueue() {
        sending = false
        list.isEnabled = true
        sendButton.isEnabled = true
        stopButton.isEnabled = false
        showStats()
    }

    private fun stop(note: String) {
        if (!sending) return
        handler.removeCallbacksAndMessages(null)
        finishQueue()
        status.append("\n$note")
    }

    override fun onStop() {
        if (sending) stop("برنامه از حالت فعال خارج شد؛ شماره‌های باقیمانده ارسال نمی‌شوند.")
        super.onStop()
    }
}
