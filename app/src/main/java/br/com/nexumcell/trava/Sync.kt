package br.com.nexumcell.trava

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object Sync {
    private const val TAG = "NexumSync"
    private val lock = Any()
    private const val CHECKIN_MS = 3 * 60 * 60 * 1000L

    /** Busca o contrato, decide e aplica. Funciona offline usando o último estado salvo. */
    fun executar(ctx: Context): Boolean {
        return synchronized(lock) { executarInterno(ctx) }
    }

    private fun executarInterno(ctx: Context): Boolean {
        val st = Store(ctx)
        if (st.finalizado || !st.configurado) return false
        val agora = System.currentTimeMillis()
        val pagasAntes = st.cache()?.let { pagas(it) } ?: -1
        val estavaTravado = st.travado
        var online = false
        try {
            val doc = Api(ctx).buscarAparelho()
            if (doc != null) {
                st.cacheJson = doc.toString()
                st.ultimoSyncOk = agora
                online = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "sem conexão: ${e.message}")
        }

        val d = st.cache() ?: return online
        var r = Decisao.avaliar(d, agora, st.ultimoSyncOk)

        // Antifraude: o IMEI real deste aparelho precisa ser o mesmo do contrato
        val doAparelho = listOf(imei(ctx, 0), imei(ctx, 1)).filter { it.length == 15 }
        val doContrato = listOf(d.optString("imei"), d.optString("imei2")).filter { it.length == 15 }
        val imeiConfere = doAparelho.isEmpty() || doContrato.isEmpty() || doAparelho.any { it in doContrato }
        imeiConfereCache = imeiConfere
        if (!imeiConfere && !r.final) {
            r = Resultado(true, "O IMEI deste aparelho não confere com o contrato. Procure a loja.")
        }

        // Check-in só quando o estado muda ou a cada 3 h (economiza a cota grátis do Firestore).
        val estado = "${r.travar}|${r.final}|$imeiConfere"
        if (online && (estado != st.ultimoEstado || agora - st.ultimoCheckin > CHECKIN_MS)) {
            try {
                Api(ctx).enviarCheckin(checkin(ctx, r))
                st.ultimoEstado = estado
                st.ultimoCheckin = agora
            } catch (e: Exception) { Log.w(TAG, "checkin: ${e.message}") }
        }
        Policy.aplicar(ctx, d, r)

        try { Notificador.avisar(ctx, d) } catch (e: Exception) { Log.w(TAG, "avisos: ${e.message}") }
        if (online) Updater.verificar(ctx)

        // Pagamento confirmado: mostra "Parabéns, suas faturas estão em dia"
        val pagasAgora = pagas(d)
        if (pagasAntes >= 0 && !r.travar && (pagasAgora > pagasAntes || (estavaTravado && !r.final))) {
            ParabensActivity.abrir(ctx, r.final)
        }
        return online
    }

    private fun pagas(d: org.json.JSONObject): Int {
        val arr = d.optJSONArray("parcelas") ?: return 0
        var n = 0
        for (i in 0 until arr.length()) if (arr.optJSONObject(i)?.optBoolean("pago") == true) n++
        return n
    }

    @Volatile private var imeiConfereCache: Boolean = true

    private fun checkin(ctx: Context, r: Resultado): Map<String, Any?> = mapOf(
        "em" to System.currentTimeMillis(),
        "imei" to imei(ctx, 0),
        "imei2" to imei(ctx, 1),
        "bateria" to bateria(ctx),
        "travado" to r.travar,
        "nivel" to r.nivel,
        "motivo" to r.motivo,
        "finalizado" to r.final,
        "deviceOwner" to Policy.isOwner(ctx),
        "imeiConfere" to imeiConfereCache,
        "versao" to BuildConfig.VERSION_NAME,
        "modelo" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "android" to Build.VERSION.RELEASE
    )

    @Suppress("MissingPermission")
    private fun imei(ctx: Context, slot: Int): String = try {
        ctx.getSystemService(TelephonyManager::class.java)?.getImei(slot) ?: ""
    } catch (e: Throwable) { "" }

    private fun bateria(ctx: Context): Int = try {
        ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Throwable) { -1 }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Sync.executar(applicationContext)
        return Result.success()
    }
}

object Agendador {
    private const val NOME = "nexumcell_sync"

    /** Verificação a cada 15 min (mínimo permitido pelo Android). */
    fun agendar(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NOME, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun agora(ctx: Context) {
        WorkManager.getInstance(ctx).enqueueUniqueWork("${NOME}_agora", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<SyncWorker>().build())
    }

    fun cancelar(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork(NOME)
    }
}
