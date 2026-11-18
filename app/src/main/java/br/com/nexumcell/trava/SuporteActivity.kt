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
    /** A assistente tenta resolver até 5 vezes antes de oferecer o atendente humano. */
    private val LIMITE = 5
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
        (if (bloqueado) listOf("Liberar meu celular") else emptyList<String>()) + listOf("Preciso de mais prazo", "Qual o valor da próxima parcela?", "Como pago com PIX?", "Paguei e não liberou", "Meu celular bloqueou, e agora?", "Quanto fica para quitar?", "Mudar dia do vencimento")
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
        // aberto pela tela de bloqueio: tira a sobreposição enquanto o cliente conversa com a assistente
        if (st.travado) { if (st.saidaAte < System.currentTimeMillis()) st.saidaAte = System.currentTimeMillis() + 15 * 60_000L; TravaTela.esconder(this) }
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
        bolha(if (bloqueado) "$oi Vi que seu aparelho está bloqueado. Posso tentar liberar agora por 24 horas para você pagar com calma."
              else "$oi Posso te ajudar com parcelas, pagamento por PIX, mais prazo para pagar, quitação e vencimento. Escolha um assunto abaixo ou escreva sua dúvida.", false)
        if (bloqueado) {
            val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            linha.addView(chip("Liberar meu celular") { linha.visibility = View.GONE; enviar("Liberar meu celular") })
            linha.addView(chip("Por que bloqueou?") { linha.visibility = View.GONE; enviar("Meu celular bloqueou, e agora?") })
            lista.addView(linha)
        }
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

    private var transferindo = false
    private fun transferir(motivo: String?) {
        // evita mandar o mesmo pedido duas vezes (toque duplo ou resposta que já transfere)
        if (transferindo || System.currentTimeMillis() - st.suporteHumanoEm < 20_000L) { abrirHumano(); return }
        transferindo = true
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
                transferindo = false
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
        if (naoResolveu < LIMITE) {
            bolha("Vamos tentar de novo (tentativa ${naoResolveu + 1} de $LIMITE). Me explique com outras palavras o que está acontecendo, ou escolha um dos assuntos abaixo.", false)
            temas()
        } else oferecerAtendente(pergunta, "Desculpe por não ter conseguido resolver por aqui.")
    }

    /** Assuntos para o cliente escolher quando a primeira resposta não serviu. */
    private fun temas() {
        val linha = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), dp(48), 0) }
        ((if (bloqueado || st.travado) listOf("Liberar meu celular") else emptyList<String>()) + listOf("Paguei e não liberou", "Meu celular bloqueou, e agora?", "Preciso de mais prazo", "Valor da próxima parcela", "Quanto fica para quitar?", "Mudar dia do vencimento")).forEach { t ->
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
        val ql = q.lowercase()
        if (esperandoBo && Regex("\\d{5,}").containsMatchIn(q)) { enviarBo(q); return }
        if (Regex("roub|furt|assalt|perdi (o |meu )?(celular|aparelho|telefone)|perdeu|levaram (o |meu )?(celular|aparelho)").containsMatchIn(ql)) { pedirRoubo(q); return }
        if (Regex("liberar meu celular|desbloque|destrav|liberar o celular|liberar o aparelho|libera meu|liberação").containsMatchIn(ql) && (bloqueado || st.travado)) { pedirLiberacao(q); return }
        if (Regex("mais prazo|prazo extra|mais tempo|prorrog|adiar|48 ?h|dois dias|2 dias|pagar depois|pagar mais tarde|atrasar").containsMatchIn(ql)) { pedirPrazo(); return }
        if (Assistente.querAtendente(q)) {
            pediuAtendente++
            // último caso: na primeira vez a assistente tenta resolver; se o cliente insistir, oferece o atendente
            if (pediuAtendente + naoResolveu < LIMITE) {
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
                c.outputStream.use { it.write(JSONObject().put("token", st.token).put("pergunta", q.take(500)).put("origem", "app").toString().toByteArray()) }
                JSONObject(c.inputStream.bufferedReader().readText()).optString("resposta").takeIf { it.isNotBlank() && it != "null" }?.let { Assistente.limpar(it) }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (resp != null) {
                    espera.text = resp; historico.add("Assistente: $resp"); naoEntendeu = 0; opcoesDepois(q)
                } else if (++naoEntendeu >= LIMITE) {
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
                    if (t.startsWith("[Cliente transferido")) {
                        // o resumo completo é para o atendente; o cliente vê o próprio pedido e o aviso de que a equipe recebeu
                        val ped = Regex("Pedido:\\s*([^\\n]*)").find(t)?.groupValues?.get(1).orEmpty()
                        if (ped.isNotBlank()) bolha(ped, true)
                        info("Seu atendimento foi passado para a equipe NexumCell. Responderemos aqui mesmo. Pode escrever mais detalhes enquanto isso.")
                        continue
                    }
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

    /* ---------- prazo extra e liberação feitos pela assistente ---------- */
    private fun servidor(): String = st.cache()?.optString("servidor").orEmpty().ifEmpty { st.cache()?.optString("servidorPix").orEmpty() }.ifEmpty { "https://nexumcell-servidor.wevellenerrera21.workers.dev" }.trimEnd('/')
    private fun acaoCliente(acao: String, extra: JSONObject.() -> Unit = {}): Pair<Int, JSONObject> {
        val c = URL("${servidor()}/api/cliente-app").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 25000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        val corpo = JSONObject().put("acao", acao).put("token", st.token).apply(extra)
        c.outputStream.use { it.write(corpo.toString().toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to (try { JSONObject(txt) } catch (e: Exception) { JSONObject() })
    }
    private fun dataHora(ms: Long): String = java.text.SimpleDateFormat("dd/MM 'às' HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(ms))
    private fun aplicarLiberacao(ate: Long) {
        st.cache()?.let { c -> c.put("liberadoAte", maxOf(ate, c.optLong("liberadoAte", 0L))); st.cacheJson = c.toString() }
        Thread { try { Sync.executar(this) } catch (e: Exception) { } }.start()
    }

    private fun pedirPrazo() {
        historico.add("Assistente: ofereceu prazo extra")
        bolha("Posso te dar até 48 horas a mais para pagar a parcela que está vencendo, sem bloquear o celular. Esse prazo extra vale 1 vez por parcela. Quer usar agora?", false)
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        linha.addView(chip("Sim, quero o prazo") { linha.visibility = View.GONE; bolha("Sim, quero o prazo", true); confirmarPrazo() })
        linha.addView(chip("Agora não") { linha.visibility = View.GONE; bolha("Tudo bem! Se precisar, é só pedir aqui.", false) })
        lista.addView(linha); rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
    }
    private fun confirmarPrazo() {
        val espera = bolha("Um momento…", false)
        Thread {
            val r = try { acaoCliente("prazo") } catch (e: Exception) { null }
            runOnUiThread {
                if (r == null) { espera.text = "Estou sem internet agora. Conecte no Wi-Fi ou nos dados e peça de novo."; return@runOnUiThread }
                val (code, j) = r
                if (code in 200..299 && j.optBoolean("ok")) {
                    val ate = j.optLong("liberadoAte"); aplicarLiberacao(ate)
                    espera.text = "Pronto! A parcela ${j.optInt("parcela")} ganhou ${j.optInt("horas", 48)} horas a mais. Seu celular não será bloqueado até ${dataHora(ate)}. Se o pagamento não cair até lá, ele volta a bloquear. Você pode pagar pelo PIX na aba Parcelas."
                    historico.add("Assistente: deu prazo extra até ${dataHora(ate)}")
                } else {
                    espera.text = j.optString("erro").ifEmpty { "Não consegui dar o prazo agora. Tente de novo em alguns minutos." }
                    opcoesDepois("Preciso de mais prazo")
                }
            }
        }.start()
    }

    private fun pedirLiberacao(pedido: String) {
        val espera = bolha("Vou verificar seu contrato…", false)
        Thread {
            val r = try { acaoCliente("liberar") { put("pedido", pedido.take(300)) } } catch (e: Exception) { null }
            runOnUiThread {
                if (r == null) { espera.text = "Estou sem internet agora. Conecte no Wi-Fi ou nos dados e peça de novo. Sem internet, a loja pode passar um código de liberação."; return@runOnUiThread }
                val (code, j) = r
                when {
                    code in 200..299 && j.optBoolean("ok") -> {
                        val ate = j.optLong("liberadoAte"); aplicarLiberacao(ate)
                        espera.text = if (j.optBoolean("jaLiberado")) "Seu celular já está liberado até ${dataHora(ate)}. Se a tela de bloqueio ainda aparecer, toque em Verificar."
                            else "Liberei seu celular por 24 horas, até ${dataHora(ate)}. A parcela continua em atraso: pague pelo PIX na aba Parcelas para não bloquear de novo."
                        historico.add("Assistente: liberou 24 h")
                    }
                    code in 200..299 && j.optBoolean("pendente") -> {
                        espera.text = "Não consegui liberar automaticamente pelo seu histórico de pagamentos. Enviei seu pedido para a equipe NexumCell, que pode liberar por 24 ou 48 horas. Assim que decidirem, o celular destrava sozinho. Pagando pelo PIX, ele libera na hora."
                        historico.add("Assistente: pedido de liberação enviado ao suporte")
                    }
                    else -> {
                        espera.text = j.optString("erro").ifEmpty { "Não consegui verificar agora. Tente de novo em alguns minutos." }
                        if (code == 409) oferecerAtendente(pedido, "") else opcoesDepois(pedido)
                    }
                }
            }
        }.start()
    }



    /* ---------- roubo, furto ou perda: a assistente bloqueia na hora e explica o que fazer ---------- */
    private var esperandoBo = false
    private fun pedirRoubo(relato: String) {
        historico.add("Assistente: perguntou o tipo (roubo/furto/perda)")
        bolha("Sinto muito pelo que aconteceu. Posso bloquear agora os seus aparelhos NexumCell para que ninguém consiga usar nem resetar. O que aconteceu?", false)
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        listOf("Roubo", "Furto", "Perda").forEach { t -> linha.addView(chip(t) { linha.visibility = View.GONE; bolha(t, true); confirmarRoubo(t, relato) }) }
        linha.addView(chip("Foi engano") { linha.visibility = View.GONE; bolha("Tudo bem, nada foi bloqueado. Posso ajudar em mais alguma coisa?", false) })
        lista.addView(linha); rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
    }
    private fun confirmarRoubo(tipo: String, relato: String) {
        val espera = bolha("Bloqueando…", false)
        Thread {
            val r = try { acaoCliente("roubo") { put("tipo", tipo); put("relato", relato.take(300)) } } catch (e: Exception) { null }
            runOnUiThread {
                if (r == null || r.first !in 200..299 || !r.second.optBoolean("ok")) {
                    espera.text = "Não consegui bloquear agora porque estou sem conexão. Tente de novo em instantes; se não der, use o portal do cliente em outro celular: Comunicar roubo, furto ou perda."
                    return@runOnUiThread
                }
                val n = r.second.optInt("bloqueados", 1)
                val bo = if (tipo == "Roubo") "ligue 190 ou vá à delegacia e registre o boletim de ocorrência" else "registre o boletim de ocorrência na Delegacia Eletrônica do seu estado"
                espera.text = "Pronto: bloqueei ${if (n > 1) "os seus $n aparelhos" else "o seu aparelho"}. Ninguém consegue usar nem resetar.\n\n" +
                    "Agora faça isto:\n1. Boletim de ocorrência: $bo.\n2. Bloqueie o celular, o chip e os apps de banco no Celular Seguro (gov.br).\n" +
                    "3. Peça à sua operadora o bloqueio da linha e um chip novo com o mesmo número.\n4. Quando tiver o número do boletim, envie aqui neste chat.\n\n" +
                    "As parcelas continuam valendo. Se o aparelho for encontrado, a loja libera o uso."
                historico.add("Assistente: bloqueou por $tipo")
                esperandoBo = true
                rolar.post { rolar.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }
    private fun enviarBo(q: String) {
        val bo = Regex("[\\d./-]{5,}").find(q)?.value.orEmpty().ifEmpty { q.take(60) }
        val espera = bolha("Anexando o boletim…", false)
        Thread {
            val r = try { acaoCliente("roubo") { put("bo", bo) } } catch (e: Exception) { null }
            runOnUiThread {
                if (r != null && r.first in 200..299) { esperandoBo = false; espera.text = "Recebi o boletim nº $bo e anexei ao seu contrato. A equipe NexumCell acompanha o caso; se o aparelho for encontrado, você será avisado." }
                else espera.text = "Não consegui enviar agora. Tente de novo em instantes."
            }
        }.start()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (st.travado) { st.saidaAte = 0L; Policy.abrirTela(this) }
    }
    override fun finish() {
        val volta = st.travado
        super.finish()
        if (volta) { st.saidaAte = 0L; Policy.abrirTela(this) }
    }

}
