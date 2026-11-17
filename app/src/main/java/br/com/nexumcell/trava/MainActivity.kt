package br.com.nexumcell.trava

import android.app.Activity
import android.view.View
import android.view.Gravity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Minutos preso em "Configurando…" até oferecer as opções de recuperação. */
private const val MIN_INSTALACAO_TRAVADA = 15L

/** App do cliente: situação do contrato e "Minhas faturas" com pagamento por PIX. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnAtualizar).setOnClickListener { atualizar() }
        findViewById<TextView>(R.id.abaFaturas).setOnClickListener { abrirAba(0) }
        findViewById<TextView>(R.id.abaParcelas).setOnClickListener { abrirAba(3) }
        findViewById<TextView>(R.id.abaContrato).setOnClickListener { abrirAba(1) }
        findViewById<TextView>(R.id.abaSuporte).setOnClickListener { abrirAba(2) }
        findViewById<View>(R.id.btnSino).setOnClickListener { abrirAba(0); atualizar() }
        findViewById<View>(R.id.tvSaibaMais).setOnClickListener { abrirAba(1) }
        findViewById<View>(R.id.qaApps).setOnClickListener { appsPermitidos() }
        findViewById<View>(R.id.qaContato).setOnClickListener { abrirAba(2) }
        findViewById<View>(R.id.qaEmergencia).setOnClickListener { try { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:190"))) } catch (e: Exception) { } }
        findViewById<View>(R.id.qaConfig).setOnClickListener { try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (e: Exception) { } }
        lerLink(intent)
        botaoFlutuante()
    }

    private fun dpx(x: Int) = (x * resources.displayMetrics.density).toInt()

    /** Uma linha do diagnóstico, com ✓/✗ e dica quando falta. */
    private fun linhaDiag(lista: LinearLayout, item: Policy.DiagItem) {
        lista.addView(TextView(this).apply {
            text = (if (item.ok) "✓  " else "✗  ") + item.titulo + (if (!item.ok) "\n      " + item.dica else "")
            textSize = 14f
            setTextColor(Color.parseColor(if (item.ok) "#0E8A5F" else if (item.critico) "#C62828" else "#B06A00"))
            setPadding(0, dpx(6), 0, dpx(6))
        })
    }

    /** Tela "Status da instalação" (item 5): checklist das proteções, acessível em qualquer estado. */
    private fun telaDiagnostico() {
        val diag = Policy.diagnostico(this)
        val faltam = diag.count { it.critico && !it.ok }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpx(22), dpx(8), dpx(22), dpx(8)) }
        diag.forEach { linhaDiag(box, it) }
        android.app.AlertDialog.Builder(this)
            .setTitle(if (faltam == 0) "Instalação protegida ✓" else "Status da instalação")
            .setView(box)
            .setPositiveButton("Fechar", null)
            .setNeutralButton("Atualizar") { _, _ -> atualizar() }
            .show()
    }

    /** Portal flutuante de suporte: botão sempre visível que abre o Suporte NexumCell dentro do app. */
    private fun botaoFlutuante() {
        val fab = TextView(this).apply {
            text = "💬  Suporte"; textSize = 15f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(dpx(20), dpx(14), dpx(20), dpx(14)); isClickable = true
            background = GradientDrawable().apply { cornerRadius = dpx(28).toFloat(); setColor(Color.parseColor("#1D4ED8")) }
            elevation = dpx(8).toFloat()
            setOnClickListener { startActivity(Intent(this@MainActivity, SuporteActivity::class.java)) }
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.END; marginEnd = dpx(16); bottomMargin = dpx(88)
        }
        addContentView(
            FrameLayout(this).apply { addView(fab, lp) },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent); lerLink(intent)
    }

    /** Link de ativação aberto no navegador: nexumcell://ativar?t=TOKEN&k=APIKEY&p=PROJETO */
    private fun lerLink(i: Intent?) {
        val u = i?.data ?: return
        if (u.scheme != "nexumcell") return
        when (u.host) {
            "ativar" -> aplicarLink(u)
            // Remoção por link (quando a depuração ainda está ligada): código mestre do dono.
            "remover" -> if (Remocao.confereMestre(u.getQueryParameter("m").orEmpty())) Remocao.executar(applicationContext)
        }
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
            st.instaladoPc = u.getQueryParameter("pc").orEmpty().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(40)
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

    /** Código de remoção do dono (10 dígitos) digitado no aparelho: solta a trava e desinstala.
     *  Funciona travado, sem internet e sem contrato — não precisa de cabo nem de desenvolvedor. */
    private fun pedirCodigoMestre() {
        val ed = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(10))
            hint = "0000000000"; textSize = 22f; gravity = Gravity.CENTER
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Remover a proteção")
            .setMessage("Digite o código de remoção que a loja/NexumCell passar. Isso tira a trava e desinstala o app. Use só com autorização da loja.")
            .setView(ed)
            .setPositiveButton("Remover") { _, _ ->
                if (Remocao.confereMestre(ed.text.toString())) {
                    android.widget.Toast.makeText(this, "Removendo a proteção…", android.widget.Toast.LENGTH_LONG).show()
                    Thread { try { Remocao.executar(applicationContext) } catch (e: Exception) { } }.start()
                } else {
                    android.widget.Toast.makeText(this, "Código inválido. Confirme com a loja.", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null).show()
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
        findViewById<View>(R.id.heroPay).visibility = View.GONE
        findViewById<View>(R.id.grpStatus).visibility = View.VISIBLE
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
                botao(lista, "Remover a trava (código do dono)") { pedirCodigoMestre() }
                return
            }
            if (passosLink(sit, tv, lista)) return
        }
        val d = st.cache()
        if (d == null) {
            sit.text = "Configurando…"
            if (st.configDesde == 0L) st.configDesde = System.currentTimeMillis()
            val min = (System.currentTimeMillis() - st.configDesde) / 60000L
            val travada = min >= MIN_INSTALACAO_TRAVADA
            val diag = Policy.diagnostico(this)
            val faltam = diag.count { it.critico && !it.ok }
            tv.text = when {
                travada -> "A instalação parece travada (há $min min sem concluir). Use as opções abaixo ou chame o suporte da loja."
                faltam > 0 -> "A instalação ainda não está completa. Veja o que falta abaixo e conecte o aparelho à internet."
                else -> "Conecte o aparelho à internet para carregar o seu contrato."
            }
            lista.removeAllViews()
            diag.forEach { linhaDiag(lista, it) }
            if (travada) {
                botao(lista, "Tentar de novo") { atualizar() }
                botao(lista, "Remover e reinstalar") { pedirCodigoMestre() }
                botao(lista, "Falar com o suporte") { startActivity(Intent(this, SuporteActivity::class.java)) }
            }
            return
        }
        st.configDesde = 0L
        val r = Decisao.avaliar(d, System.currentTimeMillis(), st.ultimoSyncOk, st.liberadoLocalAte)
        val fat = Faturas.lista(d)
        val abertas = fat.filter { !it.pago }
        val atrasadas = abertas.filter { it.diasAtraso > 0 }
        sit.text = if (r.travar) "Dispositivo bloqueado" else "Dispositivo desbloqueado"
        val prox = abertas.minByOrNull { it.venc }
        tv.text = buildString {
            append(when {
                r.travar -> "Regularize o pagamento para liberar. A liberação é automática.\n"
                atrasadas.isNotEmpty() -> "Você tem fatura em atraso: pague para não ser bloqueado.\n"
                else -> "Tudo está atualizado. Use este aplicativo para acompanhar seus pagamentos e o seu financiamento.\n"
            })
            append("Contrato nº ${d.optLong("vendaNumero")} · ${d.optString("marca")} ${d.optString("modelo")}\n")
            append("${fat.count { it.pago }} de ${fat.size} parcelas pagas")
            if (prox != null && atrasadas.isEmpty()) append("\nPróximo vencimento: ${prox.venc.format(Faturas.fmt)} · ${Faturas.brl.format(prox.valor)}")
            if (atrasadas.isNotEmpty()) append("\nTotal em atraso: ${Faturas.brl.format(atrasadas.sumOf { it.aPagar })} (com multa e juros)")
            if ((d.optDouble("descontoPct", 0.0)) > 0) append("\nPague antes do vencimento e ganhe ${d.optDouble("descontoPct").toInt()}% de desconto.")
        }
        findViewById<View>(R.id.grpStatus).visibility = View.GONE
        val hero = findViewById<LinearLayout>(R.id.heroPay); hero.visibility = View.VISIBLE
        desenharHero(hero, d, fat, r.travar, prox, atrasadas)
        desenharCalendario(findViewById(R.id.calParcelas), fat)
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
        val offline = d.optInt("maxDiasOffline", 0)
        findViewById<TextView>(R.id.tvDica).text = if (offline > 0)
            "Se ficar mais de $offline dias sem internet, o aparelho trava sozinho. Reconecte-se ou fale com a loja para recuperar o acesso."
            else "Se ficar muito tempo desconectado, o aparelho trava sozinho. Reconecte-se ou fale com a loja para recuperar o acesso."
        preencherAbas(d)
        abrirAba(abaAtual, false)
    }

    /* ---------- abas: Tela inicial (0) · Parcelas (3) · Contrato (1) · Suporte (2) ---------- */
    private var abaAtual = 0
    private fun abrirAba(i: Int, guardar: Boolean = true) {
        if (guardar) abaAtual = i
        val comNav = findViewById<View>(R.id.abas).visibility == View.VISIBLE
        // Tela inicial: cartão, visão geral e atalhos. Parcelas: só calendário + lista.
        findViewById<View>(R.id.blocoInicio).visibility = if (i == 0 || !comNav) View.VISIBLE else View.GONE
        findViewById<View>(R.id.blocoParcelas).visibility = if (i == 3 || !comNav) View.VISIBLE else View.GONE
        val sec = when (i) { 1 -> R.id.secContrato; 2 -> R.id.secSuporte; else -> R.id.secFaturas }
        listOf(R.id.secFaturas, R.id.secContrato, R.id.secSuporte).forEach { findViewById<View>(it).visibility = if (it == sec) View.VISIBLE else View.GONE }
        if (guardar) (findViewById<View>(R.id.blocoInicio).parent.parent as? android.widget.ScrollView)?.scrollTo(0, 0)
        val abas = listOf(R.id.abaFaturas to 0, R.id.abaParcelas to 3, R.id.abaContrato to 1, R.id.abaSuporte to 2)
        abas.forEach { (id, k) -> findViewById<TextView>(id).apply {
            setTextColor(android.graphics.Color.parseColor(if (k == i) "#FFFFFF" else "#9FB0D0"))
            setTypeface(null, if (k == i) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            if (k == i) setBackgroundResource(R.drawable.nav_ativo) else setBackgroundColor(android.graphics.Color.TRANSPARENT)
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

    /* ---------- Acesso rápido ---------- */
    private fun appsPermitidos() {
        val pm = packageManager
        val nomes = listOf(packageName, "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer", "com.motorola.dialer")
            .mapNotNull { try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (e: Exception) { null } }.distinct()
        android.app.AlertDialog.Builder(this).setTitle("Aplicativos permitidos")
            .setMessage("Com o aparelho em dia, você usa todos os aplicativos normalmente.\n\nSe o aparelho for bloqueado por atraso, continuam funcionando:\n• " +
                (nomes + "Chamadas de emergência (190, 192, 193)").joinToString("\n• ") + "\n\nPelo app NexumCell você paga por PIX e o aparelho libera sozinho.")
            .setPositiveButton("Entendi", null).show()
    }

    /* ---------- Device Tag: identificação do contrato (sempre 8 letras, começa com N) ---------- */
    private fun deviceTag(d: org.json.JSONObject): String {
        val t = d.optString("deviceTag").uppercase().filter { it in 'A'..'Z' }
        if (t.isNotEmpty()) return t.take(8)
        // nº do contrato embaralhado (sem repetir) em 7 letras: N + 7 = 8 letras
        val mod = 8_031_810_176L // 26^7
        var x = (d.optLong("vendaNumero").coerceAtLeast(0) * 1_103_515_245L + 12_345L).mod(mod)
        val sb = StringBuilder()
        repeat(7) { sb.append('A' + (x % 26).toInt()); x /= 26 }
        return "N" + sb.reverse()
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /* ---------- card estilo PayJoy ---------- */
    private fun txtH(t: CharSequence, size: Float, color: String, bold: Boolean = false, grav: Int = android.view.Gravity.CENTER, topM: Int = 0): TextView = TextView(this).apply {
        text = t; textSize = size; setTextColor(android.graphics.Color.parseColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = grav
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = topM }
    }
    private fun barraProg(pct: Int): View {
        val p = pct.coerceIn(0, 100)
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)).apply { topMargin = dp(6) }
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(android.graphics.Color.parseColor("#E7EBF3")) }
        }
        track.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p.toFloat())
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(android.graphics.Color.parseColor("#12875A")) }
        })
        track.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - p).toFloat()) })
        return track
    }
    private fun desenharHero(hero: LinearLayout, d: org.json.JSONObject, fat: List<Fatura>, travar: Boolean, prox: Fatura?, atrasadas: List<Fatura>) {
        hero.removeAllViews()
        val pagas = fat.count { it.pago }
        val pct = if (fat.isNotEmpty()) pagas * 100 / fat.size else 0
        hero.addView(txtH(if (travar) "● Dispositivo bloqueado" else "● Dispositivo desbloqueado", 13f, if (travar) "#C2410C" else "#12875A", true))
        hero.addView(txtH("Tempo restante", 12f, "#8893A8", false, topM = 10))
        val titulo = when {
            prox == null -> "Tudo em dia"
            atrasadas.isNotEmpty() -> "Em atraso há ${atrasadas.maxOf { it.diasAtraso }} dia(s)"
            prox.diasParaVencer <= 0L -> "Vence hoje"
            else -> "${prox.diasParaVencer} dia(s)"
        }
        hero.addView(txtH(titulo, 30f, "#0A1733", true))
        if (prox != null) {
            val valor = if (atrasadas.isNotEmpty()) atrasadas.sumOf { it.aPagar } else (if (prox.aPagar > 0.0) prox.aPagar else prox.valor)
            hero.addView(txtH("Pagamento de ${Faturas.brl.format(valor)}", 16f, "#1B2130", true, topM = 10))
            hero.addView(txtH("vence em ${prox.venc.format(Faturas.fmt)}", 14f, "#8893A8"))
            hero.addView(Button(this).apply {
                text = "Pagar com PIX"; isAllCaps = false; textSize = 16f
                setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD)
                setBackgroundResource(R.drawable.btn_verde)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(14) }
                setOnClickListener { Faturas.mostrarPix(this@MainActivity, d, valor, "Parcela ${prox.n} do contrato nº ${d.optLong("vendaNumero")}", "NXC${d.optLong("vendaNumero")}P${prox.n}", prox.n) }
            })
        } else {
            hero.addView(txtH("Você não tem parcelas em aberto.", 15f, "#1B2130", false, topM = 10))
        }
        hero.addView(txtH("Progresso do pagamento: $pct%  ($pagas de ${fat.size})", 13f, "#1B2130", false, android.view.Gravity.START, dp(16)))
        hero.addView(barraProg(pct))
        hero.addView(TextView(this).apply {
            text = "Ver detalhes da conta  ›"; textSize = 14f; setTextColor(android.graphics.Color.parseColor("#1D4ED8")); setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
            setOnClickListener { abrirUrl(portal(d)) }
        })
        hero.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(14); bottomMargin = dp(10) }
            setBackgroundColor(android.graphics.Color.parseColor("#E7EBF3"))
        })
        hero.addView(txtH("${d.optString("marca")} ${d.optString("modelo")} · Contrato nº ${d.optLong("vendaNumero")}", 12f, "#8893A8"))
        hero.addView(txtH("Device Tag: ${deviceTag(d)}", 13f, "#0A1733", true, topM = 2))
    }

    /* ---------- calendário das parcelas ---------- */
    private var calMes: java.time.YearMonth? = null
    private fun celulaCal(txt: String, textColor: String, bgCor: String?, bold: Boolean): TextView = TextView(this).apply {
        text = txt; gravity = android.view.Gravity.CENTER; textSize = 13f
        setTextColor(android.graphics.Color.parseColor(if (bgCor != null) "#FFFFFF" else textColor))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        layoutParams = android.widget.GridLayout.LayoutParams().apply {
            width = 0; height = dp(38)
            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        if (bgCor != null) background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(android.graphics.Color.parseColor(bgCor)) }
    }
    private fun desenharCalendario(cont: LinearLayout, fat: List<Fatura>) {
        cont.removeAllViews()
        if (fat.isEmpty()) return
        val prox = fat.filter { !it.pago }.minByOrNull { it.venc }
        val mes = calMes ?: java.time.YearMonth.from(prox?.venc ?: fat.first().venc)
        calMes = mes
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val mesNome = mes.month.getDisplayName(java.time.format.TextStyle.FULL, Locale("pt", "BR")).replaceFirstChar { it.uppercase() } + " " + mes.year
        fun nav(t: String, delta: Long) = TextView(this).apply {
            text = t; textSize = 22f; setTextColor(android.graphics.Color.parseColor("#1D4ED8")); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(2), dp(16), dp(2)); setOnClickListener { calMes = mes.plusMonths(delta); desenharCalendario(cont, fat) }
        }
        header.addView(nav("‹", -1))
        header.addView(TextView(this).apply { text = mesNome; textSize = 15f; setTextColor(android.graphics.Color.parseColor("#0A1733")); setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        header.addView(nav("›", 1))
        cont.addView(header)
        val grid = android.widget.GridLayout(this).apply { columnCount = 7; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) } }
        listOf("D", "S", "T", "Q", "Q", "S", "S").forEach { grid.addView(celulaCal(it, "#8893A8", null, false)) }
        val offset = mes.atDay(1).dayOfWeek.value % 7
        for (i in 0 until offset) grid.addView(celulaCal("", "#000000", null, false))
        val porDia = fat.filter { java.time.YearMonth.from(it.venc) == mes }.associateBy { it.venc.dayOfMonth }
        for (dia in 1..mes.lengthOfMonth()) {
            val f = porDia[dia]
            val cor = when { f == null -> null; f.pago -> "#12875A"; f.diasAtraso > 0L -> "#C2410C"; else -> "#1D4ED8" }
            grid.addView(celulaCal(dia.toString(), "#1B2130", cor, f != null))
        }
        cont.addView(grid)
        val leg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) } }
        fun legItem(cor: String, t: String) = leg.addView(TextView(this).apply { text = "● $t"; textSize = 11f; setTextColor(android.graphics.Color.parseColor(cor)); setPadding(0, 0, dp(12), 0) })
        legItem("#12875A", "Paga"); legItem("#1D4ED8", "A vencer"); legItem("#C2410C", "Em atraso")
        cont.addView(leg)
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
        // O atendimento acontece DENTRO do app, com o Suporte NexumCell (não abre WhatsApp).
        botaoAba(s2, "Falar com o Suporte NexumCell", true) { startActivity(Intent(this, SuporteActivity::class.java)) }
        botaoAba(s2, "❓ Tirar dúvidas no portal do cliente") { abrirUrl(portal(d)) }
        botaoAba(s2, "🚨 Meu celular foi roubado ou perdido") { abrirUrl(portal(d)) }
        s2.addView(texto("Perguntas frequentes", 16f, negrito = true).apply { setPadding(0, 36, 0, 8) })
        listOf(
            "Paguei e o celular continua bloqueado. O que faço?" to "A liberação é automática em poucos minutos. Toque em Atualizar na aba Parcelas. Se continuar, fale com o Suporte NexumCell.",
            "Posso pagar antes do vencimento?" to "Sim. Pagando antes você ganha desconto e, quitando tudo, economiza os juros das parcelas que ainda não venceram.",
            "Como mudo o dia de vencimento?" to "Pelo portal do cliente, em Mudar dia do vencimento (uma vez por contrato).",
            "Quando o app sai do celular?" to "Ao quitar o contrato, a proteção é removida automaticamente e você pode desinstalar."
        ).forEach { (p, r) -> s2.addView(texto("• $p", 14f, "#1B1A2E", true)); s2.addView(texto(r, 14f, "#3B3A52")) }
    }
}
