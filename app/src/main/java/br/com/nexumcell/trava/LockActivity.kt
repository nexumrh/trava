package br.com.nexumcell.trava

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tela exibida enquanto o aparelho estiver travado (modo quiosque / lock task). */
class LockActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private val brl = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
    private val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private var verificando = false

    private val vigia = object : Runnable {
        override fun run() {
            if (!Store(this@LockActivity).travado) { sair(); return }
            h.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        setContentView(R.layout.activity_lock)

        findViewById<Button>(R.id.btnVerificar).setOnClickListener { verificar() }
        findViewById<Button>(R.id.btnLoja).setOnClickListener {
            val tel = Store(this).cache()?.optString("lojaTelefone").orEmpty().filter { it.isDigit() }
            discar(tel)
        }
        findViewById<Button>(R.id.btnEmergencia).setOnClickListener { discar("") }
        findViewById<Button>(R.id.btnPagar).setOnClickListener {
            val d = Store(this).cache() ?: return@setOnClickListener
            val atrasadas = Faturas.lista(d).filter { !it.pago && it.diasAtraso > 0 }
            val total = Math.round(atrasadas.sumOf { it.aPagar } * 100) / 100.0
            if (total <= 0) { verificar(); return@setOnClickListener }
            Faturas.mostrarPix(this, d, total, "${atrasadas.size} parcela(s) em atraso, com multa e juros", "NXC${d.optLong("vendaNumero")}AT")
        }
        findViewById<Button>(R.id.btnConfianca).setOnClickListener { pedirConfianca() }
        findViewById<Button>(R.id.btnPromessa).setOnClickListener { pedirPromessa() }
        findViewById<Button>(R.id.btnCodigo).setOnClickListener { pedirCodigo() }
        findViewById<View>(R.id.tvPix).setOnClickListener {
            val pix = Store(this).cache()?.optString("chavePix").orEmpty()
            if (pix.isNotEmpty()) {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PIX", pix))
                Toast.makeText(this, "Chave PIX copiada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!Store(this).travado) { sair(); return }
        TravaTela.esconder(this)
        preencher(); I18n.traduzir(window.decorView)
        try {
            if (Policy.dpm(this).isLockTaskPermitted(packageName)) startLockTask()
        } catch (e: Exception) { }
        h.removeCallbacks(vigia)
        h.post(vigia)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(vigia)
    }

    // Instalado por link (sem modo quiosque): se o cliente sair da tela, ela volta.
    private val voltar = Runnable { if (Store(this).travado && !Policy.isOwner(this)) Policy.abrirTela(this) }
    override fun onStop() {
        super.onStop()
        if (Store(this).travado && !Policy.isOwner(this)) { TravaTela.mostrar(this); h.removeCallbacks(voltar); h.postDelayed(voltar, 400) }
    }
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Store(this).travado && !Policy.isOwner(this)) { h.removeCallbacks(voltar); h.postDelayed(voltar, 400) }
    }

    @Deprecated("Bloqueado de propósito")
    override fun onBackPressed() { /* não sai da tela de bloqueio */ }

    private fun atualizarConfianca() {
        val st = Store(this); val d = st.cache()
        val ok = d != null && Decisao.podeConfianca(d) && st.motivo.contains("atraso")
        findViewById<Button>(R.id.btnConfianca).visibility = if (ok) View.VISIBLE else View.GONE
        val podePromessa = d != null && d.optInt("promessaMaxDias", 0) > 0 && d.optJSONObject("promessaApp") == null && st.motivo.contains("atraso")
        findViewById<Button>(R.id.btnPromessa).visibility = if (podePromessa) View.VISIBLE else View.GONE
    }

    /** O cliente escolhe o dia em que vai pagar; o servidor libera o aparelho até lá (uma vez por contrato). */
    private fun pedirPromessa() {
        val d = Store(this).cache() ?: return
        val max = d.optInt("promessaMaxDias", 5).coerceIn(1, 10)
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEEE, dd/MM", java.util.Locale("pt", "BR"))
        val hoje = java.time.LocalDate.now()
        val dias = (1..max).map { hoje.plusDays(it.toLong()) }
        val atrasadas = Faturas.lista(d).filter { !it.pago && it.diasAtraso > 0 }
        val total = Math.round(atrasadas.sumOf { it.aPagar } * 100) / 100.0
        android.app.AlertDialog.Builder(this)
            .setTitle(I18n.t("Até quando você vai pagar?") + " (R$ " + String.format(java.util.Locale("pt", "BR"), "%.2f", total) + ")")
            .setItems(dias.map { it.format(fmt) }.toTypedArray()) { _, i -> confirmarPromessa(dias[i].toString(), dias[i].format(fmt)) }
            .setNegativeButton(I18n.t("Cancelar"), null).show()
    }

    private fun confirmarPromessa(data: String, rotulo: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(I18n.t("Confirmar a promessa?"))
            .setMessage(I18n.t("O celular fica liberado até") + " " + rotulo + ". " + I18n.t("Você só pode usar isso uma vez neste contrato. Se o pagamento não cair até essa data, o celular volta a ser bloqueado."))
            .setPositiveButton(I18n.t("Confirmar")) { _, _ ->
                val b = findViewById<Button>(R.id.btnPromessa); b.isEnabled = false
                Thread {
                    var msg: String
                    try {
                        val st = Store(this); val d = st.cache()
                        val serv = d?.optString("servidor").orEmpty().ifEmpty { d?.optString("servidorPix").orEmpty() }.ifEmpty { "https://nexumcell-servidor.wevellenerrera21.workers.dev" }.trimEnd('/')
                        val c = java.net.URL("$serv/api/cliente-app").openConnection() as java.net.HttpURLConnection
                        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 20000; c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(org.json.JSONObject().put("acao", "promessa").put("token", st.token).put("data", data).toString().toByteArray()) }
                        val code = c.responseCode
                        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
                        val j = try { org.json.JSONObject(txt) } catch (e: Exception) { org.json.JSONObject() }
                        if (code in 200..299) {
                            d?.let { x -> x.put("liberadoAte", j.optLong("liberadoAte")); x.put("promessaApp", org.json.JSONObject().put("data", data)); st.cacheJson = x.toString() }
                            Sync.executar(this); msg = I18n.t("Promessa registrada. O celular está liberado até") + " " + rotulo + "."
                        } else msg = j.optString("erro").ifEmpty { I18n.t("Não foi possível agora. Tente de novo.") }
                    } catch (e: Exception) { msg = I18n.t("Sem internet. Conecte e tente de novo.") }
                    runOnUiThread {
                        b.isEnabled = true
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        if (!Store(this).travado) sair() else { atualizarConfianca(); preencher() }
                    }
                }.start()
            }
            .setNegativeButton(I18n.t("Cancelar"), null).show()
    }

    private fun pedirConfianca() {
        android.app.AlertDialog.Builder(this)
            .setTitle(I18n.t("Liberar por 24 horas?"))
            .setMessage(I18n.t("Você pode usar o celular normalmente por 24 horas, uma vez por mês. As parcelas continuam em atraso, com multa e juros. Depois das 24 horas, se não houver pagamento, o aparelho volta a ser bloqueado."))
            .setPositiveButton(I18n.t("Liberar")) { _, _ ->
                val b = findViewById<Button>(R.id.btnConfianca); b.isEnabled = false
                Thread {
                    val ok = try {
                        val ate = System.currentTimeMillis() + 86_400_000L; val mes = Decisao.mesAtual()
                        Api(this).pedirConfianca(ate, mes)
                        val st = Store(this); st.cache()?.let { c ->
                            c.put("confianca", org.json.JSONObject().put("ate", ate).put("mes", mes)); st.cacheJson = c.toString() }
                        Sync.executar(this); true
                    } catch (e: Exception) { false }
                    runOnUiThread {
                        b.isEnabled = true
                        Toast.makeText(this, I18n.t(if (ok) "Liberado por 24 horas. Pague o quanto antes." else "Não foi possível liberar. Verifique a internet ou se já usou este mês."), Toast.LENGTH_LONG).show()
                        atualizarConfianca()
                    }
                }.start()
            }
            .setNegativeButton(I18n.t("Cancelar"), null).show()
    }

    /** Código de 6 dígitos dado pela loja: libera sem internet. */
    private fun pedirCodigo() {
        val ed = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
            hint = "000000"; textSize = 24f; gravity = android.view.Gravity.CENTER
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(I18n.t("Código da loja"))
            .setMessage(I18n.t("Digite o código de 6 números que a loja passou. Funciona mesmo sem internet."))
            .setView(ed)
            .setPositiveButton(I18n.t("Liberar")) { _, _ ->
                val txt = ed.text.toString().filter { it.isDigit() }
                // código de 10 dígitos = remoção definitiva da trava (equipe)
                if (txt.length == 10 && Remocao.confere(this, txt)) { confirmarRemocao(); return@setPositiveButton }
                val erro = Codigo.validar(this, txt)
                if (erro != null) { Toast.makeText(this, erro, Toast.LENGTH_LONG).show(); return@setPositiveButton }
                Thread { Sync.executar(applicationContext); runOnUiThread {
                    Toast.makeText(this, I18n.t("Aparelho liberado pela loja."), Toast.LENGTH_LONG).show()
                    if (!Store(this).travado) sair() } }.start()
            }
            .setNegativeButton(I18n.t("Cancelar"), null).show()
    }

    /** Código de remoção válido: confirma e tira a trava da NexumCell deste aparelho. */
    private fun confirmarRemocao() {
        android.app.AlertDialog.Builder(this)
            .setTitle(I18n.t("Remover a trava deste aparelho?"))
            .setMessage(I18n.t("A proteção da NexumCell vai sair do aparelho e o app será desinstalado. Isso não pode ser desfeito. Use só quando a loja autorizar a remoção."))
            .setPositiveButton(I18n.t("Remover a trava")) { _, _ ->
                Thread {
                    try { Remocao.executar(applicationContext) } catch (e: Exception) { }
                    runOnUiThread {
                        Toast.makeText(this, I18n.t("Trava removida. Pode desinstalar o app NexumCell."), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }.start()
            }
            .setNegativeButton(I18n.t("Cancelar"), null).show()
    }

    private fun sair() {
        h.removeCallbacks(vigia)
        try { stopLockTask() } catch (e: Exception) { }
        finish()
    }

    private fun discar(numero: String) {
        try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$numero"))) }
        catch (e: Exception) { Toast.makeText(this, "Discador indisponível", Toast.LENGTH_SHORT).show() }
    }

    private fun verificar() {
        if (verificando) return
        verificando = true
        val btn = findViewById<Button>(R.id.btnVerificar)
        btn.isEnabled = false
        btn.text = I18n.t("Verificando…")
        Thread {
            val online = Sync.executar(applicationContext)
            runOnUiThread {
                verificando = false
                btn.isEnabled = true
                btn.text = getString(R.string.ja_paguei)
                if (!Store(this).travado) { sair(); return@runOnUiThread }
                preencher(); I18n.traduzir(window.decorView)
                Toast.makeText(this,
                    if (online) "Pagamento ainda não identificado. Se acabou de pagar, envie o comprovante para a loja."
                    else "Sem internet. Conecte ao Wi-Fi ou dados móveis e tente de novo.",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun preencher() {
        atualizarConfianca()
        val st = Store(this)
        val d = st.cache()
        findViewById<TextView>(R.id.tvMotivo).text = st.motivo.ifEmpty { "Aparelho bloqueado" }
        findViewById<TextView>(R.id.tvMensagem).text = d?.optString("mensagem").orEmpty()
            .ifEmpty { "Seu aparelho foi bloqueado por atraso no pagamento. Regularize para liberar o uso." }

        val sb = StringBuilder()
        var total = 0.0
        val hoje = LocalDate.now()
        val parcelas = d?.optJSONArray("parcelas")
        if (parcelas != null) for (i in 0 until parcelas.length()) {
            val p = parcelas.optJSONObject(i) ?: continue
            if (p.optBoolean("pago")) continue
            val venc = try { LocalDate.parse(p.optString("venc")) } catch (e: Exception) { continue }
            if (!venc.isAfter(hoje)) {
                val v = p.optDouble("valor", 0.0)
                sb.append("Parcela ${p.optInt("n")} · venceu ${venc.format(fmt)} · ${brl.format(v)}\n")
            }
        }
        total = d?.let { Faturas.lista(it).filter { f -> !f.pago && f.diasAtraso > 0 }.sumOf { f -> f.aPagar } } ?: 0.0
        if (total > 0) sb.append("\nTotal para regularizar hoje: ${brl.format(total)} (com multa e juros)")
        findViewById<TextView>(R.id.tvParcelas).apply {
            text = sb.toString().trim()
            visibility = if (sb.isEmpty()) View.GONE else View.VISIBLE
        }

        val pix = d?.optString("chavePix").orEmpty()
        findViewById<TextView>(R.id.tvPix).apply {
            text = "Chave PIX: $pix\n(toque para copiar)"
            visibility = if (pix.isEmpty()) View.GONE else View.VISIBLE
        }
        val loja = d?.optString("lojaNome").orEmpty()
        val tel = d?.optString("lojaTelefone").orEmpty()
        findViewById<TextView>(R.id.tvLoja).text = listOf(loja, tel).filter { it.isNotEmpty() }.joinToString(" · ")
        findViewById<Button>(R.id.btnLoja).visibility = if (tel.isEmpty()) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tvRodape).text =
            "Contrato nº ${d?.optLong("vendaNumero") ?: "-"} · IMEI ${d?.optString("imei").orEmpty()}\nChamadas de emergência continuam disponíveis."
    }
}
