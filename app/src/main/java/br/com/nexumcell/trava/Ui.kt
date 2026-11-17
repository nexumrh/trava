package br.com.nexumcell.trava

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Design system do app NexumCell: cores, tipografia e componentes montados em código. */
object Ui {
    // cores
    const val NAVY = "#0A1733"
    const val AZUL = "#1D4ED8"
    const val AZUL_CLARO = "#EAF0FF"
    const val VERDE = "#0E9F6E"
    const val VERDE_CLARO = "#E7F7F0"
    const val VERMELHO = "#DC2626"
    const val VERMELHO_CLARO = "#FDECEC"
    const val LARANJA = "#B45309"
    const val LARANJA_CLARO = "#FEF3E2"
    const val TEXTO = "#0F172A"
    const val TEXTO2 = "#475569"
    const val MUDO = "#94A3B8"
    const val BORDA = "#E5E9F2"
    const val FUNDO = "#F3F5FA"

    val MEDIO: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val NEGRITO: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun c(hex: String) = Color.parseColor(hex)
    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, v: Int) = v * ctx.resources.displayMetrics.density

    fun forma(ctx: Context, cor: String, raio: Int, borda: String? = null): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpf(ctx, raio); setColor(c(cor)); if (borda != null) setStroke(dp(ctx, 1), c(borda))
    }
    /** Fundo com efeito de toque (ripple). */
    fun toque(ctx: Context, cor: String, raio: Int, borda: String? = null, ripple: String = "#1A0A1733"): Drawable =
        RippleDrawable(ColorStateList.valueOf(c(ripple)), forma(ctx, cor, raio, borda), forma(ctx, "#FFFFFF", raio))

    fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT, peso: Float = 0f, topo: Int = 0, baixo: Int = 0) =
        LinearLayout.LayoutParams(w, h, peso).apply { topMargin = topo; bottomMargin = baixo }

    fun texto(ctx: Context, t: CharSequence, tam: Float, cor: String = TEXTO, tipo: Typeface? = null, grav: Int = Gravity.START) = TextView(ctx).apply {
        text = t; textSize = tam; setTextColor(c(cor)); gravity = grav; if (tipo != null) typeface = tipo
        includeFontPadding = false; setLineSpacing(dpf(ctx, 3), 1f)
    }

    fun icone(ctx: Context, res: Int, cor: String, tam: Int = 22): ImageView = ImageView(ctx).apply {
        setImageDrawable(ctx.getDrawable(res)?.mutate()?.apply { setTint(c(cor)) })
        layoutParams = LinearLayout.LayoutParams(dp(ctx, tam), dp(ctx, tam))
    }

    /** Ícone dentro de um círculo claro. */
    fun iconeCirculo(ctx: Context, res: Int, cor: String, fundo: String, tam: Int = 44): View = LinearLayout(ctx).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c(fundo)) }
        layoutParams = LinearLayout.LayoutParams(dp(ctx, tam), dp(ctx, tam))
        addView(icone(ctx, res, cor, tam / 2))
    }

    /** Etiqueta em pílula (status). */
    fun chip(ctx: Context, t: String, cor: String, fundo: String, ponto: Boolean = true) = TextView(ctx).apply {
        text = if (ponto) "●  $t" else t; textSize = 12f; setTextColor(c(cor)); typeface = MEDIO; includeFontPadding = false
        setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5)); background = forma(ctx, fundo, 20)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    /** Cartão branco padrão. */
    fun cartao(ctx: Context, pad: Int = 18, topo: Int = 14): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; background = forma(ctx, "#FFFFFF", 18, BORDA)
        setPadding(dp(ctx, pad), dp(ctx, pad), dp(ctx, pad), dp(ctx, pad))
        layoutParams = lp(topo = dp(ctx, topo)); elevation = dpf(ctx, 1)
    }

    fun botao(ctx: Context, t: String, cor: String = AZUL, texto: String = "#FFFFFF", contorno: Boolean = false, alt: Int = 52, acao: () -> Unit) = TextView(ctx).apply {
        text = t; textSize = 15f; setTextColor(c(texto)); typeface = MEDIO; gravity = Gravity.CENTER; isClickable = true
        background = if (contorno) toque(ctx, "#FFFFFF", 14, BORDA) else toque(ctx, cor, 14, ripple = "#33FFFFFF")
        layoutParams = lp(h = dp(ctx, alt), topo = dp(ctx, 12))
        setOnClickListener { acao() }
    }

    fun titulo(ctx: Context, t: String, topo: Int = 22) = texto(ctx, t, 17f, TEXTO, NEGRITO).apply { layoutParams = lp(topo = dp(ctx, topo)) }

    fun divisor(ctx: Context, topo: Int = 14, baixo: Int = 14) = View(ctx).apply {
        setBackgroundColor(c(BORDA)); layoutParams = lp(h = dp(ctx, 1), topo = dp(ctx, topo), baixo = dp(ctx, baixo))
    }

    /** Linha de lista: ícone, título/subtítulo e seta. */
    fun linhaAcao(ctx: Context, res: Int, t: String, sub: String? = null, corIcone: String = AZUL, fundoIcone: String = AZUL_CLARO, acao: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true
        setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12)); background = toque(ctx, "#FFFFFF", 12)
        addView(iconeCirculo(ctx, res, corIcone, fundoIcone, 40))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 14), 0, dp(ctx, 8), 0)
            addView(texto(ctx, t, 15f, TEXTO, MEDIO))
            if (sub != null) addView(texto(ctx, sub, 13f, TEXTO2).apply { layoutParams = lp(topo = dp(ctx, 3)) })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(texto(ctx, "›", 22f, MUDO))
        setOnClickListener { acao() }
    }

    /** Par rótulo / valor em coluna. */
    fun info(ctx: Context, rotulo: String, valor: String, alinhar: Int = Gravity.START) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = alinhar
        addView(texto(ctx, rotulo, 12f, MUDO, grav = alinhar))
        addView(texto(ctx, valor, 15f, TEXTO, MEDIO, alinhar).apply { layoutParams = lp(topo = dp(ctx, 4)) })
    }

    /** Linha rótulo à esquerda, valor à direita. */
    fun linhaDado(ctx: Context, rotulo: String, valor: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
        addView(texto(ctx, rotulo, 14f, TEXTO2), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(texto(ctx, valor, 14f, TEXTO, MEDIO, Gravity.END))
    }

    fun barra(ctx: Context, pct: Int, cor: String = VERDE): View {
        val p = pct.coerceIn(0, 100)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; background = forma(ctx, "#EDF1F7", 6)
            layoutParams = lp(h = dp(ctx, 8), topo = dp(ctx, 8))
            addView(View(ctx).apply { background = forma(ctx, cor, 6) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, p.toFloat()))
            addView(View(ctx), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, (100 - p).toFloat()))
        }
    }
}
