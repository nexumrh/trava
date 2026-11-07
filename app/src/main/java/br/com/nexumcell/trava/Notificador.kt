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
    fun estado(ctx: Context, r: Resultado) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (r.nivel !in 1..2) { nm.cancel(ID_ATRASO); return }
        nm.createNotificationChannel(NotificationChannel(CANAL_ATRASO, "Parcela em atraso", NotificationManager.IMPORTANCE_HIGH))
        val texto = if (r.nivel == 2) "Redes sociais, vídeos e jogos estão bloqueados até o pagamento. Se o atraso continuar, o aparelho será bloqueado por completo. Toque para pagar com PIX."
                    else "Você tem parcela em atraso. Pague agora para evitar a limitação e o bloqueio do aparelho. Toque para pagar com PIX."
        val abrir = PendingIntent.getActivity(ctx, ID_ATRASO, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CANAL_ATRASO)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(if (r.nivel == 2) "Funções limitadas por atraso" else "Parcela em atraso")
            .setContentText(texto)
            .setStyle(Notification.BigTextStyle().bigText(texto))
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
        try { nm.notify(ID_ATRASO, n) } catch (e: SecurityException) { }
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
