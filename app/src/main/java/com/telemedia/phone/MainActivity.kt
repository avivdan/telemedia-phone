package com.telemedia.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat

/**
 * The whole app on one screen. Polls the Node control server
 * (node-bootstrap/main.js) and shows the card for the current step:
 * Telegram login → (automatic own-key setup) → head-start groups →
 * Add to Stremio → done, plus "Use from another device" (the LAN address).
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("ui", Context.MODE_PRIVATE) }

    private lateinit var status: TextView
    private lateinit var loginCard: View
    private lateinit var loginLink: Button
    private lateinit var loginLinkHint: TextView
    private lateinit var loginUsePhone: Button
    private lateinit var phoneGroup: View
    private lateinit var phone: EditText
    private lateinit var sendPhone: Button
    private lateinit var codeGroup: View
    private lateinit var codeText: TextView
    private lateinit var code: EditText
    private lateinit var sendCode: Button
    private lateinit var loginError: TextView
    private lateinit var passwordCard: View
    private lateinit var passwordText: TextView
    private lateinit var password: EditText
    private lateinit var sendPassword: Button
    private lateinit var passwordError: TextView
    private lateinit var configCard: View
    private lateinit var configPhone: EditText
    private lateinit var configSendPhone: Button
    private lateinit var configCodeGroup: View
    private lateinit var configCode: EditText
    private lateinit var configSendCode: Button
    private lateinit var configManualGroup: View
    private lateinit var apiId: EditText
    private lateinit var apiHash: EditText
    private lateinit var saveKeys: Button
    private lateinit var configError: TextView
    private lateinit var groupsCard: View
    private lateinit var groupsList: LinearLayout
    private lateinit var groupsJoin: Button
    private lateinit var groupsSkip: Button
    private lateinit var groupsError: TextView
    private lateinit var stremioCard: View
    private lateinit var stremioAdd: Button
    private lateinit var stremioNote: TextView
    private lateinit var stremioGet: Button
    private lateinit var stremioError: TextView
    private lateinit var doneCard: View
    private lateinit var doneText: TextView
    private lateinit var openStremio: Button
    private lateinit var showGroups: Button
    private lateinit var lanCard: View
    private lateinit var lanQr: ImageView
    private lateinit var lanUrl: TextView
    private lateinit var lanAdd: Button
    private lateinit var lanNote: TextView
    private lateinit var lanError: TextView
    private lateinit var batteryCard: View
    private lateinit var footer: View
    private lateinit var logout: Button

    private var phoneMode = false
    private var linkOpened = false
    private var stremioOpened = false
    private var lanOpened = false
    private var shownLanUrl: String? = null
    private var manualKeys = false
    private var configCodeShown = false
    private var shownGroups: String? = null
    private val groupChecks = mutableMapOf<String, Boolean>()
    private var restartingUntil = 0L
    // Errors from the last action, shown until the next action on that card.
    private val actionErrors = mutableMapOf<TextView, String>()

    private val poll = object : Runnable {
        override fun run() {
            Thread {
                val s = ControlClient.status()
                runOnUiThread { if (!isFinishing) render(s) }
            }.start()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setUpActions()
        askNotificationsOnce()
        NodeService.start(this)
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun bindViews() {
        status = findViewById(R.id.status)
        loginCard = findViewById(R.id.login_card)
        loginLink = findViewById(R.id.login_link)
        loginLinkHint = findViewById(R.id.login_link_hint)
        loginUsePhone = findViewById(R.id.login_use_phone)
        phoneGroup = findViewById(R.id.phone_group)
        phone = findViewById(R.id.phone)
        sendPhone = findViewById(R.id.send_phone)
        codeGroup = findViewById(R.id.code_group)
        codeText = findViewById(R.id.code_text)
        code = findViewById(R.id.code)
        sendCode = findViewById(R.id.send_code)
        loginError = findViewById(R.id.login_error)
        passwordCard = findViewById(R.id.password_card)
        passwordText = findViewById(R.id.password_text)
        password = findViewById(R.id.password)
        sendPassword = findViewById(R.id.send_password)
        passwordError = findViewById(R.id.password_error)
        configCard = findViewById(R.id.config_card)
        configPhone = findViewById(R.id.config_phone)
        configSendPhone = findViewById(R.id.config_send_phone)
        configCodeGroup = findViewById(R.id.config_code_group)
        configCode = findViewById(R.id.config_code)
        configSendCode = findViewById(R.id.config_send_code)
        configManualGroup = findViewById(R.id.config_manual_group)
        apiId = findViewById(R.id.api_id)
        apiHash = findViewById(R.id.api_hash)
        saveKeys = findViewById(R.id.save_keys)
        configError = findViewById(R.id.config_error)
        groupsCard = findViewById(R.id.groups_card)
        groupsList = findViewById(R.id.groups_list)
        groupsJoin = findViewById(R.id.groups_join)
        groupsSkip = findViewById(R.id.groups_skip)
        groupsError = findViewById(R.id.groups_error)
        stremioCard = findViewById(R.id.stremio_card)
        stremioAdd = findViewById(R.id.stremio_add)
        stremioNote = findViewById(R.id.stremio_note)
        stremioGet = findViewById(R.id.stremio_get)
        stremioError = findViewById(R.id.stremio_error)
        doneCard = findViewById(R.id.done_card)
        doneText = findViewById(R.id.done_text)
        openStremio = findViewById(R.id.open_stremio)
        showGroups = findViewById(R.id.show_groups)
        lanCard = findViewById(R.id.lan_card)
        lanQr = findViewById(R.id.lan_qr)
        lanUrl = findViewById(R.id.lan_url)
        lanAdd = findViewById(R.id.lan_add)
        lanNote = findViewById(R.id.lan_note)
        lanError = findViewById(R.id.lan_error)
        batteryCard = findViewById(R.id.battery_card)
        footer = findViewById(R.id.footer)
        logout = findViewById(R.id.logout)
    }

    private fun setUpActions() {
        // Telegram login: the link opens the Telegram app on this phone.
        loginLink.setOnClickListener {
            action("/login/link", JSONObject(), loginLink, loginError) { reply ->
                if (reply.isNull("link")) return@action // already logged in
                if (openLink(reply.getString("link"))) {
                    linkOpened = true
                } else {
                    showError(loginError, getString(R.string.no_telegram))
                    phoneMode = true
                }
            }
        }
        loginUsePhone.setOnClickListener {
            phoneMode = true
            phoneGroup.visibility = View.VISIBLE
            focus(phone)
        }
        sendPhone.setOnClickListener {
            val number = toInternational(phone.text.toString())
            action("/login/phone", JSONObject().put("phone", number), sendPhone, loginError) {
                code.setText("")
                codeGroup.visibility = View.VISIBLE
                focus(code)
            }
        }
        sendCode.setOnClickListener {
            action("/login/code", JSONObject().put("code", code.text.toString()), sendCode, loginError)
        }
        sendPassword.setOnClickListener {
            action("/password", JSONObject().put("password", password.text.toString()), sendPassword, passwordError) {
                password.setText("")
            }
        }
        onDone(phone, sendPhone)
        onDone(code, sendCode)
        onDone(password, sendPassword)

        // Own API key by hand.
        configSendPhone.setOnClickListener {
            val number = toInternational(configPhone.text.toString())
            action("/config/phone", JSONObject().put("phone", number), configSendPhone, configError) {
                configCodeShown = true
                configCodeGroup.visibility = View.VISIBLE
                focus(configCode)
            }
        }
        configSendCode.setOnClickListener {
            action("/config/code", JSONObject().put("code", configCode.text.toString()), configSendCode, configError)
        }
        findViewById<Button>(R.id.config_manual_toggle).setOnClickListener {
            manualKeys = !manualKeys
            configManualGroup.visibility = if (manualKeys) View.VISIBLE else View.GONE
            if (manualKeys) focus(apiId)
        }
        saveKeys.setOnClickListener {
            val body = JSONObject().put("apiId", apiId.text.toString()).put("apiHash", apiHash.text.toString())
            action("/config/keys", body, saveKeys, configError)
        }

        // Head-start groups.
        groupsJoin.setOnClickListener {
            val links = JSONArray(groupChecks.filterValues { it }.keys.toList())
            action("/groups/join", JSONObject().put("links", links), groupsJoin, groupsError)
        }
        groupsSkip.setOnClickListener { action("/groups/skip", JSONObject(), groupsSkip, groupsError) }
        showGroups.setOnClickListener {
            shownGroups = null
            action("/groups/show", JSONObject(), showGroups, null)
        }

        // Stremio.
        stremioAdd.setOnClickListener {
            action("/stremio/link", JSONObject(), stremioAdd, stremioError) { reply ->
                if (openLink(reply.getString("link"))) stremioOpened = true
                else showError(stremioError, getString(R.string.no_browser))
            }
        }
        stremioGet.setOnClickListener { Stremio.openStoreListing(this) }
        openStremio.setOnClickListener { if (!Stremio.open(this)) Stremio.openStoreListing(this) }

        // Other devices: the LAN address into the Stremio account, or copy it.
        lanAdd.setOnClickListener {
            action("/lan/link", JSONObject(), lanAdd, lanError) { reply ->
                if (openLink(reply.getString("link"))) lanOpened = true
                else showError(lanError, getString(R.string.no_browser))
            }
        }
        findViewById<Button>(R.id.lan_copy).setOnClickListener {
            val url = shownLanUrl ?: return@setOnClickListener
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url))
            Toast.makeText(this, R.string.lan_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.battery_allow).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.restart).setOnClickListener { restartNode() }
        logout.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Log this phone out of Telegram?")
                .setPositiveButton(R.string.logout) { _, _ ->
                    action("/logout", JSONObject(), logout, null) { restartNode() }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    // ------------------------------------------------------------------------
    // Rendering the polled state.
    // ------------------------------------------------------------------------
    private fun render(polled: JSONObject?) {
        val s = if (System.currentTimeMillis() < restartingUntil) null else polled
        val state = s?.optString("state") ?: "offline"
        val cards = listOf(loginCard, passwordCard, configCard, groupsCard, stremioCard, doneCard, lanCard, batteryCard)
        var visible = setOf<View>()
        val text: String? = when (state) {
            "offline", "starting" -> getString(R.string.starting)
            "logging_in" -> getString(R.string.logging_in)
            "starting_addon" -> getString(R.string.starting_addon)
            "need_login" -> {
                visible = setOf(loginCard)
                renderLogin(s!!.optJSONObject("login") ?: JSONObject())
                null
            }
            "need_password" -> {
                visible = setOf(passwordCard)
                renderPassword(s!!)
                null
            }
            "auto_setup" -> getString(
                if (s!!.optString("setupStep") == "switching") R.string.auto_setup_switching
                else R.string.auto_setup_working,
            )
            "need_config" -> {
                visible = setOf(configCard)
                renderConfig(s!!)
            }
            "running" -> {
                visible = renderRunning(s!!)
                runningText(s)
            }
            "error" -> "Something went wrong: ${s!!.optString("error")}\n\nTap Restart below. " +
                "If it keeps happening, log out and log in again."
            else -> state
        }
        for (card in cards) card.visibility = if (card in visible) View.VISIBLE else View.GONE
        status.text = text ?: ""
        status.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        footer.visibility = if (state == "running" || state == "error") View.VISIBLE else View.GONE
        logout.visibility = if (state == "running") View.VISIBLE else View.GONE
    }

    private fun renderLogin(view: JSONObject) {
        val method = view.optStr("method")
        val codeStep = method == "phone" && view.optStr("step") == "code"
        if (method == "phone") phoneMode = true
        phoneGroup.visibility = if (phoneMode) View.VISIBLE else View.GONE
        codeGroup.visibility = if (codeStep) View.VISIBLE else View.GONE
        codeText.setText(if (view.optStr("codeVia") == "sms") R.string.code_sent_sms else R.string.code_sent_app)
        loginLinkHint.setText(if (linkOpened) R.string.login_link_opened else R.string.login_link_hint)
        setError(loginError, view.optStr("error"))
    }

    private fun renderPassword(s: JSONObject) {
        val hint = s.optStr("hint")
        passwordText.text = getString(R.string.password_intro) + if (hint.isNotBlank()) "\n(Hint: $hint)" else ""
        setError(passwordError, if (s.optBoolean("wrong")) getString(R.string.password_wrong) else "")
    }

    private fun renderConfig(s: JSONObject): String? {
        val step = s.optStr("setupStep")
        if (step == "code_sent") configCodeShown = true
        configCodeGroup.visibility = if (configCodeShown) View.VISIBLE else View.GONE
        configManualGroup.visibility = if (manualKeys) View.VISIBLE else View.GONE
        setError(configError, if (step == "error") s.optStr("setupError") else "")
        return if (step == "reading" || step == "creating") getString(R.string.config_working) else null
    }

    private fun runningText(s: JSONObject): String {
        val results = s.optJSONObject("groups")?.optJSONObject("results")
        val joined = results?.keys()?.asSequence()?.count { results.optJSONObject(it)?.optStr("result") == "joined" } ?: 0
        return "Telemedia is running ✔" + if (joined > 0) "\nJoined $joined group${if (joined > 1) "s" else ""} ✔" else ""
    }

    // Next step after login: groups (if offered), then Stremio, then done.
    private fun renderRunning(s: JSONObject): Set<View> {
        val visible = mutableSetOf<View>()
        val groups = s.optJSONObject("groups") ?: JSONObject()
        val groupsState = groups.optStr("state")
        when {
            groupsState in setOf("loading", "offer", "joining") -> {
                visible += groupsCard
                renderGroups(groups)
            }
            !s.optBoolean("stremioConnected") -> {
                visible += stremioCard
                renderStremio(s.optJSONObject("stremio") ?: JSONObject())
            }
            else -> {
                visible += doneCard
                val installed = Stremio.installedPackage(this) != null
                doneText.setText(if (installed) R.string.done_text else R.string.done_no_stremio)
                openStremio.setText(if (installed) R.string.open_stremio else R.string.get_stremio)
                showGroups.visibility = if (s.optInt("groupsCount") > 0) View.VISIBLE else View.GONE
            }
        }
        if (groupsCard !in visible) {
            visible += lanCard
            renderLan(s.optJSONObject("lan") ?: JSONObject())
        }
        if (!ignoringBatteryOptimizations()) {
            visible += batteryCard
            askBatteryOnce()
        }
        return visible
    }

    private fun renderGroups(groups: JSONObject) {
        val state = groups.optStr("state")
        val items = groups.optJSONArray("items") ?: JSONArray()
        val results = groups.optJSONObject("results") ?: JSONObject()
        groupsJoin.isEnabled = state == "offer"
        groupsJoin.setText(if (state == "joining") R.string.groups_joining else R.string.groups_join)
        groupsSkip.visibility = if (state == "offer") View.VISIBLE else View.GONE
        setError(groupsError, groups.optStr("error"))

        val key = state + items + results
        if (key == shownGroups) return
        shownGroups = key
        groupsList.removeAllViews()
        if (state == "loading") {
            groupsList.addView(TextView(this).apply {
                setText(R.string.groups_loading)
                setTextColor(getColor(R.color.muted))
                textSize = 15f
                setPadding(0, dp(8), 0, dp(8))
            })
            return
        }
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val link = item.getString("link")
            val error = item.optStr("error")
            val joined = item.optBoolean("joined")
            val usable = !joined && error.isEmpty()
            val checked = groupChecks.getOrPut(link) { usable }
            val result = results.optJSONObject(link)
            val outcome = when {
                joined -> "✓ Already a member"
                result?.optStr("result") == "joined" -> "✓ Joined"
                result?.optStr("result") == "already" -> "✓ Already a member"
                result?.optStr("result") == "requested" -> "Join request sent: an admin has to approve it"
                result != null && result.optStr("error").isNotEmpty() -> "✗ " + result.optStr("error")
                error.isNotEmpty() -> "✗ " + error.replaceFirstChar { it.uppercase() }
                item.optBoolean("approval") -> "An admin approves new members"
                else -> ""
            }
            groupsList.addView(CheckBox(this).apply {
                text = groupLabel(item, outcome)
                setTextColor(getColor(R.color.fg))
                textSize = 16f
                minHeight = dp(52)
                setPadding(dp(8), dp(10), 0, dp(10))
                buttonTintList = ColorStateList.valueOf(getColor(R.color.accent))
                isChecked = joined || (usable && checked)
                isEnabled = usable && state == "offer"
                setOnCheckedChangeListener { _, on -> groupChecks[link] = on }
            })
        }
    }

    // "Title" in bold, then "12,345 members · Channel · note" and the outcome, smaller.
    private fun groupLabel(item: JSONObject, outcome: String): CharSequence {
        val out = SpannableStringBuilder()
        val title = item.optStr("title").ifEmpty { item.getString("link") }
        out.append(title, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val details = listOfNotNull(
            item.optInt("members").takeIf { it > 0 }?.let { NumberFormat.getIntegerInstance().format(it) + " members" },
            if (item.has("title")) (if (item.optBoolean("channel")) "Channel" else "Group") else null,
            item.optStr("note").ifEmpty { null },
        ).joinToString(" · ")
        for (line in listOf(details, outcome).filter { it.isNotEmpty() }) {
            val start = out.length
            out.append("\n").append(line)
            out.setSpan(RelativeSizeSpan(0.85f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(getColor(R.color.muted)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private fun renderLan(lan: JSONObject) {
        val url = lan.optStr("url")
        if (url != shownLanUrl) {
            shownLanUrl = url.ifEmpty { null }
            lanUrl.text = url
            if (url.isNotEmpty()) lanQr.setImageBitmap(qrBitmap(url, 600))
        }
        val onWifi = url.isNotEmpty()
        lanQr.visibility = if (onWifi) View.VISIBLE else View.GONE
        lanUrl.visibility = if (onWifi) View.VISIBLE else View.GONE
        lanAdd.isEnabled = onWifi && lan.optStr("state") != "installing"
        val note = when {
            !onWifi -> getString(R.string.lan_no_wifi)
            lan.optStr("state") == "installing" -> getString(R.string.stremio_installing)
            lan.optStr("state") == "waiting" && lanOpened -> getString(R.string.stremio_waiting)
            lan.optBoolean("needsReadd") -> getString(R.string.lan_readd)
            lan.optBoolean("inAccount") -> getString(R.string.lan_in_account)
            else -> ""
        }
        lanNote.text = note
        lanNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        setError(lanError, if (lan.optStr("state") == "error") "Couldn't add it: " + lan.optStr("error") else "")
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun renderStremio(stremio: JSONObject) {
        val note = when (stremio.optStr("state")) {
            "installing" -> getString(R.string.stremio_installing)
            "waiting" -> if (stremioOpened) getString(R.string.stremio_waiting) else ""
            "error" -> "Couldn't add it yet: " + stremio.optStr("error")
            else -> ""
        }
        stremioNote.text = note
        stremioNote.visibility = if (note.isEmpty()) View.GONE else View.VISIBLE
        stremioGet.visibility = if (Stremio.installedPackage(this) == null) View.VISIBLE else View.GONE
        setError(stremioError, "")
    }

    // ------------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------------

    /** POST an action; the button shows "Please wait…" meanwhile, errors go to [error]. */
    private fun action(
        path: String,
        body: JSONObject,
        button: Button?,
        error: TextView?,
        onOk: (JSONObject) -> Unit = {},
    ) {
        error?.let { actionErrors.remove(it); it.visibility = View.GONE }
        val label = button?.text
        button?.isEnabled = false
        button?.setText(R.string.please_wait)
        Thread {
            val reply = ControlClient.post(path, body.toString())
            runOnUiThread {
                button?.isEnabled = true
                button?.text = label
                if (reply.ok) onOk(reply.body) else error?.let { showError(it, reply.error) }
            }
        }.start()
    }

    private fun showError(view: TextView, message: String) {
        actionErrors[view] = message
        setError(view, message)
    }

    /** Shows the last action's error if there is one, else the polled [message]. */
    private fun setError(view: TextView, message: String) {
        val text = actionErrors[view] ?: message
        view.text = text
        view.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openLink(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    /** "050-123-4567" → "+972501234567" using the SIM's country; numbers with + stay as typed. */
    private fun toInternational(raw: String): String {
        val number = raw.trim()
        if (number.isEmpty() || number.startsWith("+") || number.startsWith("00")) return number
        val tm = getSystemService(TelephonyManager::class.java) ?: return number
        val country = tm.simCountryIso.orEmpty().ifEmpty { tm.networkCountryIso.orEmpty() }.uppercase()
        if (country.isEmpty()) return number
        return PhoneNumberUtils.formatNumberToE164(number, country) ?: number
    }

    private fun focus(field: EditText) {
        field.requestFocus()
        field.post {
            getSystemService(InputMethodManager::class.java)?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun onDone(field: EditText, button: Button) {
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEND) {
                if (button.isEnabled) button.performClick()
                true
            } else {
                false
            }
        }
    }

    private fun restartNode() {
        restartingUntil = System.currentTimeMillis() + 3000
        render(null)
        shownGroups = null
        Thread { NodeService.restart(applicationContext) }.start()
    }

    private fun ignoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    // Once, when Telemedia first runs: streaming must not stop in the background.
    private fun askBatteryOnce() {
        if (prefs.getBoolean("askedBattery", false)) return
        prefs.edit().putBoolean("askedBattery", true).apply()
        requestBatteryExemption()
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (ignored: ActivityNotFoundException) {
            }
        }
    }

    // Android 13+: the "Telemedia is running" notification needs permission.
    private fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("askedNotifications", false)) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /** optString without org.json's "null" for JSON null. */
    private fun JSONObject.optStr(name: String): String = if (isNull(name)) "" else optString(name)
}
