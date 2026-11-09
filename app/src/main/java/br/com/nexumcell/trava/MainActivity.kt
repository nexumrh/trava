package br.com.nexumcell.trava

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
        lerLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); lerLink(intent)
    }

    /** Link de ativação aberto no navegador: nexumcell://ativar?t=TOKEN&k=APIKEY&p=PROJETO */
    private fun lerLink(i: Intent?) {
        val u = i?.data ?: return
        if (u.scheme != "nexumcell" || u.host != "ativar") return
        val st = Store(this)
        if (Policy.isOwner(this)) return
        val t = u.getQueryParameter("t").orEmpty(); val k = u.getQueryParameter("k").orEmpty(); val p = u.getQueryParameter("p").orEmpty()
        if (t.length < 10 || k.isEmpty() || p.isEmpty()) return
        // não deixa trocar o contrato de um aparelho já ativado
        if (st.token.isNotEmpty() && st.token != t && st.linkAtivadoEm > 0) return
        st.token = t; st.apiKey = k; st.projectId = p; st.modoLink = true
    }

    private fun bateriaLivre(): Boolean = Build.VERSION.SDK_INT < 23 ||
        (getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true)

    /** Passos da instalação por link. Retorna true quando falta algo (e desenha os botões). */
    private fun passosLink(sit: TextView, tv: TextView, lista: LinearLayout): Boolean {
        val st = Store(this)
        val passos = mutableListOf<Pair<String, () -> Unit>>()
        if (!Policy.adminAtivo(this)) passos.add("1. Ativar a proteção (administrador)" to {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Necessário para o crediário NexumCell: bloqueia o aparelho em caso de atraso. Ao quitar, a proteção é removida."))
        })
        if (!Policy.sobreposicao(this)) passos.add("2. Permitir sobrepor a outros apps" to {
            try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        })
        if (!bateriaLivre()) passos.add("3. Não economizar bateria neste app" to {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            passos.add("4. Permitir notificações" to { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.READ_PHONE_STATE), 7) })
        if (passos.isEmpty()) {
            if (st.linkAtivadoEm == 0L) { st.linkAtivadoEm = System.currentTimeMillis(); st.adminRemovidoEm = 0L; Setup.concluir(applicationContext, null) }
            return false
        }
        sit.text = if (st.linkAtivadoEm > 0) "Proteção desativada" else "Ativar o NexumCell"
        tv.text = if (st.linkAtivadoEm > 0) "A proteção do aparelho foi desativada e o aparelho está bloqueado. Toque nos botões abaixo para ativar de novo."
            else "Instalação pelo link da loja. Toque em cada botão abaixo, na ordem, e aceite a permissão. Depois volte para este app."
        lista.removeAllViews()
        passos.forEach { (t, acao) ->
            lista.addView(Button(this).apply {
                text = t; isAllCaps = false; setTextColor(android.graphics.Color.parseColor("#0A1733"))
                setBackgroundResource(R.drawable.btn_outline_claro)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
                setOnClickListener { try { acao() } catch (e: Exception) { } }
            })
        }
        return true
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
            if (!st.modoLink || !st.configurado) {
                sit.text = "App não ativado"
                tv.text = "Este aplicativo precisa ser ativado pela loja: pelo QR code na primeira tela do aparelho, ou pelo link de instalação que a loja envia (abra o link no navegador e toque em “Ativar”)."
                lista.removeAllViews(); return
            }
            if (passosLink(sit, tv, lista)) return
        }
        val d = st.cache()
        if (d == null) {
            sit.text = "Configurando…"
            tv.text = "Conecte o aparelho à internet para carregar o seu contrato."
            return
        }
        val r = Decisao.avaliar(d, System.currentTimeMillis(), st.ultimoSyncOk, st.liberadoLocalAte)
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
