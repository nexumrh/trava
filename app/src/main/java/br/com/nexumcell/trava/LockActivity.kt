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
        preencher()
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

    @Deprecated("Bloqueado de propósito")
    override fun onBackPressed() { /* não sai da tela de bloqueio */ }

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
        btn.text = "Verificando…"
        Thread {
            val online = Sync.executar(applicationContext)
            runOnUiThread {
                verificando = false
                btn.isEnabled = true
                btn.text = getString(R.string.ja_paguei)
                if (!Store(this).travado) { sair(); return@runOnUiThread }
                preencher()
                Toast.makeText(this,
                    if (online) "Pagamento ainda não identificado. Se acabou de pagar, envie o comprovante para a loja."
                    else "Sem internet. Conecte ao Wi-Fi ou dados móveis e tente de novo.",
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun preencher() {
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
