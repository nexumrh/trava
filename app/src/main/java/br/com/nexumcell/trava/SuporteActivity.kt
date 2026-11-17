package br.com.nexumcell.trava

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
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

/**
 * Suporte NexumCell dentro do app. Quem atende primeiro é SEMPRE a assistente virtual
 * (respostas com os dados reais do contrato). Se ela não resolver, ou o cliente pedir,
 * a conversa passa para um atendente humano: as mensagens vão para suporteApp/{token}
 * e aparecem no portal de suporte. Funciona também com o aparelho bloqueado.
 */
class SuporteActivity : Activity() {
    private lateinit var lista: LinearLayout
    private lateinit var rolar: ScrollView
    private lateinit var campo: EditText
    private lateinit var subtitulo: TextView
    private lateinit var atalhos: View
    private lateinit var linkModo: TextView
    private val fundo = Handler(Looper.getMainLooper())
    private var atualizar: Runnable? = null
    private var bloqueado = false
    private var naoEntendeu = 0
    /** Respostas que não resolveram e pedidos de atendente: o humano só entra em último caso. */
    private var naoResolveu = 0
    private var pediuAtendente = 0
    /** Histórico da conversa com a assistente, enviado ao atendente na transferência. */
    private val historico = mutableListOf<String>()
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private val st by lazy { Store(this) }

