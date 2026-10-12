package com.shipperdog.companion.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.shipperdog.companion.core.SessionExpiredException

/**
 * The login screen (issue #73, S15-02): server base, email,
 * password — the portal session machinery's own three facts, nothing
 * companion-specific. The base field stays editable because the
 * spike dogfoods against both the emulator's local compose stack
 * (10.0.2.2) and any deployed API; the values prefill from the last
 * successful login. A persisted session skips this screen entirely.
 */
class LoginActivity : Activity() {

    private lateinit var baseUrl: EditText
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var signIn: Button
    private lateinit var progress: ProgressBar
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Runtime.init(this)
        setContentView(buildUi())
        if (Runtime.session.hasSession()) {
            openCatalog()
        }
    }

    private fun buildUi(): View {
        val padding = dp(20)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(padding, padding, padding, padding)

        val title = TextView(this)
        title.text = "ShipperDog Companion"
        title.textSize = 22f
        title.gravity = Gravity.CENTER
        title.setPadding(0, padding, 0, padding)
        column.addView(title)

        baseUrl = field("Server base URL", InputType.TYPE_TEXT_VARIATION_URI)
        Runtime.configuredBaseUrl().let { baseUrl.setText(it) }
        column.addView(baseUrl)

        email = field("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        Runtime.lastEmail()?.let { email.setText(it) }
        column.addView(email)

        password = field("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        column.addView(password)

        progress = ProgressBar(this)
        progress.visibility = View.GONE
        progress.setPadding(0, padding, 0, padding)
        column.addView(progress)

        message = TextView(this)
        message.setPadding(0, dp(8), 0, 0)
        column.addView(message)

        signIn = Button(this)
        signIn.text = "Sign in"
        signIn.setOnClickListener { submit() }
        column.addView(signIn)
        return column
    }

    private fun field(hint: String, inputType: Int): EditText {
        val input = EditText(this)
        input.hint = hint
        input.inputType = inputType
        return input
    }

    private fun submit() {
        val base = baseUrl.text.toString().trim()
        val tester = email.text.toString().trim()
        val secret = password.text.toString()
        if (base.isEmpty() || tester.isEmpty() || secret.isEmpty()) {
            message.text = "All three fields are required."
            return
        }
        signIn.isEnabled = false
        progress.visibility = View.VISIBLE
        message.text = ""
        Runtime.background.execute {
            try {
                Runtime.rebuild(base)
                Runtime.session.login(tester, secret)
                Runtime.main.post { openCatalog() }
            } catch (refused: Exception) {
                Runtime.main.post {
                    signIn.isEnabled = true
                    progress.visibility = View.GONE
                    message.text = when (refused) {
                        is SessionExpiredException -> "Session refused — sign in again."
                        else -> "Sign-in refused: " + (refused.message ?: "unknown error")
                    }
                }
            }
        }
    }

    private fun openCatalog() {
        startActivity(Intent(this, CatalogActivity::class.java))
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
