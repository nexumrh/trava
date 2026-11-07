package br.com.nexumcell.trava

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Resultado(val travar: Boolean, val motivo: String, val final: Boolean = false)

/**
 * Regra da trava. É a MESMA regra da função avaliar() do painel web —
 * se mudar uma, mude a outra.
 */
object Decisao {
    private const val DIA = 86_400_000L

    fun avaliar(d: JSONObject, agora: Long, ultimoSyncOk: Long): Resultado {
        val status = d.optString("status")
        if (d.optBoolean("quitado") || status == "QUITADO") return Resultado(false, "Contrato quitado", true)
        if (status == "CANCELADO") return Resultado(false, "Contrato cancelado", true)

        // cliente comunicou roubo, furto ou perda pelo portal: trava até a loja liberar
        val roubo = d.optJSONObject("roubo")
        if (roubo != null && roubo.optLong("liberadoEm", 0L) == 0L) {
            return Resultado(true, "Aparelho com comunicação de ${roubo.optString("tipo", "roubo").lowercase()}. Se você encontrou este celular, entregue em uma loja ${d.optString("lojaNome", "NexumCell")}.")
        }

        when (d.optString("modo")) {
            "TRAVAR" -> return Resultado(true, "Bloqueio solicitado pela loja")
            "LIBERAR" -> return Resultado(false, "Liberado pela loja")
        }

        val liberadoAte = d.optLong("liberadoAte", 0L)
        if (liberadoAte > agora) return Resultado(false, "Liberação temporária")

        val tolerancia = d.optInt("toleranciaDias", 0)
        val hoje = LocalDate.now()
        var atrasadas = 0
        val parcelas = d.optJSONArray("parcelas")
        if (parcelas != null) {
            for (i in 0 until parcelas.length()) {
                val p = parcelas.optJSONObject(i) ?: continue
                if (p.optBoolean("pago")) continue
                val venc = try { LocalDate.parse(p.optString("venc")) } catch (e: Exception) { continue }
                if (ChronoUnit.DAYS.between(venc, hoje) > tolerancia) atrasadas++
            }
        }
        if (atrasadas > 0) return Resultado(true, if (atrasadas == 1) "1 parcela em atraso" else "$atrasadas parcelas em atraso")

        val maxOffline = d.optInt("maxDiasOffline", 15).coerceAtLeast(1)
        if (ultimoSyncOk > 0 && agora - ultimoSyncOk > maxOffline * DIA) {
            return Resultado(true, "Aparelho sem conexão há mais de $maxOffline dias. Conecte-se à internet (Wi-Fi ou dados) e toque em verificar.")
        }
        return Resultado(false, "Em dia")
    }
}
