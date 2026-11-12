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

    // Instalado por link (sem QR): administrador comum + sobreposição, proteção reduzida
    var modoLink: Boolean
        get() = p.getBoolean("modoLink", false)
        set(v) { p.edit().putBoolean("modoLink", v).commit() }
    var linkAtivadoEm: Long
        get() = l("linkAtivadoEm")
        set(v) { pl("linkAtivadoEm", v) }
    var autostartPedido: Boolean
        get() = p.getBoolean("autostartPedido", false)
        set(v) { p.edit().putBoolean("autostartPedido", v).commit() }
    var adminRemovidoEm: Long
        get() = l("adminRemovidoEm")
        set(v) { pl("adminRemovidoEm", v) }

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
    var ultimaChecagemUpdate: Long
        get() = l("ultimaChecagemUpdate")
        set(v) { pl("ultimaChecagemUpdate", v) }
    var avisosEnviados: String
        get() = s("avisosEnviados")
        set(v) { ps("avisosEnviados", v) }
    // Apps suspensos na etapa "funções limitadas" (para liberar depois)
    var suspensos: String
        get() = p.getString("suspensos", "") ?: ""
        set(v) { p.edit().putString("suspensos", v).apply() }

    // Código de liberação sem internet: segredo só do aparelho (a loja lê pelo painel em chavesTrava/{token})
    var segredo: String
        get() {
            var v = s("segredo")
            if (v.isEmpty()) {
                val b = ByteArray(32); java.security.SecureRandom().nextBytes(b)
                v = b.joinToString("") { "%02x".format(it) }; ps("segredo", v)
            }
            return v
        }
        set(v) { ps("segredo", v) }
    var chaveEnviada: Boolean
        get() = p.getBoolean("chaveEnviada", false)
        set(v) { p.edit().putBoolean("chaveEnviada", v).commit() }
    var codigosUsados: String
        get() = s("codigosUsados")
        set(v) { ps("codigosUsados", v) }
    var liberadoLocalAte: Long
        get() = l("liberadoLocalAte")
        set(v) { pl("liberadoLocalAte", v) }
    var errosCodigo: Long
        get() = l("errosCodigo")
        set(v) { pl("errosCodigo", v) }
    var codigoBloqueadoAte: Long
        get() = l("codigoBloqueadoAte")
        set(v) { pl("codigoBloqueadoAte", v) }

    // Chip que estava no aparelho (para detectar troca)
    var chipBase: String
        get() = s("chipBase")
        set(v) { ps("chipBase", v) }

    // Notificação instantânea (Firebase Cloud Messaging)
    var pushToken: String
        get() = s("pushToken")
        set(v) { ps("pushToken", v) }
    var fcmAppId: String
        get() = s("fcmAppId")
        set(v) { ps("fcmAppId", v) }
    var fcmSender: String
        get() = s("fcmSender")
        set(v) { ps("fcmSender", v) }

    // Relógio confiável (Relogio.kt) e aviso diário em tela cheia
    var relogioMax: Long
        get() = l("relogioMax")
        set(v) { pl("relogioMax", v) }
    var relogioDif: Long
        get() = l("relogioDif")
        set(v) { pl("relogioDif", v) }
    var relogioVoltouEm: Long
        get() = l("relogioVoltouEm")
        set(v) { pl("relogioVoltouEm", v) }
    /** Nome do computador que instalou a trava pelo cabo USB (vem no link de ativação). */
    var instaladoPc: String
        get() = s("instaladoPc")
        set(v) { ps("instaladoPc", v) }
    var avisoTelaDia: String
        get() = s("avisoTelaDia")
        set(v) { ps("avisoTelaDia", v) }

    var finalizado: Boolean
        get() = p.getBoolean("finalizado", false)
        set(v) { p.edit().putBoolean("finalizado", v).commit() }

    // selfie enviada pelo app (ativação do crédito)
    var selfieEnviadaEm: Long
        get() = l("selfieEnviadaEm")
        set(v) { pl("selfieEnviadaEm", v) }

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
