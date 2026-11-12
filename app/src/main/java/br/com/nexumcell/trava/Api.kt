package br.com.nexumcell.trava

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Comunicação com o Firebase pela API REST (sem SDK, sem google-services.json).
 * O aparelho entra com login anônimo e lê apenas o documento aparelhos/{token}.
 */
class Api(private val ctx: Context) {
    private val st = Store(ctx)

    private fun http(method: String, url: String, body: String?, bearer: String?, contentType: String = "application/json"): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            if (method == "PATCH") {
                // HttpURLConnection não aceita PATCH; as APIs do Google aceitam este cabeçalho.
                c.requestMethod = "POST"
                c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            } else {
                c.requestMethod = method
            }
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            try { if (c.date > 0L && (url.contains("googleapis.com"))) Relogio.servidor(ctx, c.date) } catch (e: Throwable) { }
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to txt
        } finally {
            c.disconnect()
        }
    }

    private fun idToken(): String {
        val agora = System.currentTimeMillis()
        if (st.idToken.isNotEmpty() && agora < st.idTokenExp - 60_000) return st.idToken

        if (st.refreshToken.isNotEmpty()) {
            val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(st.refreshToken, "UTF-8")
            val (code, txt) = http("POST", "https://securetoken.googleapis.com/v1/token?key=${st.apiKey}", body, null, "application/x-www-form-urlencoded")
            if (code == 200) {
                val j = JSONObject(txt)
                st.idToken = j.getString("id_token")
                st.refreshToken = j.optString("refresh_token", st.refreshToken)
                st.idTokenExp = agora + (j.optString("expires_in").toLongOrNull() ?: 3600L) * 1000
                return st.idToken
            }
        }

        val (code, txt) = http("POST", "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${st.apiKey}", "{\"returnSecureToken\":true}", null)
        if (code != 200) throw IOException("Falha no login anônimo ($code): $txt")
        val j = JSONObject(txt)
        st.idToken = j.getString("idToken")
        st.refreshToken = j.getString("refreshToken")
        st.idTokenExp = agora + (j.optString("expiresIn").toLongOrNull() ?: 3600L) * 1000
        return st.idToken
    }

    private fun docUrl() =
        "https://firestore.googleapis.com/v1/projects/${st.projectId}/databases/(default)/documents/aparelhos/${st.token}"

    /** Retorna o contrato do aparelho, ou null se o documento não existir. */
    fun buscarAparelho(): JSONObject? {
        val (code, txt) = http("GET", docUrl(), null, idToken())
        if (code == 404) return null
        if (code == 401 || code == 403) st.idToken = ""
        if (code != 200) throw IOException("Erro ao ler contrato ($code): $txt")
        return Fs.decodeFields(JSONObject(txt).optJSONObject("fields") ?: JSONObject())
    }

    /** Atualiza somente o campo "checkin" (as regras do Firestore só permitem isso ao aparelho). */
    /** Pede a liberação de confiança de 24 h (as regras do Firestore conferem o limite de 1 por mês). */
    fun pedirConfianca(ate: Long, mes: String) {
        val info = mapOf("ate" to ate, "mes" to mes, "em" to System.currentTimeMillis())
        val body = JSONObject().put("fields", JSONObject().put("confianca", Fs.encode(info)))
        val (code, txt) = http("PATCH", docUrl() + "?updateMask.fieldPaths=confianca", body.toString(), idToken())
        if (code !in 200..299) throw IOException("Liberação recusada ($code): $txt")
    }

    /** Guarda o segredo do código sem internet em chavesTrava/{token} (cria uma vez; só a equipe lê). */
    fun enviarChave(segredo: String): Boolean {
        val url = "https://firestore.googleapis.com/v1/projects/${st.projectId}/databases/(default)/documents/chavesTrava?documentId=${st.token}"
        val body = JSONObject().put("fields", JSONObject().put("k", Fs.encode(segredo)).put("em", Fs.encode(System.currentTimeMillis())))
        val (code, txt) = http("POST", url, body.toString(), idToken())
        if (code in 200..299 || code == 409) return true
        throw IOException("chave ($code): $txt")
    }

    /** Selfie tirada no app: grava em selfiesApp/{token} (a loja confere antes de concluir a venda). */
    fun enviarSelfie(foto: String) {
        val base = "https://firestore.googleapis.com/v1/projects/${st.projectId}/databases/(default)/documents/selfiesApp"
        val campos = JSONObject().put("foto", Fs.encode(foto)).put("em", Fs.encode(System.currentTimeMillis()))
            .put("modelo", Fs.encode("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"))
        val body = JSONObject().put("fields", campos).toString()
        var (code, txt) = http("POST", "$base?documentId=${st.token}", body, idToken())
        if (code == 409) { val r = http("PATCH", "$base/${st.token}?updateMask.fieldPaths=foto&updateMask.fieldPaths=em&updateMask.fieldPaths=modelo", body, idToken()); code = r.first; txt = r.second }
        if (code !in 200..299) throw IOException("selfie ($code): $txt")
    }

    fun enviarCheckin(info: Map<String, Any?>) {
        val body = JSONObject().put("fields", JSONObject().put("checkin", Fs.encode(info)))
        val (code, txt) = http("PATCH", docUrl() + "?updateMask.fieldPaths=checkin", body.toString(), idToken())
        if (code !in 200..299) throw IOException("Erro no check-in ($code): $txt")
    }

    private fun suporteBase() =
        "https://firestore.googleapis.com/v1/projects/${st.projectId}/databases/(default)/documents/suporteApp"

    /** Suporte dentro do app: grava a mensagem do cliente em suporteApp/{token}/msgs e atualiza o cabeçalho
     *  (para o portal de suporte da NexumCell ver a conversa). Funciona mesmo com o aparelho bloqueado. */
    fun enviarSuporteMsg(texto: String, bloqueado: Boolean, d: JSONObject?) {
        val cab = JSONObject().put("fields", JSONObject()
            .put("vendaNumero", Fs.encode(d?.optLong("vendaNumero") ?: 0L))
            .put("clienteNome", Fs.encode(d?.optString("clienteNome") ?: ""))
            .put("lojaNome", Fs.encode(d?.optString("lojaNome") ?: ""))
            .put("marca", Fs.encode(d?.optString("marca") ?: ""))
            .put("modelo", Fs.encode(d?.optString("modelo") ?: ""))
            .put("bloqueado", Fs.encode(bloqueado))
            .put("atualizadoEm", Fs.encode(System.currentTimeMillis()))
            .put("naoLidoSuporte", Fs.encode(true)))
        val mask = listOf("vendaNumero","clienteNome","lojaNome","marca","modelo","bloqueado","atualizadoEm","naoLidoSuporte")
            .joinToString("&") { "updateMask.fieldPaths=$it" }
        http("PATCH", "${suporteBase()}/${st.token}?$mask", cab.toString(), idToken())
        val msg = JSONObject().put("fields", JSONObject()
            .put("de", Fs.encode("cliente"))
            .put("texto", Fs.encode(texto))
            .put("em", Fs.encode(System.currentTimeMillis())))
        val (code, txt) = http("POST", "${suporteBase()}/${st.token}/msgs", msg.toString(), idToken())
        if (code !in 200..299) throw IOException("suporte ($code): $txt")
    }

    /** Lê a conversa de suporte (mensagens do cliente + respostas da NexumCell), em ordem. */
    fun buscarSuporteMsgs(): List<JSONObject> {
        val (code, txt) = http("GET", "${suporteBase()}/${st.token}/msgs?pageSize=100", null, idToken())
        if (code !in 200..299) return emptyList()
        val arr = JSONObject(txt).optJSONArray("documents") ?: return emptyList()
        val out = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i).optJSONObject("fields") ?: continue
            out.add(Fs.decodeFields(f))
        }
        out.sortBy { (it.opt("em") as? Long) ?: 0L }
        return out
    }
}

