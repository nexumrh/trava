package br.com.nexumcell.trava

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização automática: a cada 6 h consulta o último Release do GitHub (nexumrh/trava).
 * Se houver versão nova, baixa o APK e instala sozinho (permitido porque o app é Device Owner).
 * O APK precisa ser assinado com a MESMA chave — o GitHub Actions já faz isso.
 */
object Updater {
    private const val TAG = "NexumUpdate"
    private const val INTERVALO = 6 * 60 * 60 * 1000L

    fun verificar(ctx: Context, forcar: Boolean = false) {
        val st = Store(ctx)
        val agora = System.currentTimeMillis()
        if (!forcar && agora - st.ultimaChecagemUpdate < INTERVALO) return
        st.ultimaChecagemUpdate = agora
        if (!Policy.isOwner(ctx)) return
        try {
            val c = URL(BuildConfig.UPDATE_API).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            if (c.responseCode != 200) { c.disconnect(); return }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            c.disconnect()
            // tag "v1.N" → versionCode N + 2 (mesma conta do build.gradle.kts)
            val n = j.optString("tag_name").substringAfterLast('.').toIntOrNull() ?: return
            val remoto = n + 2
            if (remoto <= BuildConfig.VERSION_CODE) return
            val assets = j.optJSONArray("assets") ?: return
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
            }
            if (url.isNullOrEmpty()) return
            val arq = File(ctx.cacheDir, "nexumcell-update.apk")
            baixar(url, arq)
            instalar(ctx, arq)
            Log.i(TAG, "Atualizando para versão $remoto")
        } catch (e: Exception) {
            Log.w(TAG, "falha ao atualizar: ${e.message}")
        }
    }

    private fun baixar(url: String, destino: File) {
        var endereco = url
        repeat(5) {
            val c = URL(endereco).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            val code = c.responseCode
            if (code in 300..399) { endereco = c.getHeaderField("Location") ?: return; c.disconnect(); return@repeat }
            if (code != 200) { c.disconnect(); throw IllegalStateException("download $code") }
            c.inputStream.use { inp -> destino.outputStream().use { inp.copyTo(it) } }
            c.disconnect()
            return
        }
    }

    private fun instalar(ctx: Context, arq: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("nexumcell.apk", 0, arq.length()).use { out ->
                arq.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
            val pend = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(pend.intentSender)
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        Log.i("NexumUpdate", "status da instalação: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
    }
}
