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
        aplicarLink(u)
    }

    /** Aceita nexumcell://ativar?t=..&k=..&p=.. ou o link da página (…?instalar=TOKEN&k=..&p=..). */
    private fun aplicarLink(u: Uri): Boolean {
        val st = Store(this)
        if (Policy.isOwner(this)) return false
        val t = (u.getQueryParameter("t") ?: u.getQueryParameter("instalar")).orEmpty().trim()
        val k = u.getQueryParameter("k").orEmpty().trim(); val p = u.getQueryParameter("p").orEmpty().trim()
        if (t.length < 10 || k.isEmpty() || p.isEmpty()) return false
        // não deixa trocar o contrato de um aparelho já ativado
        if (st.token.isNotEmpty() && st.token != t && st.linkAtivadoEm > 0) return false
        st.token = t; st.apiKey = k; st.projectId = p; st.modoLink = true
        return true
    }

    private fun usarTexto(txt: String?) {
        val ok = try { !txt.isNullOrBlank() && aplicarLink(Uri.parse(txt.trim())) } catch (e: Exception) { false }
        android.widget.Toast.makeText(this, if (ok) "Contrato encontrado! Agora ative a proteção." else "Este QR não é o da instalação NexumCell. Leia o QR da tela do vendedor.", android.widget.Toast.LENGTH_LONG).show()
        if (ok) { mostrar(); atualizar() }
    }

    /** Leitor de QR do Google (não precisa de permissão de câmera). */
    private fun lerQr() {
        try {
            val op = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
            com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(this, op).startScan()
                .addOnSuccessListener { b -> usarTexto(b.rawValue) }
                .addOnFailureListener { android.widget.Toast.makeText(this, "Não foi possível abrir o leitor. Confira a internet ou use “Colar o link”.", android.widget.Toast.LENGTH_LONG).show() }
        } catch (e: Throwable) {
            android.widget.Toast.makeText(this, "Leitor indisponível neste aparelho. Use “Colar o link”.", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun colarLink() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        val atual = try { cm?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (e: Exception) { null }.orEmpty()
        val campo = android.widget.EditText(this).apply { hint = "Cole aqui o link da instalação"; setText(if (atual.contains("instalar=") || atual.startsWith("nexumcell:")) atual else "") }
        android.app.AlertDialog.Builder(this).setTitle("Link da instalação").setView(campo)
            .setPositiveButton("Usar") { _, _ -> usarTexto(campo.text.toString()) }.setNegativeButton("Cancelar", null).show()
    }

    private fun botao(lista: LinearLayout, t: String, acao: () -> Unit) {
        lista.addView(Button(this).apply {
            text = t; isAllCaps = false; setTextColor(android.graphics.Color.parseColor("#0A1733"))
            setBackgroundResource(R.drawable.btn_outline_claro)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
            setOnClickListener { try { acao() } catch (e: Exception) { } }
        })
    }

    /** Primeira abertura (instalação por link): pega o contrato copiado pela página ou abre o leitor de QR sozinho. */
    private var tentouAuto = false
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || tentouAuto || Policy.isOwner(this)) return
        val st = Store(this); if (st.configurado || st.finalizado) return
        tentouAuto = true
        val clip = try { getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() } catch (e: Exception) { null }.orEmpty().trim()
        if (clip.startsWith("nexumcell://ativar") || clip.contains("instalar=")) {
            val ok = try { aplicarLink(Uri.parse(clip)) } catch (e: Exception) { false }
            if (ok) { android.widget.Toast.makeText(this, "Contrato encontrado! Agora ative a proteção.", android.widget.Toast.LENGTH_LONG).show(); mostrar(); atualizar(); return }
        }
        lerQr()
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
        passos.forEach { (t, acao) -> botao(lista, t, acao) }
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
                tv.text = "Este aplicativo precisa ser ativado pela loja. Toque em “Ler QR code da loja” e aponte para o QR da instalação na tela do vendedor."
                lista.removeAllViews()
                botao(lista, "📷 Ler QR code da loja") { lerQr() }
                botao(lista, "Colar o link da instalação") { colarLink() }
                return
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
