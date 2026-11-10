package br.com.nexumcell.trava

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Suporte com IA: a assistente responde dúvidas sobre o contrato (o servidor consulta as parcelas pelo token). */
class ChatActivity : Activity() {
    private lateinit var lista: LinearLayout
    private lateinit var rolar: ScrollView
    private lateinit var campo: EditText
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#F4F4F8")) }
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#0A1733")); setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(context).apply { text = "‹"; textSize = 28f; setTextColor(Color.WHITE); setPadding(0, 0, dp(14), 0); setOnClickListener { finish() } })
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Assistente NexumCell"; textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
                addView(TextView(context).apply { text = "Tire suas dúvidas sobre o contrato"; textSize = 12f; setTextColor(Color.parseColor("#C9D3EA")) }) })
        })
        rolar = ScrollView(this); lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        rolar.addView(lista); raiz.addView(rolar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val atalhos = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        listOf("Qual o valor da próxima parcela?", "Como pago com PIX?", "Quanto fica para quitar?", "Meu celular bloqueou, e agora?").forEach { q ->
            atalhos.addView(TextView(this).apply {
                text = q; textSize = 13f; setTextColor(Color.parseColor("#1D4ED8")); setPadding(dp(12), dp(8), dp(12), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#C9D6F2")) }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                setOnClickListener { enviar(q) }
            })
        }
        raiz.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(atalhos) })
        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(10)); setBackgroundColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
        campo = EditText(this).apply { hint = "Digite sua dúvida"; imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(true)
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { enviar(text.toString()); true } else false } }
        barra.addView(campo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(Button(this).apply { text = "Enviar"; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1D4ED8")); setOnClickListener { enviar(campo.text.toString()) } })
        raiz.addView(barra)
        setContentView(raiz)
        val nome = Store(this).cache()?.optString("clienteNome").orEmpty().split(" ").firstOrNull().orEmpty()
        bolha("Olá${if (nome.isNotEmpty()) ", $nome" else ""}! Sou a assistente da NexumCell. Posso te ajudar com parcelas, pagamento por PIX, quitação, bloqueio e prazos. O que você precisa?", false)
    }

    private fun bolha(t: String, minha: Boolean): TextView {
        val tv = TextView(this).apply {
            text = t; textSize = 15f; setLineSpacing(4f, 1f); setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(Color.parseColor(if (minha) "#FFFFFF" else "#1B1A2E"))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor(if (minha) "#1D4ED8" else "#FFFFFF")) }
        }
        lista.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (minha) Gravity.END else Gravity.START; topMargin = dp(8); if (minha) marginStart = dp(48) else marginEnd = dp(48) })
        rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
        return tv
    }

    private fun enviar(texto: String) {
        val q = texto.trim(); if (q.isEmpty()) return
        campo.setText(""); bolha(q, true); val espera = bolha("Digitando…", false)
        val st = Store(this); val d = st.cache()
        val serv = d?.optString("servidor").orEmpty().ifEmpty { "https://nexumcell-servidor.wevellenerrera21.workers.dev" }.trimEnd('/')
        Thread {
            val resp = try {
                val c = URL("$serv/api/assistente").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("token", st.token).put("pergunta", q.take(500)).toString().toByteArray()) }
                JSONObject(c.inputStream.bufferedReader().readText()).optString("resposta").takeIf { it.isNotBlank() && it != "null" }
            } catch (e: Exception) { null }
            runOnUiThread {
                espera.text = resp ?: "Não consegui responder agora. Verifique a internet ou fale com a loja pelo WhatsApp na aba Suporte."
            }
        }.start()
    }
}
