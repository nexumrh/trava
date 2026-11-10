package br.com.nexumcell.trava

import android.app.Activity
import android.view.View
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
        findViewById<TextView>(R.id.abaFaturas).setOnClickListener { abrirAba(0) }
        findViewById<TextView>(R.id.abaContrato).setOnClickListener { abrirAba(1) }
        findViewById<TextView>(R.id.abaSuporte).setOnClickListener { abrirAba(2) }
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
        val t = (u.getQueryParameter("t") ?: u.getQueryParameter("instalar")).orEmpty().trim()
        val k = u.getQueryParameter("k").orEmpty().trim(); val p = u.getQueryParameter("p").orEmpty().trim()
        if (t.length < 10 || k.isEmpty() || p.isEmpty()) return false
        // instalado pelo computador (ADB): já é "dono do aparelho"; só falta o contrato (uma única vez)
        if (Policy.isOwner(this)) {
            if (st.configurado) return false
            st.token = t; st.apiKey = k; st.projectId = p
            Setup.concluir(applicationContext, null)
            return true
        }
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
    /** permissões já pedidas nesta abertura (se o cliente negar, fica o botão para tentar de novo) */
    private val pedidos = mutableSetOf<String>()
    private var ultimoPedido = 0L
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

    /** Logo depois de achar o contrato, já abre o pedido de administrador (1º passo). */
    private fun pedirAdmin() {
        if (Policy.adminAtivo(this)) return
        try {
            startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, Policy.admin(this))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Necessário para o crediário NexumCell: bloqueia o aparelho em caso de atraso. Ao quitar, a proteção é removida."))
        } catch (e: Exception) { }
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
        // Realme, Oppo, Xiaomi, Vivo, Huawei: precisa liberar "iniciar automaticamente" senão o sistema mata o app
        if (Autostart.precisa() && !st.autostartPedido) passos.add("5. Permitir iniciar sozinho" to { st.autostartPedido = true; Autostart.abrir(this) })
        val faltaPerm = listOfNotNull(
            android.Manifest.permission.READ_PHONE_STATE,
            if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.POST_NOTIFICATIONS else null
        ).filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (faltaPerm.isNotEmpty()) passos.add("4. Permitir notificações e telefone" to { requestPermissions(faltaPerm.toTypedArray(), 7) })
        if (passos.isEmpty()) {
            if (st.linkAtivadoEm == 0L) { st.linkAtivadoEm = System.currentTimeMillis(); st.adminRemovidoEm = 0L; Setup.concluir(applicationContext, null) }
            return false
        }
        val desat = st.linkAtivadoEm > 0 && !Policy.adminAtivo(this)
        sit.text = if (desat) "Proteção desativada" else "Ativar o NexumCell"
        tv.text = if (desat) "A proteção do aparelho foi desativada e o aparelho está bloqueado. Toque nos botões abaixo para ativar de novo."
            else "Instalação pelo link da loja. Toque em cada botão abaixo, na ordem, e aceite a permissão. Depois volte para este app."
        lista.removeAllViews()
        passos.forEach { (t, acao) -> botao(lista, t, acao) }
        // pede tudo em sequência, sozinho: ao voltar de cada tela de permissão, abre a próxima
        passos.firstOrNull { it.first !in pedidos }?.let { (t, acao) ->
            lista.postDelayed({
                // só com o app na frente (não abre por cima de outra tela de permissão)
                if (!isFinishing && hasWindowFocus() && t !in pedidos && System.currentTimeMillis() - ultimoPedido > 1500) {
                    pedidos.add(t); ultimoPedido = System.currentTimeMillis()
                    try { acao() } catch (e: Exception) { }
                }
            }, 700)
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
        findViewById<View>(R.id.abas).visibility = View.GONE; abrirAba(0, false)
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
        findViewById<View>(R.id.abas).visibility = View.VISIBLE
        preencherAbas(d)
        abrirAba(abaAtual, false)
    }

    /* ---------- abas: Faturas · Contrato · Suporte ---------- */
    private var abaAtual = 0
    private fun abrirAba(i: Int, guardar: Boolean = true) {
        if (guardar) abaAtual = i
        val ids = listOf(R.id.secFaturas, R.id.secContrato, R.id.secSuporte)
        val abas = listOf(R.id.abaFaturas, R.id.abaContrato, R.id.abaSuporte)
        ids.forEachIndexed { k, id -> findViewById<View>(id).visibility = if (k == i) View.VISIBLE else View.GONE }
        abas.forEachIndexed { k, id -> findViewById<TextView>(id).apply {
            setTextColor(android.graphics.Color.parseColor(if (k == i) "#0A1733" else "#696784"))
            setTypeface(null, if (k == i) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            setBackgroundColor(android.graphics.Color.parseColor(if (k == i) "#EEF3FF" else "#FFFFFF"))
        } }
    }

    private fun portal(d: org.json.JSONObject) = d.optString("portal").ifEmpty { "https://nexumrh.github.io/cliente/" }.let { if (it.endsWith("/") || it.contains("?") || it.endsWith(".html")) it else "$it/" }
    private fun abrirUrl(u: String) { try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { android.widget.Toast.makeText(this, "Não foi possível abrir", android.widget.Toast.LENGTH_SHORT).show() } }
    private fun whats(tel: String, msg: String) { val n = tel.filter { it.isDigit() }; if (n.length < 10) return; abrirUrl("https://wa.me/${if (n.startsWith("55")) n else "55$n"}?text=" + Uri.encode(msg)) }
    private fun texto(t: String, tam: Float = 15f, cor: String = "#1B1A2E", negrito: Boolean = false) = TextView(this).apply {
        text = t; textSize = tam; setTextColor(android.graphics.Color.parseColor(cor)); if (negrito) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, 8, 0, 8); setLineSpacing(4f, 1f)
    }
    private fun botaoAba(alvo: LinearLayout, t: String, principal: Boolean = false, acao: () -> Unit) {
        alvo.addView(Button(this).apply {
            text = t; isAllCaps = false; setTextColor(android.graphics.Color.parseColor(if (principal) "#FFFFFF" else "#0A1733"))
            if (principal) setBackgroundColor(android.graphics.Color.parseColor("#1D4ED8")) else setBackgroundResource(R.drawable.btn_outline_claro)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 18 }
            setOnClickListener { acao() }
        })
    }

    private fun preencherAbas(d: org.json.JSONObject) {
        val st = Store(this)
        // Contrato
        val c = findViewById<LinearLayout>(R.id.secContrato); c.removeAllViews()
        c.addView(texto("Meu contrato", 17f, negrito = true))
        c.addView(texto("Contrato nº ${d.optLong("vendaNumero")} · ${d.optString("marca")} ${d.optString("modelo")}\nLoja: ${d.optString("lojaNome")}\n${d.optJSONArray("parcelas")?.length() ?: 0} parcelas", 15f, "#3B3A52"))
        c.addView(texto("O contrato foi assinado na compra. Você pode baixar uma cópia em PDF para guardar.", 14f, "#696784"))
        botaoAba(c, "📄 Baixar contrato assinado (PDF)", true) { abrirUrl(portal(d) + "?contrato=" + Uri.encode(st.token)) }
        botaoAba(c, "Ver recibos e quitação no portal") { abrirUrl(portal(d)) }
        // Suporte
        val s2 = findViewById<LinearLayout>(R.id.secSuporte); s2.removeAllViews()
        s2.addView(texto("Suporte", 17f, negrito = true))
        s2.addView(texto("Fale com a gente. Tenha em mãos o número do contrato: ${d.optLong("vendaNumero")}.", 14f, "#696784"))
        val telLoja = d.optString("lojaTelefone")
        val telSup = d.optString("telefoneSuporte")
        val msg = "Olá! Sou cliente NexumCell, contrato nº ${d.optLong("vendaNumero")} (${d.optString("marca")} ${d.optString("modelo")}). Preciso de ajuda."
        botaoAba(s2, "🤖 Tirar dúvidas com a assistente (IA)", true) { startActivity(Intent(this, ChatActivity::class.java)) }
        if (telLoja.filter { it.isDigit() }.length >= 10) {
            botaoAba(s2, "💬 Falar com a loja no WhatsApp") { whats(telLoja, msg) }
            botaoAba(s2, "📞 Ligar para a loja") { abrirUrl("tel:" + telLoja.filter { it.isDigit() }) }
        }
        if (telSup.filter { it.isDigit() }.length >= 10 && telSup.filter { it.isDigit() } != telLoja.filter { it.isDigit() })
            botaoAba(s2, "🛟 Suporte NexumCell (WhatsApp)") { whats(telSup, msg) }
        botaoAba(s2, "❓ Tirar dúvidas no portal do cliente") { abrirUrl(portal(d)) }
        botaoAba(s2, "🚨 Meu celular foi roubado ou perdido") { abrirUrl(portal(d)) }
        s2.addView(texto("Perguntas frequentes", 16f, negrito = true).apply { setPadding(0, 36, 0, 8) })
        listOf(
            "Paguei e o celular continua bloqueado. O que faço?" to "A liberação é automática em poucos minutos. Toque em Atualizar na aba Faturas. Se continuar, fale com a loja.",
            "Posso pagar antes do vencimento?" to "Sim. Pagando antes você ganha desconto e, quitando tudo, economiza os juros das parcelas que ainda não venceram.",
            "Como mudo o dia de vencimento?" to "Pelo portal do cliente, em Mudar dia do vencimento (uma vez por contrato).",
            "Quando o app sai do celular?" to "Ao quitar o contrato, a proteção é removida automaticamente e você pode desinstalar."
        ).forEach { (p, r) -> s2.addView(texto("• $p", 14f, "#1B1A2E", true)); s2.addView(texto(r, 14f, "#3B3A52")) }
    }
}
