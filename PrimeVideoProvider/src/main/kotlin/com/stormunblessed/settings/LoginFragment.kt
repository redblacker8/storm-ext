package com.stormunblessed.settings

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.stormunblessed.PrimeVideoApi
import com.stormunblessed.PrimeVideoConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginFragment(private val api: PrimeVideoApi) : BottomSheetDialogFragment() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var regionGroup: RadioGroup? = null
    private var cookieInput: EditText? = null
    private var cookieButton: Button? = null
    private var tokenInput: EditText? = null
    private var tokenButton: Button? = null
    private var statusText: TextView? = null
    private var logoutButton: Button? = null
    private var atvTokenLabel: TextView? = null

    /**
     * Etiqueta visible -> índice real en [PrimeVideoConstants.TERRITORIES].
     * Cubre todos los índices de la tabla del plugin original sin repetir.
     * México usa el storefront global de primevideo.com (ROW Norteamérica, índice 8).
     */
    private val regionOptions: List<Pair<String, Int>> = listOf(
        "Auto-detectar" to 0,
        "México (primevideo.com)" to 8,
        "EE.UU. (amazon.com)" to 3,
        "Alemania (amazon.de)" to 1,
        "Reino Unido (amazon.co.uk)" to 2,
        "Japón (amazon.co.jp)" to 4,
        "ROE Europa (primevideo.com)" to 5,
        "ROW Europa (primevideo.com)" to 6,
        "ROW Lejano Este (primevideo.com)" to 7,
    )

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
            text = "Prime Video Login"
            textSize = 20f
            setPadding(0, 0, 0, dp(8))
        })

        root.addView(TextView(ctx).apply {
            text = "Este proveedor requiere una cuenta Prime Video activa. " +
                "Las cookies solo se usan para obtener tokens de acceso (no se guardan)."
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })

        // Región - selector vertical desplazable (sin botones apretados)
        root.addView(TextView(ctx).apply {
            text = "Región"
            textSize = 14f
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        root.addView(TextView(ctx).apply {
            text = "Elige el país/marketplace de tu cuenta. México usa primevideo.com."
            textSize = 11f
            setPadding(0, 0, 0, dp(6))
        }, matchWrap())

        val group = RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        regionGroup = group
        var foundCurrent = false
        regionOptions.forEach { (label, territoryIndex) ->
            val rb = RadioButton(ctx).apply {
                text = label
                textSize = 14f
                id = View.generateViewId()
                tag = territoryIndex
                setPadding(0, dp(2), 0, dp(2))
            }
            group.addView(rb)
            if (territoryIndex == api.region) {
                rb.isChecked = true
                foundCurrent = true
            }
        }
        if (!foundCurrent) {
            (group.getChildAt(0) as? RadioButton)?.isChecked = true
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val rb = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val territoryIndex = rb.tag as? Int ?: return@setOnCheckedChangeListener
            api.setTerritory(territoryIndex)
            setStatus("Región guardada: ${rb.text}. Reimporta cookies/token si cambiaste de país.")
        }
        root.addView(group, matchWrap())

        // Separator
        root.addView(TextView(ctx).apply {
            text = "─── Inicio de sesión ───"
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        })

        // Pesta de cookies del navegador
        root.addView(TextView(ctx).apply {
            text = "Opción 1: Pega las cookies de primevideo.com"
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        root.addView(TextView(ctx).apply {
            text = "En el navegador copia las cookies como name=value; name2=value2 o JSON."
            textSize = 11f
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        cookieInput = EditText(ctx).apply {
            hint = "Pega las cookies (name=value; … o JSON)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(cookieInput, matchWrap())
        cookieButton = Button(ctx).apply {
            text = "Importar cookies"
            setOnClickListener { onImportCookiesClick() }
        }
        root.addView(cookieButton, matchWrap())

        // Separator
        root.addView(TextView(ctx).apply {
            text = "─── o ───"
            textSize = 13f
            setPadding(0, dp(12), 0, dp(4))
        })

        // Pesta de token
        root.addView(TextView(ctx).apply {
            text = "Opción 2: Pega el refresh token de Amazon"
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }, matchWrap())
        tokenInput = EditText(ctx).apply {
            hint = "Pega el refresh token o JSON"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(tokenInput, matchWrap())
        tokenButton = Button(ctx).apply {
            text = "Importar token"
            setOnClickListener { onImportTokenClick() }
        }
        root.addView(tokenButton, matchWrap())

        // ATV token status
        atvTokenLabel = TextView(ctx).apply {
            textSize = 11f
            setPadding(0, dp(4), 0, dp(4))
        }
        root.addView(atvTokenLabel, matchWrap())
        updateAtvTokenStatus()

        statusText = TextView(ctx).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText, matchWrap())

        logoutButton = Button(ctx).apply {
            text = "Cerrar sesión"
            isVisible = api.isLoggedIn
            setOnClickListener { onLogoutClick() }
        }
        root.addView(logoutButton, matchWrap())

        if (api.isLoggedIn) {
            statusText?.text = "Sesión activa (cookies/token presente)"
        }

        // Envolver todo en ScrollView para que el bottom sheet nunca corte contenido
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        return scroll
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    fun updateAtvTokenStatus() {
        val token = api.atvToken
        if (!token.isNullOrBlank()) {
            atvTokenLabel?.text = "✓ ATV token presente (API mobile activa)"
        } else {
            atvTokenLabel?.text = "⚠ Sin token ATV. Importa cookies que contengan 'at-main-av'."
        }
    }

    private fun setStatus(msg: String) {
        statusText?.text = msg
    }

    private fun onImportCookiesClick() {
        val raw = cookieInput?.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            setStatus("Pega las cookies del navegador")
            return
        }
        setStatus("Validando cookies...")
        cookieButton?.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.importCookies(raw) }
                showToast("Prime Video: cookies importadas")
                updateAtvTokenStatus()
                dismiss()
            } catch (e: Exception) {
                setStatus(e.message ?: "Cookies inválidas")
                cookieButton?.isEnabled = true
            }
        }
    }

    private fun onImportTokenClick() {
        val raw = tokenInput?.text?.toString()?.trim().orEmpty()
        if (raw.isBlank()) {
            setStatus("Pega el token de sesión")
            return
        }
        setStatus("Validando token...")
        tokenButton?.isEnabled = false
        scope.launch {
            try {
                withContext(Dispatchers.IO) { api.importTokens(raw) }
                showToast("Prime Video: token importado")
                dismiss()
            } catch (e: Exception) {
                setStatus(e.message ?: "Token inválido o expirado")
                tokenButton?.isEnabled = true
            }
        }
    }

    private fun onLogoutClick() {
        api.logout()
        showToast("Prime Video: sesión cerrada")
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}