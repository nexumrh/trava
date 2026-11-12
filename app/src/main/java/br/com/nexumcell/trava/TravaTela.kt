package br.com.nexumcell.trava

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Tela de bloqueio desenhada POR CIMA de tudo (permissão "sobrepor a outros apps").
 * Usada quando o app não é Device Owner (instalação por link): aparelhos como Realme, Xiaomi e Oppo
 * não deixam o app abrir uma tela sozinho em segundo plano, mas a sobreposição aparece sempre.
 */
object TravaTela {
    private const val TAG = "NexumTravaTela"
    private val h = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null

    fun podeSobrepor(ctx: Context) = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun mostrar(ctx: Context) {
        val app = ctx.applicationContext
        if (!podeSobrepor(app)) return
        h.post {
            try {
                if (!Store(app).travado) { removerAgora(app); return@post }
                if (view != null) { preencher(app); return@post }
                val wm = app.getSystemService(WindowManager::class.java)
                val tipo = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, tipo,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.OPAQUE
                ).apply { gravity = Gravity.TOP or Gravity.START }
                val v = montar(app)
                wm.addView(v, lp); view = v
                preencher(app)
            } catch (e: Throwable) { Log.w(TAG, "mostrar: ${e.message}") }
        }
    }

    fun esconder(ctx: Context) { val app = ctx.applicationContext; h.post { removerAgora(app) } }

    private fun removerAgora(app: Context) {
        val v = view ?: return
        try { app.getSystemService(WindowManager::class.java).removeView(v) } catch (e: Throwable) { }
        view = null
    }

    private var tvMotivo: TextView? = null
    private var tvValor: TextView? = null

    private fun montar(ctx: Context): View {
        val dp = ctx.resources.displayMetrics.density
        fun px(x: Int) = (x * dp).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(56), px(24), px(32))
        }
        fun texto(t: String, tam: Float, cor: String, negrito: Boolean = false) = TextView(ctx).apply {
            text = I18n.t(t); textSize = tam; setTextColor(Color.parseColor(cor)); gravity = Gravity.CENTER
            if (negrito) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(6), 0, px(6))
        }
        fun botao(t: String, fundo: String, cor: String, acao: () -> Unit) = Button(ctx).apply {
            text = I18n.t(t); isAllCaps = false; setTextColor(Color.parseColor(cor)); setBackgroundColor(Color.parseColor(fundo))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(52)).apply { topMargin = px(12) }
            setOnClickListener { try { acao() } catch (e: Throwable) { } }
        }
        col.addView(texto("🔒", 54f, "#FFFFFF"))
        col.addView(texto("Aparelho bloqueado", 26f, "#FFFFFF", true))
        tvMotivo = texto("", 15f, "#C9D3EA"); col.addView(tvMotivo)
        tvValor = texto("", 18f, "#F2C94C", true); col.addView(tvValor)
        col.addView(texto("Pague para liberar. A liberação é automática em poucos minutos após o pagamento.", 14f, "#C9D3EA"))
        col.addView(botao("Pagar com PIX e ver opções", "#C9A227", "#0A1733") { abrirLock(ctx) })
        col.addView(botao("Copiar chave PIX", "#1E2C4F", "#FFFFFF") {
            val pix = Store(ctx).cache()?.optString("chavePix").orEmpty()
            if (pix.isNotEmpty()) {
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PIX", pix))
                Toast.makeText(ctx, "Chave PIX copiada: $pix", Toast.LENGTH_LONG).show()
            }
        })
        col.addView(botao("Já paguei — verificar", "#1E2C4F", "#FFFFFF") {
            Toast.makeText(ctx, I18n.t("Verificando…"), Toast.LENGTH_SHORT).show()
            Thread { Sync.executar(ctx) }.start()
        })
        col.addView(botao("💬 Falar com o Suporte NexumCell", "#1E2C4F", "#FFFFFF") {
            try { ctx.startActivity(android.content.Intent(ctx, SuporteActivity::class.java).putExtra("bloqueado", true).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        })
        col.addView(botao("Emergência (190 / 192 / 193)", "#7A1F1F", "#FFFFFF") { discar(ctx, "") })
        return ScrollView(ctx).apply {
            setBackgroundColor(Color.parseColor("#0A1733")); isFillViewport = true
            addView(col)
            // não deixa nada por baixo receber toques
            setOnTouchListener { _, _ -> false }
        }
    }

    private fun preencher(ctx: Context) {
        val st = Store(ctx); val d = st.cache()
        tvMotivo?.text = st.motivo
        val total = try { d?.let { Faturas.lista(it).filter { f -> !f.pago && f.diasAtraso > 0 }.sumOf { f -> f.aPagar } } ?: 0.0 } catch (e: Exception) { 0.0 }
        tvValor?.text = if (total > 0) "${I18n.t("Total em atraso")}: ${Faturas.brl.format(total)}" else ""
    }

    private fun abrirLock(ctx: Context) {
        try {
            ctx.startActivity(Intent(ctx, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            esconder(ctx)
        } catch (e: Throwable) { Log.w(TAG, "abrirLock: ${e.message}") }
    }

    private fun discar(ctx: Context, tel: String) {
        try {
            esconder(ctx)
            ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // volta a bloquear depois de 1 minuto (tempo para a ligação)
            h.postDelayed({ if (Store(ctx).travado) mostrar(ctx) }, 60_000)
        } catch (e: Throwable) { }
    }
}
