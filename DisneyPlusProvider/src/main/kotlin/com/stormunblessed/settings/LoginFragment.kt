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
import com.stormunblessed.DisneyApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginFragment(private val api: DisneyApi) : BottomSheetDialogFragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var emailInput: EditText? = null
    private var passwordRow: LinearLayout? = null
    private var passwordInput: EditText? = null
    private var otpRow: LinearLayout? = null
    private var otpInput: EditText? = null
    private var pinRow: LinearLayout? = null
    private var pinInput: EditText? = null
    private var tokenInput: EditText? = null
    private var tokenButton: Button? = null
    private var statusText: TextView? = null
    private var loginButton: Button? = null
    private var pinButton: Button? = null
    private var logoutButton: Button? = null

    private var otpRequired = false
    private var pendingPassword = false

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
            text = "Disney+ Login"
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(ctx).apply {
            text = "Este proveedor requiere una cuenta Disney+ con suscripción activa. " +
                "Tus credenciales solo se usan para obtener tokens de acceso (no se guardan)."
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })

        emailInput = EditText(ctx).apply {
            hint = "Email de Disney+"
            setText(api.email.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(emailInput, matchWrap())

        // Contraseña: es la credencial principal, visible desde el inicio.
        passwordRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = !api.isLoggedIn
        }
        passwordInput = EditText(ctx).apply {
            hint = "Contraseña de Disney+"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        passwordRow!!.addView(passwordInput, matchWrap())
        root.addView(passwordRow, matchWrap())

        otpRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        otpInput = EditText(ctx).apply {
            hint = "Código de verificación (OTP)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        otpRow!!.addView(otpInput, matchWrap())
        root.addView(otpRow, matchWrap())

        // Alternativa: reutilizar la sesión del navegador (los tokens viven en localStorage, no en cookies).
        val sessionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = !api.isLoggedIn
            setPadding(0, dp(12), 0, 0)
        }
        sessionRow.addView(TextView(ctx).apply {
            text = "¿Ya iniciaste sesión en el navegador? Puedes reutilizar esa sesión."
            textSize = 13f
        }, matchWrap())
        sessionRow.addView(TextView(ctx).apply {
            text = "En disneyplus.com abre la consola y copia:\n" +
                "JSON.parse(localStorage.getItem('__bam_sdk_access--disney-svod-3d9324fc_prod')).context"
            textSize = 11f
            setPadding(0, dp(4), 0, dp(4))
        }, matchWrap())
        tokenInput = EditText(ctx).apply {
            hint = "Pega aquí el token de sesión (JSON o refresh token)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        sessionRow.addView(tokenInput, matchWrap())
        tokenButton = Button(ctx).apply {
            text = "Importar sesión del navegador"
            setOnClickListener { onImportSessionClick() }
        }
        sessionRow.addView(tokenButton, matchWrap())
        root.addView(sessionRow, matchWrap())

        // El PIN solo sirve para desbloquear un perfil ya autenticado: no se pide para iniciar sesión.
        pinRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isVisible = api.isLoggedIn
            setPadding(0, dp(8), 0, 0)
        }
        pinRow!!.addView(TextView(ctx).apply {
            text = "PIN de perfil (opcional, solo si tu perfil está protegido)"
            textSize = 13f
        }, matchWrap())
        pinInput = EditText(ctx).apply {
            hint = "PIN del perfil (4 dígitos)"
            setText(api.profilePin.orEmpty())
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        pinRow!!.addView(pinInput, matchWrap())
        root.addView(pinRow, matchWrap())

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

        pinButton = Button(ctx).apply {
            text = "Guardar PIN y seleccionar perfil"
            isVisible = api.isLoggedIn
            setOnClickListener { onSavePinClick() }
        }
        root.addView(pinButton, matchWrap())

        logoutButton = Button(ctx).apply {
            text = "Cerrar sesión"
            isVisible = api.isLoggedIn
            setOnClickListener { onLogoutClick() }
        }
        root.addView(logoutButton, matchWrap())

        if (api.isLoggedIn) {
            emailInput?.setText(api.email.orEmpty())
            passwordRow?.isVisible = false
            statusText?.text = "Sesión activa${api.email?.let { " como $it" }.orEmpty()}"
        }

        return root
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun setStatus(msg: String) {
        statusText?.text = msg
    }

    private fun onLoginClick() {
        if (otpRequired) {
            onVerifyClick()
        } else if (pendingPassword) {
            onPasswordLogin()
        } else {
            onStartLogin()
        }
    }

    private fun onStartLogin() {
        val email = emailInput?.text?.toString()?.trim().orEmpty()
        if (email.isBlank()) {
            setStatus("Ingresa tu correo de Disney+")
            return
        }
        otpRequired = false
        pendingPassword = false
        setStatus("Contactando Disney+...")
        loginButton?.isEnabled = false
        scope.launch {
            var submitPassword = false
            try {
                val needsOtp = withContext(Dispatchers.IO) { api.startLogin(email) }
                if (needsOtp) {
                    otpRequired = true
                    passwordRow?.isVisible = false
                    otpRow?.isVisible = true
                    otpInput?.requestFocus()
                    loginButton?.text = "Verificar código"
                    setStatus("Revisa tu correo y escribe el código recibido.")
                } else {
                    pendingPassword = true
                    passwordRow?.isVisible = true
                    otpRow?.isVisible = false
                    loginButton?.text = "Iniciar sesión"
                    if (passwordInput?.text?.toString()?.isNotBlank() == true) {
                        submitPassword = true
                    } else {
                        passwordInput?.requestFocus()
                        setStatus("Escribe tu contraseña.")
                    }
                }
            } catch (e: Exception) {
                setStatus(e.message ?: "Error de Disney+")
            } finally {
                loginButton?.isEnabled = true
            }
            if (submitPassword) onPasswordLogin()
        }
    }

    private fun onPasswordLogin() {
        val password = passwordInput?.text?.toString().orEmpty()
        if (password.isBlank()) {
            setStatus("Escribe tu contraseña")
            return
        }
        setStatus("Iniciando sesión...")
        loginButton?.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.completePasswordLogin(password) }
                onLoggedIn()
            } catch (e: Exception) {
                setStatus(e.message ?: "Error de Disney+")
                loginButton?.isEnabled = true
            }
        }
    }

    private fun onVerifyClick() {
        val otp = otpInput?.text?.toString()?.trim().orEmpty()
        if (otp.isBlank()) {
            setStatus("Escribe el código OTP")
            return
        }
        setStatus("Verificando código...")
        loginButton?.isEnabled = false
        scope.launch {
            try {
                val error = withContext(Dispatchers.IO) { api.completeOtpLogin(otp) }
                if (error != null) {
                    setStatus(error)
                    loginButton?.isEnabled = true
                } else {
                    onLoggedIn()
                }
            } catch (e: Exception) {
                setStatus(e.message ?: "Error de Disney+")
                loginButton?.isEnabled = true
            }
        }
    }

    private fun onLoggedIn() {
        scope.launch {
            api.profilePin = pinInput?.text?.toString()?.trim().orEmpty()
            withContext(Dispatchers.IO) { runCatching { api.selectActiveProfile() } }
            showToast("Disney+ sesión iniciada")
            dismiss()
        }
    }

    private fun onImportSessionClick() {
        val raw = tokenInput?.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            setStatus("Pega el token de sesión del navegador")
            return
        }
        setStatus("Validando sesión...")
        tokenButton?.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.importSession(raw) }
                showToast("Disney+ sesión importada")
                dismiss()
            } catch (e: Exception) {
                setStatus(e.message ?: "Token de sesión inválido")
                tokenButton?.isEnabled = true
            }
        }
    }

    private fun onSavePinClick() {
        val pin = pinInput?.text?.toString()?.trim().orEmpty()
        api.profilePin = pin
        setStatus("PIN guardado. Seleccionando perfil...")
        pinButton?.isEnabled = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { api.selectActiveProfile() }.getOrDefault(false)
            }
            if (ok) {
                setStatus("Perfil seleccionado")
                showToast("Disney+ perfil listo")
            } else {
                setStatus("No se pudo seleccionar el perfil. Verifica el PIN.")
            }
            pinButton?.isEnabled = true
        }
    }

    private fun onLogoutClick() {
        api.logout()
        showToast("Disney+ sesión cerrada")
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