/** Conversão entre o formato tipado do Firestore REST e JSON comum. */
object Fs {
    fun decodeFields(fields: JSONObject): JSONObject {
        val out = JSONObject()
        val it = fields.keys()
        while (it.hasNext()) {
            val k = it.next()
            out.put(k, decodeValue(fields.getJSONObject(k)))
        }
        return out
    }

    fun decodeValue(v: JSONObject): Any? = when {
        v.has("stringValue") -> v.getString("stringValue")
        v.has("integerValue") -> v.getString("integerValue").toLongOrNull() ?: 0L
        v.has("doubleValue") -> v.getDouble("doubleValue")
        v.has("booleanValue") -> v.getBoolean("booleanValue")
        v.has("timestampValue") -> v.getString("timestampValue")
        v.has("mapValue") -> decodeFields(v.getJSONObject("mapValue").optJSONObject("fields") ?: JSONObject())
        v.has("arrayValue") -> {
            val arr = JSONArray()
            val vals = v.getJSONObject("arrayValue").optJSONArray("values")
            if (vals != null) for (i in 0 until vals.length()) arr.put(decodeValue(vals.getJSONObject(i)))
            arr
        }
        else -> JSONObject.NULL
    }

    fun encode(v: Any?): JSONObject = when (v) {
        null -> JSONObject().put("nullValue", JSONObject.NULL)
        is Boolean -> JSONObject().put("booleanValue", v)
        is Int, is Long -> JSONObject().put("integerValue", v.toString())
        is Float, is Double -> JSONObject().put("doubleValue", (v as Number).toDouble())
        is Map<*, *> -> {
            val f = JSONObject()
            v.forEach { (k, x) -> f.put(k.toString(), encode(x)) }
            JSONObject().put("mapValue", JSONObject().put("fields", f))
        }
        else -> JSONObject().put("stringValue", v.toString())
    }
}
