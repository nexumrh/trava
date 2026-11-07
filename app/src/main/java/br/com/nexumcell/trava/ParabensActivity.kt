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

/** "Parabéns, suas faturas estão em dia" — aberta quando um pagamento é confirmado e o aparelho é liberado. */
class ParabensActivity : Activity() {
    companion object {
        fun abrir(ctx: Context, quitado: Boolean) {
            try {
                ctx.startActivity(Intent(ctx, ParabensActivity::class.java)
                    .putExtra("quitado", quitado)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (e: Exception) { }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val quitado = intent.getBooleanExtra("quitado", false)
        val dp = resources.displayMetrics.density
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0A1733")); setPadding((28 * dp).toInt(), 0, (28 * dp).toInt(), 0)
        }
        raiz.addView(TextView(this).apply {
            text = "✓"; textSize = 46f; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#7FE0B5"))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#1A3A35")) }
            layoutParams = LinearLayout.LayoutParams((110 * dp).toInt(), (110 * dp).toInt())
        })
        raiz.addView(TextView(this).apply {
            text = "Parabéns!"; textSize = 30f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, (24 * dp).toInt(), 0, (8 * dp).toInt())
        })
        raiz.addView(TextView(this).apply {
            text = if (quitado) "Você quitou o seu contrato. O aparelho é todo seu e a trava foi removida."
                   else "Suas faturas estão em dia.\nContinue pagando e evite atrasos."
            textSize = 19f; setTextColor(Color.parseColor("#C9D3EA")); gravity = Gravity.CENTER; setLineSpacing(6f, 1f)
        })
        raiz.addView(Button(this).apply {
            text = "Continuar"; isAllCaps = false; textSize = 17f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = 12 * dp; setColor(Color.parseColor("#1A5CFF")) }
            layoutParams = LinearLayout.LayoutParams(-1, (54 * dp).toInt()).apply { topMargin = (36 * dp).toInt() }
            setOnClickListener { finish() }
        })
        setContentView(raiz)
    }
}
