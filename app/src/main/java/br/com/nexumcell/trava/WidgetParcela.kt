package br.com.nexumcell.trava

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

/** Widget da tela inicial: valor e vencimento da próxima parcela, com botão para pagar. Atualiza a cada sincronização. */
class WidgetParcela : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { desenhar(ctx, mgr, ids) }

    companion object {
        fun atualizar(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, WidgetParcela::class.java))
                if (ids.isNotEmpty()) desenhar(ctx, mgr, ids)
            } catch (e: Exception) { }
        }

        private fun brl(v: Double) = "R$ " + String.format(Locale("pt", "BR"), "%,.2f", v)

        private fun desenhar(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_parcela)
            val d = Store(ctx).cache()
            if (d == null) {
                rv.setTextViewText(R.id.wgValor, "NexumCell"); rv.setTextViewText(R.id.wgVenc, I18n.t("Abra o app para conectar")); rv.setTextViewText(R.id.wgPagar, I18n.t("Abrir"))
            } else if (d.optBoolean("quitado") || d.optString("status") == "QUITADO") {
                rv.setTextViewText(R.id.wgTitulo, "NexumCell"); rv.setTextViewText(R.id.wgValor, I18n.t("Quitado"))
                rv.setTextViewText(R.id.wgVenc, I18n.t("Parabéns! O celular é todo seu.")); rv.setTextViewText(R.id.wgPagar, I18n.t("Abrir"))
            } else {
                val abertas = Faturas.lista(d).filter { !it.pago }
                val atrasadas = abertas.filter { it.diasAtraso > 0 }
                if (atrasadas.isNotEmpty()) {
                    rv.setTextViewText(R.id.wgTitulo, "NexumCell · " + I18n.t("em atraso"))
                    rv.setTextViewText(R.id.wgValor, brl(atrasadas.sumOf { it.aPagar }))
                    val maior = atrasadas.maxOf { it.diasAtraso }
                    rv.setTextViewText(R.id.wgVenc, "${atrasadas.size} " + I18n.t("parcela(s)") + " · $maior " + I18n.t("dia(s) de atraso"))
                    rv.setTextViewText(R.id.wgPagar, I18n.t("Pagar agora"))
                } else if (abertas.isNotEmpty()) {
                    val p = abertas.first()
                    rv.setTextViewText(R.id.wgTitulo, "NexumCell · " + I18n.t("parcela") + " ${p.n}/${p.total}")
                    rv.setTextViewText(R.id.wgValor, brl(p.aPagar))
                    val dv = p.diasParaVencer
                    rv.setTextViewText(R.id.wgVenc, when {
                        dv <= 0L -> I18n.t("vence hoje")
                        dv == 1L -> I18n.t("vence amanhã")
                        else -> I18n.t("vence em") + " $dv " + I18n.t("dias")
                    } + if (p.desconto > 0) " · " + I18n.t("com desconto") else "")
                    rv.setTextViewText(R.id.wgPagar, I18n.t("Pagar"))
                } else {
                    rv.setTextViewText(R.id.wgValor, I18n.t("Em dia")); rv.setTextViewText(R.id.wgVenc, ""); rv.setTextViewText(R.id.wgPagar, I18n.t("Abrir"))
                }
            }
            val abrir = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            rv.setOnClickPendingIntent(R.id.wgRaiz, abrir); rv.setOnClickPendingIntent(R.id.wgPagar, abrir)
            mgr.updateAppWidget(ids, rv)
        }
    }
}