    /** Atendimento humano fica aberto por 24 h depois da transferência. */
    private val humano: Boolean get() = st.suporteHumanoEm > 0 && System.currentTimeMillis() - st.suporteHumanoEm < 24 * 3_600_000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bloqueado = intent.getBooleanExtra("bloqueado", false)
        val raiz = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor(Ui.FUNDO)) }
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.parseColor("#0A1733")); setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(context).apply { text = "‹"; textSize = 28f; setTextColor(Color.WHITE); setPadding(0, 0, dp(12), 0); setOnClickListener { finish() } })
            addView(TextView(context).apply {
                text = "N"; textSize = 17f; typeface = Ui.NEGRITO; gravity = Gravity.CENTER; setTextColor(Color.parseColor(Ui.AZUL))
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(12) }
            })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = I18n.t("Suporte NexumCell"); textSize = 18f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD) })
                subtitulo = TextView(context).apply { textSize = 12f; setTextColor(Color.parseColor("#C9D3EA")) }
                addView(subtitulo)
            })
        })
        rolar = ScrollView(this); lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        rolar.addView(lista); raiz.addView(rolar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // perguntas rápidas (só com a assistente)
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        listOf("Qual o valor da próxima parcela?", "Como pago com PIX?", "Paguei e não liberou", "Meu celular bloqueou, e agora?", "Quanto fica para quitar?", "Mudar dia do vencimento")
            .forEach { q -> linha.addView(chip(q) { enviar(q) }) }
        atalhos = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(linha) }
        raiz.addView(atalhos)

        linkModo = TextView(this).apply { textSize = 13f; setTextColor(Color.parseColor("#1D4ED8")); setPadding(dp(16), dp(8), dp(16), dp(8)) }
        raiz.addView(linkModo)

        val barra = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(8), dp(10), dp(10)); setBackgroundColor(Color.WHITE); gravity = Gravity.CENTER_VERTICAL }
        campo = EditText(this).apply {
            imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine(false); maxLines = 4
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { enviar(text.toString()); true } else false }
        }
        barra.addView(campo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        barra.addView(Button(this).apply { text = I18n.t("Enviar"); isAllCaps = false; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1D4ED8")); setOnClickListener { enviar(campo.text.toString()) } })
        raiz.addView(barra)
        setContentView(raiz)

        if (humano) abrirHumano() else abrirAssistente()
    }

    override fun onResume() {
        super.onResume()
        atualizar = Runnable { if (humano) carregar(false); atualizar?.let { fundo.postDelayed(it, 15000) } }
        fundo.postDelayed(atualizar!!, 15000)
    }
    override fun onPause() { super.onPause(); atualizar?.let { fundo.removeCallbacks(it) } }

    private fun nome() = st.cache()?.optString("clienteNome").orEmpty().split(" ").firstOrNull().orEmpty()

    /* ---------- modo assistente (sempre o primeiro atendimento) ---------- */
    private fun abrirAssistente() {
        subtitulo.text = "● Assistente virtual · responde na hora"
        campo.hint = "Escreva sua dúvida"
        atalhos.visibility = View.VISIBLE
        linkModo.visibility = View.GONE
        lista.removeAllViews(); historico.clear(); naoEntendeu = 0; naoResolveu = 0; pediuAtendente = 0
        val oi = "Olá" + (if (nome().isNotEmpty()) ", ${nome()}" else "") + "! Sou a assistente virtual da NexumCell."
        bolha(if (bloqueado) "$oi Vi que seu aparelho está bloqueado. Posso te mostrar o motivo e como liberar agora mesmo."
              else "$oi Posso te ajudar com parcelas, pagamento por PIX, quitação, bloqueio e vencimento. Escolha um assunto abaixo ou escreva sua dúvida.", false)
    }

    /* ---------- modo atendente humano ---------- */
    private fun abrirHumano() {
        subtitulo.text = "● Atendimento com a equipe NexumCell"
        campo.hint = I18n.t("Escreva para o Suporte NexumCell")
        atalhos.visibility = View.GONE
        linkModo.visibility = View.VISIBLE
        linkModo.text = "Voltar para a assistente virtual"
        linkModo.setOnClickListener { st.suporteHumanoEm = 0; abrirAssistente() }
        ultimoCount = -1; carregar()
    }

    private fun transferir(motivo: String?) {
        info("Transferindo para um atendente…")
        val d = st.cache()
        val resumo = buildString {
            append("[Cliente transferido pela assistente virtual]")
            if (!motivo.isNullOrBlank()) append("\nPedido: $motivo")
            if (historico.isNotEmpty()) append("\n\nConversa com a assistente:\n" + historico.takeLast(8).joinToString("\n"))
        }
        Thread {
            val ok = try { Api(this).enviarSuporteMsg(resumo.take(3000), bloqueado, d); true } catch (e: Exception) { false }
            runOnUiThread {
                if (!ok) { info("Não consegui chamar o atendente agora. Verifique a internet e toque de novo em Chamar atendente."); return@runOnUiThread }
                st.suporteHumanoEm = System.currentTimeMillis()
                abrirHumano()
            }
        }.start()
    }

    /* ---------- visual ---------- */
    private fun chip(t: String, acao: () -> Unit) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(Color.parseColor("#1D4ED8")); setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.WHITE); setStroke(dp(1), Color.parseColor("#C9D6F2")) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        setOnClickListener { acao() }
    }

    private fun info(t: String) {
        lista.addView(TextView(this).apply {
            text = t; textSize = 13f; setTextColor(Color.parseColor("#5B5A72")); gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun bolha(t: String, minha: Boolean, autor: String? = null): TextView {
        if (autor != null) lista.addView(TextView(this).apply {
            text = autor; textSize = 11f; setTextColor(Color.parseColor("#696784")); setPadding(dp(6), 0, 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        val tv = TextView(this).apply {
            text = t; textSize = 15f; setLineSpacing(4f, 1f); setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextColor(Color.parseColor(if (minha) "#FFFFFF" else "#1B1A2E"))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.parseColor(if (minha) "#1D4ED8" else "#FFFFFF")); if (!minha) setStroke(dp(1), Color.parseColor("#E1E1EC")) }
        }
        lista.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (minha) Gravity.END else Gravity.START; topMargin = dp(if (autor != null) 2 else 8); if (minha) marginStart = dp(48) else marginEnd = dp(48)
        })
        rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
        return tv
    }

    /** Depois de cada resposta a assistente pergunta se resolveu. O atendente só é oferecido em último caso. */
    private fun opcoesDepois(pergunta: String) {
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        linha.addView(Ui.texto(this, "Isso resolveu?", 13f, Ui.TEXTO2).apply { setPadding(dp(4), dp(8), dp(10), 0) })
        linha.addView(chip("Sim") { linha.visibility = View.GONE; naoResolveu = 0; bolha("Que bom! Se precisar de mais alguma coisa, é só escrever aqui.", false) })
        linha.addView(chip("Não") { linha.visibility = View.GONE; naoResolveuCom(pergunta) })
        lista.addView(linha)
        rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun naoResolveuCom(pergunta: String) {
        naoResolveu++
        historico.add("Cliente: não resolveu")
        if (naoResolveu < 2) {
            bolha("Vamos tentar de novo. Me explique com outras palavras o que está acontecendo, ou escolha um dos assuntos abaixo.", false)
            temas()
        } else oferecerAtendente(pergunta, "Desculpe por não ter conseguido resolver por aqui.")
    }

    /** Assuntos para o cliente escolher quando a primeira resposta não serviu. */
    private fun temas() {
        val linha = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), dp(48), 0) }
        listOf("Paguei e não liberou", "Meu celular bloqueou, e agora?", "Valor da próxima parcela", "Quanto fica para quitar?", "Mudar dia do vencimento").forEach { t ->
            linha.addView(chip(t) { linha.visibility = View.GONE; enviar(t) }.apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) } })
        }
        lista.addView(linha)
        rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun oferecerAtendente(pergunta: String, intro: String) {
        bolha("$intro Posso chamar um atendente da equipe NexumCell para continuar com você. O atendimento humano pode levar alguns minutos.", false)
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        linha.addView(chip("Chamar atendente") { linha.visibility = View.GONE; transferir(pergunta) })
        linha.addView(chip("Continuar com a assistente") { linha.visibility = View.GONE; naoResolveu = 0; bolha("Certo! Pode escrever sua dúvida.", false) })
        lista.addView(linha)
        rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    /* ---------- envio ---------- */
    private fun enviar(texto: String) {
        val q = texto.trim(); if (q.isEmpty()) return
        campo.setText("")
        if (humano) { enviarHumano(q); return }
        bolha(q, true); historico.add("Cliente: $q")
        val d = st.cache()
        if (Assistente.querAtendente(q)) {
            pediuAtendente++
            // último caso: na primeira vez a assistente tenta resolver; se o cliente insistir, oferece o atendente
            if (pediuAtendente == 1 && naoResolveu == 0) {
                bolha("Consigo resolver a maioria dos casos na hora, sem espera. Me conta em poucas palavras o que aconteceu?", false)
                temas()
            } else oferecerAtendente(q, "Tudo bem.")
            return
        }
        val local = Assistente.responder(q, d)
        if (local != null) {
            bolha(local.texto, false); historico.add("Assistente: ${local.texto}")
            if (local.transferir) transferir(q) else if (!q.contains("obrigad", true)) opcoesDepois(q)
            return
        }
        // não achou resposta pronta: pergunta para a IA do servidor
        val espera = bolha("Digitando…", false)
        val serv = d?.optString("servidor").orEmpty().ifEmpty { "https://nexumcell-servidor.wevellenerrera21.workers.dev" }.trimEnd('/')
        Thread {
            val resp = try {
                val c = URL("$serv/api/assistente").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 30000
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(JSONObject().put("token", st.token).put("pergunta", q.take(500)).toString().toByteArray()) }
                JSONObject(c.inputStream.bufferedReader().readText()).optString("resposta").takeIf { it.isNotBlank() && it != "null" }?.let { Assistente.limpar(it) }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (resp != null) {
                    espera.text = resp; historico.add("Assistente: $resp"); naoEntendeu = 0; opcoesDepois(q)
                } else if (++naoEntendeu >= 3) {
                    espera.text = "Não consegui entender bem."
                    oferecerAtendente(q, "")
                } else {
                    espera.text = "Não entendi direito. Pode escrever de outro jeito, ou escolher um dos assuntos abaixo."
                    temas()
                }
            }
        }.start()
    }

    private var ultimoCount = -1
    private fun carregar(rolarFim: Boolean = true) {
        Thread {
            val msgs = try { Api(this).buscarSuporteMsgs() } catch (e: Exception) { emptyList() }
            runOnUiThread {
                if (!humano || msgs.size == ultimoCount) return@runOnUiThread
                ultimoCount = msgs.size
                lista.removeAllViews()
                info("Você está falando com a equipe NexumCell. Respondemos aqui mesmo, normalmente em poucos minutos no horário comercial.")
                for (m in msgs) {
                    val t = (m.opt("texto") as? String).orEmpty()
                    if (t.startsWith("[Cliente transferido")) continue // resumo interno para o atendente
                    val minha = (m.opt("de") as? String) == "cliente"
                    bolha(t, minha, if (minha) null else "Atendente NexumCell")
                }
                if (rolarFim) rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }

    private fun enviarHumano(q: String) {
        bolha(q, true)
        val d = st.cache()
        Thread {
            val ok = try { Api(this).enviarSuporteMsg(q, bloqueado, d); true } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) { st.suporteHumanoEm = System.currentTimeMillis(); ultimoCount = -1; carregar() }
                else info(I18n.t("Não consegui enviar agora. Verifique a internet e tente de novo — sua mensagem não foi perdida, é só reenviar."))
            }
        }.start()
    }
}
