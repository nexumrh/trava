package br.com.nexumcell.trava

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** App do cliente: situação do contrato e "Minhas faturas" com pagamento por PIX. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnAtualizar).setOnClickListener { atualizar() }
    }

    override fun onResume() {
        super.onResume()
        mostrar(); I18n.traduzir(window.decorView)
        atualizar()
    }

    private fun atualizar() {
        val btn = findViewById<Button>(R.id.btnAtualizar)
        btn.isEnabled = false
        btn.text = "Atualizando…"
        Thread {
            Sync.executar(applicationContext)
            runOnUiThread { btn.isEnabled = true; btn.text = I18n.t("Atualizar"); mostrar(); I18n.traduzir(window.decorView) }
        }.start()
    }

    private fun mostrar() {
        val st = Store(this)
        val sit = findViewById<TextView>(R.id.tvSituacao)
        val tv = findViewById<TextView>(R.id.tvStatus)
        val lista = findViewById<LinearLayout>(R.id.listaFaturas)
        val rodape = findViewById<TextView>(R.id.tvRodape)
        if (st.finalizado) {
            sit.text = "Contrato quitado"
            tv.text = "Parabéns! Você pagou todas as parcelas. A trava foi removida e o aparelho é todo seu. Você já pode desinstalar este aplicativo."
            lista.removeAllViews(); return
        }
        if (!Policy.isOwner(this)) {
            sit.text = "App não ativado"
            tv.text = "Este aplicativo precisa ser instalado pela loja, lendo o QR code na primeira tela do aparelho (após restauração de fábrica)."
            return
        }
        val d = st.cache()
        if (d == null) {
            sit.text = "Configurando…"
            tv.text = "Conecte o aparelho à internet para carregar o seu contrato."
            return
        }
        val r = Decisao.avaliar(d, System.currentTimeMillis(), st.ultimoSyncOk)
        val fat = Faturas.lista(d)
        val abertas = fat.filter { !it.pago }
        val atrasadas = abertas.filter { it.diasAtraso > 0 }
        sit.text = when {
            r.travar -> "Aparelho bloqueado"
            atrasadas.isNotEmpty() -> "Fatura em atraso"
            else -> "Suas faturas estão em dia"
        }
        val prox = abertas.minByOrNull { it.venc }
        tv.text = buildString {
            append("Contrato nº ${d.optLong("vendaNumero")} · ${d.optString("marca")} ${d.optString("modelo")}\n")
            append("${fat.count { it.pago }} de ${fat.size} parcelas pagas")
            if (prox != null && atrasadas.isEmpty()) append("\nPróximo vencimento: ${prox.venc.format(Faturas.fmt)} · ${Faturas.brl.format(prox.valor)}")
            if (atrasadas.isNotEmpty()) append("\nTotal em atraso: ${Faturas.brl.format(atrasadas.sumOf { it.aPagar })} (com multa e juros)")
            if ((d.optDouble("descontoPct", 0.0)) > 0) append("\nPague antes do vencimento e ganhe ${d.optDouble("descontoPct").toInt()}% de desconto.")
        }
        Faturas.desenhar(this, lista, d)
        Faturas.quitacao(d)?.let { (valor, economia) ->
            if (economia > 0.01) lista.addView(android.widget.Button(this).apply {
                text = "Quitar o contrato agora: ${Faturas.brl.format(valor)} (economize ${Faturas.brl.format(economia)})"
                isAllCaps = false; setTextColor(android.graphics.Color.parseColor("#0E8A5F"))
                setBackgroundResource(R.drawable.btn_outline_claro)
                setOnClickListener { Faturas.mostrarPix(this@MainActivity, d, valor, "Quitação antecipada do contrato nº ${d.optLong("vendaNumero")}", "NXC${d.optLong("vendaNumero")}Q") }
            })
        }
        val loja = listOf(d.optString("lojaNome"), d.optString("lojaTelefone")).filter { it.isNotEmpty() }.joinToString(" · ")
        rodape.text = buildString {
            if (loja.isNotEmpty()) append("Loja: $loja\n")
            if (st.ultimoSyncOk > 0) append("Atualizado em ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(st.ultimoSyncOk))}")
        }
    }
}
