package br.com.nexumcell.trava

import android.content.Context
import android.os.PersistableBundle
import org.json.JSONObject

/** Dados persistidos no aparelho (SharedPreferences). */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("nexumcell", Context.MODE_PRIVATE)

    private fun s(k: String) = p.getString(k, "") ?: ""
    private fun ps(k: String, v: String) = p.edit().putString(k, v).commit()
    private fun l(k: String) = p.getLong(k, 0L)
    private fun pl(k: String, v: Long) = p.edit().putLong(k, v).commit()

    // Recebidos no QR code (admin extras)
    var token: String
        get() = s("token")
        set(v) { ps("token", v) }
    var apiKey: String
        get() = s("apiKey")
        set(v) { ps("apiKey", v) }
    var projectId: String
        get() = s("projectId")
        set(v) { ps("projectId", v) }

    // Autenticação anônima no Firebase
    var idToken: String
        get() = s("idToken")
        set(v) { ps("idToken", v) }
    var refreshToken: String
        get() = s("refreshToken")
        set(v) { ps("refreshToken", v) }
    var idTokenExp: Long
        get() = l("idTokenExp")
        set(v) { pl("idTokenExp", v) }

    // Último estado conhecido do contrato (permite travar sem internet)
    var cacheJson: String
        get() = s("cache")
        set(v) { ps("cache", v) }
    var ultimoSyncOk: Long
        get() = l("ultimoSyncOk")
        set(v) { pl("ultimoSyncOk", v) }

    // Estado local da trava
    var travado: Boolean
        get() = p.getBoolean("travado", false)
        set(v) { p.edit().putBoolean("travado", v).commit() }
    var motivo: String
        get() = s("motivo")
        set(v) { ps("motivo", v) }
    var frpAplicado: String
        get() = s("frpAplicado")
        set(v) { ps("frpAplicado", v) }
    var ultimoCheckin: Long
        get() = l("ultimoCheckin")
        set(v) { pl("ultimoCheckin", v) }
    var ultimoEstado: String
        get() = s("ultimoEstado")
        set(v) { ps("ultimoEstado", v) }
    var finalizado: Boolean
        get() = p.getBoolean("finalizado", false)
        set(v) { p.edit().putBoolean("finalizado", v).commit() }

    val configurado: Boolean get() = token.isNotEmpty() && apiKey.isNotEmpty() && projectId.isNotEmpty()

    fun salvarExtras(b: PersistableBundle?) {
        if (b == null) return
        b.getString("token")?.takeIf { it.isNotBlank() }?.let { token = it }
        b.getString("apiKey")?.takeIf { it.isNotBlank() }?.let { apiKey = it }
        b.getString("projectId")?.takeIf { it.isNotBlank() }?.let { projectId = it }
    }

    fun cache(): JSONObject? {
        val c = cacheJson
        if (c.isEmpty()) return null
        return try { JSONObject(c) } catch (e: Exception) { null }
    }
}
