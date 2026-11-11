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
        var agora = Relogio.agora(ctx)
        val pagasAntes = st.cache()?.let { pagas(it) } ?: -1
        val estavaTravado = st.travado
        var online = false
        try {
            val doc = Api(ctx).buscarAparelho()
            if (doc != null) {
                st.cacheJson = doc.toString()
                agora = Relogio.agora(ctx)   // já com a hora do servidor desta resposta
                st.ultimoSyncOk = agora
                online = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "sem conexão: ${e.message}")
        }

        val d = st.cache() ?: return online
        try { Push.configurar(ctx, d) } catch (e: Throwable) { }
        if (online && !st.chaveEnviada) {
            try { if (Api(ctx).enviarChave(st.segredo)) st.chaveEnviada = true } catch (e: Exception) { Log.w(TAG, "chave: ${e.message}") }
        }
        var r = Decisao.avaliar(d, agora, st.ultimoSyncOk, st.liberadoLocalAte)

        // Antifraude: o IMEI real deste aparelho precisa ser o mesmo do contrato
        val doAparelho = listOf(imei(ctx, 0), imei(ctx, 1)).filter { it.length == 15 }
        val doContrato = listOf(d.optString("imei"), d.optString("imei2")).filter { it.length == 15 }
        val imeiConfere = doAparelho.isEmpty() || doContrato.isEmpty() || doAparelho.any { it in doContrato }
        imeiConfereCache = imeiConfere
        if (!imeiConfere && !r.final) {
            r = Resultado(true, "O IMEI deste aparelho não confere com o contrato. Procure a loja.")
        }

        // Check-in só quando o estado muda ou a cada 3 h (economiza a cota grátis do Firestore).
        val chip = chipInfo(ctx)
        // troca de chip: o painel avisa; se o Dono escolheu "travar", trava até a loja conferir
        val chipAtual = chip["id"]?.toString().orEmpty()
        if (chipAtual.isNotEmpty() && (st.chipBase.isEmpty() || chipAtual == d.optString("chipAceito"))) st.chipBase = chipAtual
        val chipTrocado = chipAtual.isNotEmpty() && st.chipBase.isNotEmpty() && chipAtual != st.chipBase
        chipCache = chip + mapOf("trocado" to chipTrocado)
        if (chipTrocado && !r.final && !r.travar && st.liberadoLocalAte <= agora && d.optString("acaoTrocaChip", "avisar") == "travar") {
            r = Resultado(true, "Chip trocado. Procure a loja para liberar o aparelho.")
        }
        // roubo comunicado com autorização do cliente: manda a localização a cada sincronização
        val roubo = d.optJSONObject("roubo")
        val localizar = roubo != null && roubo.optLong("liberadoEm", 0L) == 0L && roubo.optBoolean("localizar")
        localCache = if (localizar) Localizador.obter(ctx) else null
        val estado = "${r.travar}|${r.final}|$imeiConfere|${r.nivel}|${chip["id"]}|${st.pushToken.take(12)}|${st.liberadoLocalAte}|${Policy.adminAtivo(ctx)}|${Relogio.adulterado(ctx)}|${GoogleLock.status(ctx)}"
        if (online && (localizar || estado != st.ultimoEstado || agora - st.ultimoCheckin > CHECKIN_MS)) {
            try {
                Api(ctx).enviarCheckin(checkin(ctx, r))
                st.ultimoEstado = estado
                st.ultimoCheckin = agora
            } catch (e: Exception) { Log.w(TAG, "checkin: ${e.message}") }
        }
        Policy.aplicar(ctx, d, r)

        try { Notificador.avisar(ctx, d) } catch (e: Exception) { Log.w(TAG, "avisos: ${e.message}") }
        try { Notificador.avisoLoja(ctx, d) } catch (e: Exception) { }
        WidgetParcela.atualizar(ctx)
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
    @Volatile private var chipCache: Map<String, Any?> = emptyMap()
    @Volatile private var localCache: Map<String, Any?>? = null

    /**
     * Identifica o chip em uso sem guardar o número dele: operadora + resumo (SHA-256) do ICCID.
     * Se o chip for trocado, o painel avisa a equipe (pode ser revenda do aparelho ou golpe).
     */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun chipInfo(ctx: Context): Map<String, Any?> = try {
        val sm = ctx.getSystemService(android.telephony.SubscriptionManager::class.java)
        val subs = try { sm?.activeSubscriptionInfoList.orEmpty() } catch (e: Throwable) { emptyList() }
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val ids = subs.map { s -> (try { s.iccId } catch (e: Throwable) { null }).orEmpty().ifEmpty { "${s.mccString}${s.mncString}${s.carrierName}" } }.sorted()
        val operadora = subs.joinToString(" + ") { it.carrierName?.toString().orEmpty() }.ifEmpty { tm?.simOperatorName.orEmpty() }
        val bruto = ids.joinToString("|").ifEmpty { tm?.simOperator.orEmpty() }
        val id = if (bruto.isEmpty()) "" else java.security.MessageDigest.getInstance("SHA-256").digest(bruto.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
        // país do chip e da rede onde o celular está agora (br, py, ar…) — para o alerta de aparelho fora do país
        val paisChip = (try { tm?.simCountryIso } catch (e: Throwable) { null }).orEmpty().lowercase()
        val paisRede = (try { tm?.networkCountryIso } catch (e: Throwable) { null }).orEmpty().lowercase()
        mapOf("id" to id, "operadora" to operadora, "qtd" to subs.size, "paisChip" to paisChip, "paisRede" to paisRede)
    } catch (e: Throwable) { mapOf("id" to "", "operadora" to "", "qtd" to 0) }

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
        "modo" to Policy.modo(ctx),
        "adminAtivo" to Policy.adminAtivo(ctx),
        "sobreposicao" to Policy.sobreposicao(ctx),
        "imeiConfere" to imeiConfereCache,
        "chip" to chipCache,
        "local" to localCache,
        "versao" to BuildConfig.VERSION_NAME,
        "push" to Store(ctx).pushToken,
        "liberadoCodigoAte" to Store(ctx).liberadoLocalAte,
        "protecoes" to Policy.protecoesAtivas,
        "relogio" to Relogio.info(ctx),
        "googleLock" to GoogleLock.status(ctx),
        "modelo" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "saude" to saude(ctx),
        "android" to Build.VERSION.RELEASE
    )

    /** Saúde do aparelho: armazenamento, bateria, root e depuração (ajuda na avaliação da retomada e contra fraude). */
    private fun saude(ctx: Context): Map<String, Any?> = try {
        val sf = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        val bat = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val saudeBat = when (bat?.getIntExtra(android.os.BatteryManager.EXTRA_HEALTH, 0)) { 2 -> "boa"; 3 -> "superaquecida"; 4 -> "ruim"; 5 -> "sobretensão"; 7 -> "fria"; else -> "desconhecida" }
        val root = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/app/Superuser.apk", "/data/adb/magisk").any { java.io.File(it).exists() } || (Build.TAGS?.contains("test-keys") == true)
        val dev = try { android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 } catch (e: Exception) { false }
        mapOf(
            "armLivreGb" to Math.round(sf.availableBytes / 1e8) / 10.0, "armTotalGb" to Math.round(sf.totalBytes / 1e8) / 10.0,
            "bateriaSaude" to saudeBat, "temperatura" to ((bat?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0),
            "carregando" to ((bat?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) > 0),
            "ligadoHa" to android.os.SystemClock.elapsedRealtime() / 3_600_000L, "root" to root, "modoDesenvolvedor" to dev)
    } catch (e: Throwable) { mapOf("erro" to (e.message ?: "")) }

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
