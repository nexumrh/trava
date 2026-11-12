package br.com.nexumcell.trava

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * Suporte NexumCell DENTRO do app (sem abrir WhatsApp). O cliente conversa com a equipe da NexumCell;
 * as mensagens ficam em suporteApp/{token} e aparecem no portal de suporte. Funciona mesmo com o
 * aparelho bloqueado por falta de pagamento — por isso é aberto também pela tela de bloqueio.
 */
class SuporteActivity : Activity() {
    private lateinit var lista: LinearLayout
    private lateinit var rolar: ScrollView
    private lateinit var campo: EditText
    private val fundo = Handler(Looper.getMainLooper())
    private var atualizar: Runnable? = null
    private var bloqueado = false
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bloqueado = intent.getBooleanExtra("bloqueado", false)
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F4F4F8")) }
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#0A1733")); setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(context).apply { text = "‹"; textSize = 28f; setTextColor(Color.WHITE); setPadding(0, 0, dp(14), 0); setOnClickListener { finish() } })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = I18n.t("Suporte NexumCell"); textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(context).apply { text = I18n.t("Atendimento oficial — respondemos aqui no app"); textSize = 12f; setTextColor(Color.parseColor("#C9D3EA")) })
            })
        })
        rolar = ScrollView(this); lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        rolar.addView(lista); raiz.addView(rolar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Atalho para a assistente (IA)
        raiz.addView(TextView(this).apply {
            text = I18n.t("🤖 Tirar dúvidas rápidas com a assistente (IA)"); textSize = 14f; setTextColor(Color.parseColor("#1D4ED8"))
            setPadding(dp(16), dp(10), dp(16), dp(10)); setOnClickListener { startActivity(Intent(this@SuporteActivity, ChatActivity::class.java)) }
        })

        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(10)); setBackgroundColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
        campo = EditText(this).apply {
            hint = I18n.t("Escreva para o Suporte NexumCell"); imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(false); maxLines = 4
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { enviar(text.toString()); true } else false }
        }
        barra.addView(campo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(Button(this).apply { text = I18n.t("Enviar"); isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1D4ED8")); setOnClickListener { enviar(campo.text.toString()) } })
        raiz.addView(barra)
        setContentView(raiz)

        val nome = Store(this).cache()?.optString("clienteNome").orEmpty().split(" ").firstOrNull().orEmpty()
        info(I18n.t("Olá") + (if (nome.isNotEmpty()) ", $nome" else "") + "! " + I18n.t("Este é o canal direto com a NexumCell. Escreva sua dúvida ou o que está acontecendo que a equipe responde aqui mesmo."))
        if (bloqueado) info(I18n.t("Mesmo com o aparelho bloqueado você pode falar com a gente por aqui."))
        carregar()
    }

    override fun onResume() { super.onResume(); atualizar = Runnable { carregar(false); atualizar?.let { fundo.postDelayed(it, 15000) } }; fundo.postDelayed(atualizar!!, 15000) }
    override fun onPause() { super.onPause(); atualizar?.let { fundo.removeCallbacks(it) } }

    private fun info(t: String) {
        lista.addView(TextView(this).apply {
            text = t; textSize = 13f; setTextColor(Color.parseColor("#5B5A72")); gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun bolha(t: String, minha: Boolean) {
        val tv = TextView(this).apply {
            text = t; textSize = 15f; setLineSpacing(4f, 1f); setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(Color.parseColor(if (minha) "#FFFFFF" else "#1B1A2E"))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor(if (minha) "#1D4ED8" else "#FFFFFF")); if (!minha) setStroke(dp(1), Color.parseColor("#E1E1EC")) }
        }
        lista.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (minha) Gravity.END else Gravity.START; topMargin = dp(8); if (minha) marginStart = dp(48) else marginEnd = dp(48)
        })
        rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private var ultimoCount = -1
    private fun carregar(rolarFim: Boolean = true) {
        Thread {
            val msgs = try { Api(this).buscarSuporteMsgs() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (msgs.size == ultimoCount) return@runOnUiThread
                ultimoCount = msgs.size
                // redesenha só as bolhas (mantém os textos de info já no topo é simples: limpamos e remarcamos)
                lista.removeAllViews()
                val nome = Store(this).cache()?.optString("clienteNome").orEmpty().split(" ").firstOrNull().orEmpty()
                info(I18n.t("Olá") + (if (nome.isNotEmpty()) ", $nome" else "") + "! " + I18n.t("Este é o canal direto com a NexumCell. Escreva sua dúvida ou o que está acontecendo que a equipe responde aqui mesmo."))
                if (msgs.isEmpty() && bloqueado) info(I18n.t("Mesmo com o aparelho bloqueado você pode falar com a gente por aqui."))
                for (m in msgs) bolha((m.opt("texto") as? String).orEmpty(), (m.opt("de") as? String) == "cliente")
                if (rolarFim) rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }

    private fun enviar(texto: String) {
        val q = texto.trim(); if (q.isEmpty()) return
        campo.setText(""); bolha(q, true)
        val d = Store(this).cache()
        Thread {
            val ok = try { Api(this).enviarSuporteMsg(q, bloqueado, d); true } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) { ultimoCount = -1; carregar() }
                else info(I18n.t("Não consegui enviar agora. Verifique a internet e tente de novo — sua mensagem não foi perdida, é só reenviar."))
            }
        }.start()
    }
}
