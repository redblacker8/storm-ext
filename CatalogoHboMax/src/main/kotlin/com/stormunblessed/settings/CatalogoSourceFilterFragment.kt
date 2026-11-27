package com.stormunblessed.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ProviderType

/**
 * Unwraps a (possibly themed or wrapped) context until the hosting activity.
 * The settings button can hand us an activity, a themed wrapper or a fragment
 * context, so a hard cast like `context as AppCompatActivity` may crash.
 */
fun Context.findAppCompatActivity(): AppCompatActivity? {
    var current: Context = this
    while (true) {
        if (current is AppCompatActivity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
}

/** True on Android TV / leanback devices (D-pad only, no useful keyboard). */
private fun Context.isTelevision(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION

/**
 * Settings panel for the catalog providers (CatalogoInfantil, CatalogoNetflix,
 * CatalogoHboMax, CatalogoGeneral).
 *
 * Every installed provider that can actually supply links (same language, not
 * another catalog) is listed as a checkbox: checked = allowed. Storage is a
 * blacklist, so the untouched default is "all allowed".
 *
 * Two scopes are offered:
 *
 *  - "Solo este catálogo": blocks apply only to the catalog that opened the panel.
 *  - "Todos los catálogos": blocks apply to all four catalogs and win over the
 *    local list, which is shown but disabled in the first scope.
 *
 * Changes are saved as soon as a checkbox is toggled (no save button), the same
 * way the Prime Video login panel behaves.
 *
 * On TV the panel is D-pad only: the provider search field is hidden (it would
 * pop the leanback keyboard), rows are larger, and the focus starts on the
 * scope selector.
 */
class CatalogoSourceFilterFragment(
    private val filter: CatalogoSourceFilter,
    private val providerName: String,
    private val providerLang: String,
) : BottomSheetDialogFragment() {

    /** false = only this catalog; true = all catalogs. */
    private var globalScope = false
    private var query: String = ""
    private var providers: List<MainAPI> = emptyList()

    private lateinit var rowsContainer: LinearLayout
    private lateinit var summary: TextView
    private lateinit var warning: TextView

    /** Row metrics, computed from the layout (bigger on TV). */
    private var rowTextSize: Float = 14f
    private var rowPad: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val isTv = ctx.isTelevision()

        // Same candidates the provider itself searches: same language and not
        // another meta provider (a catalog must not delegate to a catalog).
        providers = APIHolder.apis.filter {
            it.lang.equals(providerLang, ignoreCase = true) &&
                it.providerType != ProviderType.MetaProvider
        }.sortedBy { it.name.lowercase() }

        // TV is watched from a distance: bigger type and taller rows.
        val titleTextSize = if (isTv) 22f else 20f
        val labelTextSize = if (isTv) 16f else 14f
        val bodyTextSize = if (isTv) 14f else 13f
        val hintTextSize = if (isTv) 13f else 11f
        val rowSize = if (isTv) 17f else 14f
        val rowPadding = if (isTv) dp(10) else dp(2)
        val actionTextSize = if (isTv) 14f else 12f
        val summaryTextSize = if (isTv) 14f else 12f

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(TextView(ctx).apply {
            text = "Fuentes permitidas"
            textSize = titleTextSize
            setPadding(0, 0, 0, dp(6))
        }, matchWrap())

        root.addView(TextView(ctx).apply {
            text = "Elige qué proveedores puede usar $providerName para encontrar enlaces. " +
                "Todos están permitidos por defecto: desmarca los que quieras bloquear."
            textSize = bodyTextSize
            setPadding(0, 0, 0, dp(12))
        }, matchWrap())

        root.addView(TextView(ctx).apply {
            text = "Aplicar a"
            textSize = labelTextSize
            setPadding(0, 0, 0, dp(2))
        }, matchWrap())

        val scopeGroup = RadioGroup(ctx).apply { orientation = RadioGroup.VERTICAL }
        val ownScope = RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "Solo este catálogo"
            textSize = labelTextSize
            isChecked = true
        }
        val allScope = RadioButton(ctx).apply {
            id = View.generateViewId()
            text = "Todos los catálogos"
            textSize = labelTextSize
        }
        scopeGroup.addView(ownScope)
        scopeGroup.addView(allScope)
        scopeGroup.setOnCheckedChangeListener { _, checkedId ->
            globalScope = checkedId == allScope.id
            render()
        }
        root.addView(scopeGroup, matchWrap())

        root.addView(TextView(ctx).apply {
            text = "«Todos los catálogos» se guarda como bloqueo global: no se puede " +
                "volver a permitir desde un catálogo individual."
            textSize = hintTextSize
            setPadding(0, 0, 0, dp(10))
        }, matchWrap())

        // A phone-only convenience: on TV the leanback keyboard would cover the
        // sheet, and the list is short enough to scroll with the D-pad.
        if (!isTv) {
            val search = EditText(ctx).apply {
                hint = "Buscar proveedor…"
                textSize = 14f
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine()
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim().orEmpty()
                    render()
                }
            })
            root.addView(search, matchWrap())
        }

        val bulkRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val allowAll = Button(ctx).apply {
            text = "Permitir todos"
            textSize = actionTextSize
            setOnClickListener {
                filter.setBlocked(emptySet(), globalScope)
                render()
            }
        }
        val blockAll = Button(ctx).apply {
            text = "Bloquear todos"
            textSize = actionTextSize
            setOnClickListener {
                filter.setBlocked(providers.map { it.name }.toSet(), globalScope)
                render()
            }
        }
        bulkRow.addView(allowAll, weightOne())
        bulkRow.addView(blockAll, weightOne())
        root.addView(bulkRow, matchWrap())

        rowsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(rowsContainer, matchWrap())

        summary = TextView(ctx).apply {
            textSize = summaryTextSize
            setPadding(0, dp(8), 0, dp(4))
        }
        root.addView(summary, matchWrap())

        warning = TextView(ctx).apply {
            text = "⚠ No hay ningún proveedor permitido: este catálogo no encontrará enlaces."
            textSize = summaryTextSize
            setPadding(0, 0, 0, dp(8))
            visibility = View.GONE
        }
        root.addView(warning, matchWrap())

        root.addView(TextView(ctx).apply {
            text = if (providers.isEmpty()) {
                "No hay otros proveedores instalados con idioma español latino."
            } else {
                ""
            }
            textSize = bodyTextSize
            setPadding(0, dp(4), 0, dp(8))
            visibility = if (providers.isEmpty()) View.VISIBLE else View.GONE
        }, matchWrap())

        // Row metrics are read by render().
        rowTextSize = rowSize
        rowPad = rowPadding

        render()

        return ScrollView(ctx).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The search field must not pop the keyboard when the sheet opens.
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    /** Rebuilds the checkbox list for the active scope and text filter. */
    private fun render() {
        val ctx = context ?: return
        rowsContainer.removeAllViews()

        val globallyBlocked = filter.globalBlocked()
        val scopeBlocked = filter.blockedFor(globalScope)
        val visible = providers.filter {
            query.isBlank() || it.name.contains(query, ignoreCase = true)
        }

        if (visible.isEmpty() && providers.isNotEmpty()) {
            rowsContainer.addView(TextView(ctx).apply {
                text = "Ningún proveedor coincide con «$query»."
                textSize = 13f
                setPadding(0, dp(8), 0, dp(8))
            }, matchWrap())
        }

        visible.forEach { api ->
            // In the per-catalog scope, a global block is inherited and cannot
            // be undone here, so the row is shown unchecked, disabled and
            // labelled.
            val blockedGlobally = !globalScope && api.name in globallyBlocked
            val checkBox = CheckBox(ctx).apply {
                text = if (blockedGlobally) "${api.name}  (bloqueado en todos)" else api.name
                textSize = rowTextSize
                isChecked = !blockedGlobally && api.name !in scopeBlocked
                isEnabled = !blockedGlobally
                setPadding(0, rowPad, 0, rowPad)
            }
            checkBox.setOnCheckedChangeListener { _, checked ->
                val current = filter.blockedFor(globalScope)
                val next = if (checked) current - api.name else current + api.name
                filter.setBlocked(next, globalScope)
                updateSummary()
            }
            rowsContainer.addView(checkBox, matchWrap())
        }

        updateSummary()
    }

    private fun updateSummary() {
        val total = providers.size
        val allowed = providers.count { filter.isAllowed(it.name) }
        val globalBlocks = providers.count { it.name in filter.globalBlocked() }
        val localOnly = providers.count {
            it.name in filter.ownBlocked() && !filter.isGloballyBlocked(it.name)
        }

        summary.text = buildString {
            append("$allowed de $total permitidos")
            if (localOnly > 0) append(" · $localOnly bloqueados aquí")
            if (globalBlocks > 0) append(" · $globalBlocks bloqueados en todos")
        }
        warning.visibility = if (total > 0 && allowed == 0) View.VISIBLE else View.GONE
    }

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun weightOne() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f,
    )
}
