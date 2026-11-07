package br.com.nexumcell.trava

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject

/** Avisos no próprio celular: 3 dias antes, no dia do vencimento e 1 dia depois. */
object Notificador {
    private const val CANAL = "faturas"
    private const val CANAL_ATRASO = "atraso"
    private const val ID_ATRASO = 900

    /** Aviso fixo (não dá para dispensar) enquanto houver atraso: etapa 1 (aviso) e 2 (funções limitadas). */
    fun estado(ctx: Context, r: Resultado, d: JSONObject? = null) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (r.nivel !in 1..2) { nm.cancel(ID_ATRASO); return }
        nm.createNotificationChannel(NotificationChannel(CANAL_ATRASO, "Parcela em atraso", NotificationManager.IMPORTANCE_HIGH))
        // contagem regressiva até a próxima etapa
        var dias: Long? = null
        if (d != null) {
            val (_, parcial, total) = Decisao.etapas(d)
            val maior = Faturas.lista(d).filter { !it.pago }.maxOfOrNull { it.diasAtraso } ?: 0L
            dias = (if (r.nivel == 2) total else parcial) - maior
        }
        val texto = I18n.aviso(r.nivel, dias)
        val abrir = PendingIntent.getActivity(ctx, ID_ATRASO, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CANAL_ATRASO)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(I18n.t(if (r.nivel == 2) "Funções limitadas por atraso" else "Parcela em atraso"))
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
        try { nm.notify(ID_ATRASO, n) } catch (e: SecurityException) { }
    }

    /** Aviso em massa enviado pela loja (ex.: feriado). Aparece uma vez por aviso. */
    fun avisoLoja(ctx: Context, d: JSONObject) {
        val a = d.optJSONObject("aviso") ?: return
        val id = a.optString("id"); if (id.isEmpty() || a.optLong("ate", 0L) < System.currentTimeMillis()) return
        val st = Store(ctx); val enviados = st.avisosEnviados.split(",").toMutableSet()
        if ("aviso-$id" in enviados) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel("loja", "Avisos da loja", NotificationManager.IMPORTANCE_HIGH))
        val texto = a.optString("texto")
        val n = Notification.Builder(ctx, "loja").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(I18n.t("Aviso da loja")).setContentText(texto).setStyle(Notification.BigTextStyle().bigText(texto)).setAutoCancel(true).build()
        try { nm.notify(950, n) } catch (e: SecurityException) { }
        enviados.add("aviso-$id"); st.avisosEnviados = enviados.filter { it.isNotEmpty() }.takeLast(200).joinToString(",")
    }

    fun avisar(ctx: Context, d: JSONObject) {
        val st = Store(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CANAL, "Faturas", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Lembretes de vencimento das parcelas"
        })
        val enviados = st.avisosEnviados.split(",").filter { it.isNotEmpty() }.toMutableSet()
        val tol = d.optInt("toleranciaDias", 0)
        for (f in Faturas.lista(d)) {
            if (f.pago) continue
            val (tipo, titulo, texto) = when {
                f.diasParaVencer == 3L -> Triple("d3", "Sua parcela vence em 3 dias",
                    "Parcela ${f.n}/${f.total}: ${Faturas.brl.format(f.aPagar)} pagando até ${f.venc.minusDays(1).format(Faturas.fmt)} (com desconto). Toque para pagar com PIX.")
                f.diasParaVencer == 0L -> Triple("d0", "Sua parcela vence hoje",
                    "Parcela ${f.n}/${f.total}: ${Faturas.brl.format(f.valor)}. Pague hoje e evite multa e juros.")
                f.diasAtraso == 1L -> Triple("d1", "Parcela em atraso",
                    "A parcela ${f.n}/${f.total} venceu ontem. Regularize para evitar o bloqueio do aparelho" + (if (tol > 0) " (em $tol dia(s))." else "."))
                else -> continue
            }
            val chave = "${f.n}-${f.venc}-$tipo"
            if (chave in enviados) continue
            val abrir = PendingIntent.getActivity(ctx, f.n, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CANAL)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(titulo)
                .setContentText(texto)
                .setStyle(Notification.BigTextStyle().bigText(texto))
                .setContentIntent(abrir)
                .setAutoCancel(true)
                .build()
            try { nm.notify(1000 + f.n, n) } catch (e: SecurityException) { }
            enviados.add(chave)
        }
        st.avisosEnviados = enviados.toList().takeLast(200).joinToString(",")
    }
}
