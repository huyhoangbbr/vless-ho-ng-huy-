package com.hhvpn.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.text.method.HideReturnsTransformationMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var linkInput: EditText
    private lateinit var statusText: TextView
    private lateinit var serverText: TextView
    private lateinit var connectButton: Button
    private var profile: VlessProfile? = null
    private var pendingConfig: String? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(HHVpnService.EXTRA_STATE) ?: return
            val detail = intent.getStringExtra(HHVpnService.EXTRA_DETAIL).orEmpty()
            showState(state, detail)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(21, 17, 34)
        window.navigationBarColor = Color.rgb(21, 17, 34)
        buildScreen()
        showState(HHVpnService.currentState, "")
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(HHVpnService.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    @Deprecated("Uses the system VPN consent dialog result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != VPN_CONSENT_REQUEST) return
        if (resultCode == RESULT_OK) {
            pendingConfig?.let(::startVpn)
        } else {
            pendingConfig = null
            showState("Disconnected", "VPN permission was not granted.")
        }
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }
        scroll.addView(content)

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(text("hh vpn", 30, PINK, true), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(text("VLESS / Android", 14, BLUE))
        content.addView(titleRow)

        val rainbow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(BORDER, 4)
            clipToOutline = true
        }
        listOf(PINK, PEACH, GOLD, GREEN, BLUE, ACCENT).forEach { color ->
            rainbow.addView(View(this).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(0, dp(5), 1f))
        }
        content.addView(rainbow, margin(top = 12, bottom = 22))

        statusText = text("Disconnected", 17, MUTED, true)
        content.addView(statusText, margin(bottom = 14))

        val serverCard = card()
        serverCard.addView(text("SERVER LINK", 12, PEACH, true))
        linkInput = EditText(this).apply {
            hint = "vless:// link"
            setHintTextColor(MUTED)
            setTextColor(TEXT)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            isSingleLine = true
            isSaveEnabled = false
            setSelectAllOnFocus(false)
            background = rounded(INPUT, 16, BORDER)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    profile = null
                    serverText.text = "No server selected"
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        serverCard.addView(linkInput, margin(top = 10, bottom = 7))

        val showLink = CheckBox(this).apply {
            text = "Show link"
            setTextColor(TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnCheckedChangeListener { _, checked ->
                val selection = linkInput.selectionStart.coerceAtLeast(0)
                linkInput.transformationMethod = if (checked) {
                    HideReturnsTransformationMethod.getInstance()
                } else {
                    PasswordTransformationMethod.getInstance()
                }
                linkInput.setSelection(selection.coerceAtMost(linkInput.text.length))
            }
        }
        serverCard.addView(showLink, margin(bottom = 8))

        val linkActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        linkActions.addView(button("Paste", false) { pasteLink() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        linkActions.addView(button("Parse link", false) { parseLink() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(8)
        })
        serverCard.addView(linkActions)
        serverText = text("No server selected", 14, MUTED)
        serverCard.addView(serverText, margin(top = 12))
        content.addView(serverCard, margin(bottom = 14))

        val vpnCard = card()
        vpnCard.addView(text("VPN CONNECTION", 12, GOLD, true))
        vpnCard.addView(
            text(
                "Android routes device traffic through the selected VLESS server. Connecting asks Android for VPN permission.",
                14,
                MUTED,
            ),
            margin(top = 10),
        )
        content.addView(vpnCard, margin(bottom = 18))

        connectButton = button("Connect", true) { connect() }
        content.addView(connectButton, LinearLayout.LayoutParams(-1, dp(50)))
        content.addView(
            text("Links stay in memory. Disconnect to stop the tunnel.", 12, MUTED),
            margin(top = 15),
        )

        setContentView(scroll)
    }

    private fun parseLink(): VlessProfile? {
        return try {
            val parsed = VlessProfile.parse(linkInput.text.toString())
            profile = parsed
            serverText.text = "${parsed.name}\n${parsed.host}:${parsed.port} · ${parsed.transport.uppercase()} · ${parsed.security.uppercase()}"
            parsed
        } catch (error: IllegalArgumentException) {
            profile = null
            serverText.text = error.message ?: "Invalid VLESS link."
            null
        }
    }

    private fun pasteLink() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        if (item == null) {
            Toast.makeText(this, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
            return
        }
        linkInput.setText(item.coerceToText(this))
        linkInput.setSelection(linkInput.text.length)
    }

    private fun connect() {
        val selected = profile ?: parseLink() ?: return
        pendingConfig = selected.toXrayConfig().toString()
        val consent = VpnService.prepare(this)
        if (consent == null) {
            startVpn(pendingConfig ?: return)
        } else {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, VPN_CONSENT_REQUEST)
        }
    }

    private fun startVpn(config: String) {
        pendingConfig = null
        val intent = Intent(this, HHVpnService::class.java)
            .setAction(HHVpnService.ACTION_START)
            .putExtra(HHVpnService.EXTRA_CONFIG, config)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        showState("Connecting", "")
    }

    private fun showState(state: String, detail: String) {
        statusText.text = if (detail.isBlank()) state else "$state · $detail"
        statusText.setTextColor(
            when (state) {
                "Connected" -> GREEN
                "Connecting", "Disconnecting" -> ACCENT
                else -> MUTED
            },
        )
        connectButton.text = when (state) {
            "Connected" -> "Disconnect"
            "Connecting", "Disconnecting" -> "Disconnecting..."
            else -> "Connect"
        }
        connectButton.isEnabled = state == "Connected" || state == "Disconnected"
        connectButton.setOnClickListener {
            if (HHVpnService.currentState == "Connected") disconnect() else connect()
        }
    }

    private fun disconnect() {
        val intent = Intent(this, HHVpnService::class.java).setAction(HHVpnService.ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        showState("Disconnecting", "")
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(PANEL, 24, BORDER)
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(label: String, accent: Boolean, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(if (accent) BG else TEXT)
            background = rounded(if (accent) ACCENT else BUTTON, 24)
            setOnClickListener { action() }
        }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }

    private fun margin(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val VPN_CONSENT_REQUEST = 41
        private const val BG = 0xFF151122.toInt()
        private const val PANEL = 0xFF221B34.toInt()
        private const val INPUT = 0xFF181329.toInt()
        private const val TEXT = 0xFFF8F3FF.toInt()
        private const val MUTED = 0xFFBDB1D0.toInt()
        private const val BORDER = 0xFF493B60.toInt()
        private const val BUTTON = 0xFF34274D.toInt()
        private const val PINK = 0xFFFF95BC.toInt()
        private const val PEACH = 0xFFFFB49C.toInt()
        private const val GOLD = 0xFFFFE195.toInt()
        private const val GREEN = 0xFF9AEFC5.toInt()
        private const val BLUE = 0xFF99DFFF.toInt()
        private const val ACCENT = 0xFFC9B1FF.toInt()
    }
}
