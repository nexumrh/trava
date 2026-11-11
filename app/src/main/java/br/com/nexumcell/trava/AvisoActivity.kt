package br.com.nexumcell.trava

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Aviso em tela cheia ANTES do bloqueio: uma vez por dia enquanto houver parcela em atraso (etapas 1 e 2),
 * com a contagem de dias que faltam e o botão de pagar. O cliente pode fechar — o aparelho ainda não está travado.
 */
class AvisoActivity : Activity() {
    companion object {
        fun talvezAbrir(ctx: Context, r: Resultado, d: JSONObject) {
            if (r.travar || r.final || r.nivel !in 1..2) return
            val st = Store(ctx)
            val hoje = java.time.LocalDate.now().toString() + "-" + r.nivel
            if (st.avisoTelaDia == hoje) return
            val (_, parcial, total) = Decisao.etapas(d)
            val maior = Faturas.lista(d).filter { !it.pago }.maxOfOrNull { it.diasAtraso } ?: 0L
            val dias = (if (r.nivel == 2) total else parcial) - maior
            try {
                ctx.startActivity(Intent(ctx, AvisoActivity::class.java)
                    .putExtra("nivel", r.nivel).putExtra("dias", dias)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                st.avisoTelaDia = hoje
            } catch (e: Exception) { }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        val nivel = intent.getIntExtra("nivel", 1)
        val dias = intent.getLongExtra("dias", 0L)
        val dp = resources.displayMetrics.density
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A1733")); setPadding((28 * dp).toInt(), 0, (28 * dp).toInt(), 0)
        }
        raiz.addView(TextView(this).apply {
            text = if (dias > 0) dias.toString() else "!"; textSize = 44f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#FFD27A"))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#3A2F1A")) }
            layoutParams = LinearLayout.LayoutParams((110 * dp).toInt(), (110 * dp).toInt())
        })
        raiz.addView(TextView(this).apply {
            text = I18n.t(if (dias > 0) (if (nivel == 2) "dia(s) para o bloqueio total" else "dia(s) para limitar as funções") else "Parcela em atraso")
            textSize = 17f; setTextColor(Color.parseColor("#FFD27A")); gravity = Gravity.CENTER
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        raiz.addView(TextView(this).apply {
            text = I18n.t(if (nivel == 2) "Funções limitadas por atraso" else "Parcela em atraso")
            textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, (22 * dp).toInt(), 0, (8 * dp).toInt())
        })
        raiz.addView(TextView(this).apply {
            text = I18n.aviso(nivel, dias).replace(" Toque para pagar com PIX.", "").replace(" Toque para pagar con PIX.", "")
            textSize = 18f; setTextColor(Color.parseColor("#C9D3EA")); gravity = Gravity.CENTER; setLineSpacing(6f, 1f)
        })
        raiz.addView(Button(this).apply {
            text = I18n.t("Pagar agora com PIX"); isAllCaps = false; textSize = 17f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.parseColor("#1A5CFF")) }
            layoutParams = LinearLayout.LayoutParams(-1, (54 * dp).toInt()).apply { topMargin = (32 * dp).toInt() }
            setOnClickListener {
                startActivity(Intent(this@AvisoActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish()
            }
        })
        raiz.addView(Button(this).apply {
            text = I18n.t("Agora não"); isAllCaps = false; textSize = 16f; setTextColor(Color.parseColor("#C9D3EA"))
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.TRANSPARENT); setStroke((1 * dp).toInt(), Color.parseColor("#3B4A6B")) }
            layoutParams = LinearLayout.LayoutParams(-1, (50 * dp).toInt()).apply { topMargin = (12 * dp).toInt() }
            setOnClickListener { finish() }
        })
        setContentView(raiz)
    }
}
