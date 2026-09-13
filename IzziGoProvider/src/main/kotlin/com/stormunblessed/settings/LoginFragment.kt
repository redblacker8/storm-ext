package com.stormunblessed.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.stormunblessed.IzziGoApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginFragment(private val api: IzziGoApi) : BottomSheetDialogFragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var userInput: EditText? = null
    private var passInput: EditText? = null
    private var statusText: TextView? = null
    private var loginButton: Button? = null
    private var logoutButton: Button? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(ctx).apply {
            text = "izzi go Login"
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(ctx).apply {
            text = "Este proveedor requiere una cuenta izzi go con suscripción activa. " +
                "Tus credenciales se guardan solo en tu dispositivo para renovar la sesión."
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })

        userInput = EditText(ctx).apply {
            hint = "Usuario o correo izzi go"
            setText(api.username.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(userInput, matchWrap())

        passInput = EditText(ctx).apply {
            hint = "Contraseña"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(passInput, matchWrap())

        statusText = TextView(ctx).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText, matchWrap())

        loginButton = Button(ctx).apply {
            text = if (api.isLoggedIn) "Re-iniciar sesión" else "Iniciar sesión"
            setOnClickListener { onLoginClick() }
        }
        root.addView(loginButton, matchWrap())

        logoutButton = Button(ctx).apply {
            text = "Cerrar sesión"
            isVisible = api.isLoggedIn
            setOnClickListener { onLogoutClick() }
        }
        root.addView(logoutButton, matchWrap())

        if (api.isLoggedIn) {
            userInput?.setText(api.username.orEmpty())
            statusText?.text = "Sesión activa${api.username?.let { " como $it" }.orEmpty()}"
        }

        return root
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun onLoginClick() {
        val user = userInput?.text?.toString()?.trim().orEmpty()
        val pass = passInput?.text?.toString().orEmpty()
        if (user.isBlank()) {
            statusText?.text = "Ingresa tu usuario"
            return
        }
        if (pass.isBlank()) {
            statusText?.text = "Ingresa tu contraseña"
            return
        }
        statusText?.text = "Iniciando sesión..."
        loginButton?.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.login(user, pass) }
                showToast("izzi go: sesión iniciada")
                dismiss()
            } catch (e: Exception) {
                statusText?.text = e.message ?: "Error de izzi go"
                loginButton?.isEnabled = true
            }
        }
    }

    private fun onLogoutClick() {
        api.logout()
        showToast("izzi go: sesión cerrada")
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
