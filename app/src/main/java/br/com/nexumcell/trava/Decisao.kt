package br.com.nexumcell.trava

import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** nivel: 0 em dia · 1 aviso na tela · 2 funções limitadas (redes sociais) · 3 bloqueio total */
data class Resultado(val travar: Boolean, val motivo: String, val final: Boolean = false, val nivel: Int = if (travar) 3 else 0)

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

        val confianca = d.optJSONObject("confianca")
        if (confianca != null && confianca.optLong("ate", 0L) > agora) return Resultado(false, "Liberação de confiança (24 h)")

        val (avisoDias, parcialDias, totalDias) = etapas(d)
        val hoje = LocalDate.now()
        var atrasadas = 0
        var maior = 0L
        val parcelas = d.optJSONArray("parcelas")
        if (parcelas != null) {
            for (i in 0 until parcelas.length()) {
                val p = parcelas.optJSONObject(i) ?: continue
                if (p.optBoolean("pago")) continue
                val venc = try { LocalDate.parse(p.optString("venc")) } catch (e: Exception) { continue }
                val dias = ChronoUnit.DAYS.between(venc, hoje)
                if (dias > 0) { atrasadas++; if (dias > maior) maior = dias }
            }
        }
        if (maior >= totalDias) return Resultado(true, if (atrasadas == 1) "1 parcela em atraso" else "$atrasadas parcelas em atraso")
        if (maior >= parcialDias) return Resultado(false, "Funções limitadas: $maior dias de atraso", nivel = 2)
        if (maior >= avisoDias) return Resultado(false, "Parcela em atraso há $maior dia(s)", nivel = 1)

        val maxOffline = d.optInt("maxDiasOffline", 15).coerceAtLeast(1)
        if (ultimoSyncOk > 0 && agora - ultimoSyncOk > maxOffline * DIA) {
            return Resultado(true, "Aparelho sem conexão há mais de $maxOffline dias. Conecte-se à internet (Wi-Fi ou dados) e toque em verificar.")
        }
        return Resultado(false, "Em dia")
    }

    /** (aviso, parcial, total) em dias de atraso. Contratos antigos: só bloqueio total depois da tolerância. */
    fun etapas(d: JSONObject): Triple<Long, Long, Long> {
        if (d.has("etapaTotalDias")) {
            val t = d.optLong("etapaTotalDias", 7).coerceAtLeast(1)
            val p = d.optLong("etapaParcialDias", t).coerceIn(1, t)
            return Triple(d.optLong("etapaAvisoDias", 1).coerceAtLeast(1), p, t)
        }
        val t = d.optLong("toleranciaDias", 0) + 1
        return Triple(1, t, t)
    }

    fun mesAtual(): String = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).let { "%04d-%02d".format(it.year, it.monthValue) }

    /** Liberação de confiança: 24 h, uma vez por mês, só para atraso (não vale para roubo, bloqueio da loja ou IMEI). */
    fun podeConfianca(d: JSONObject): Boolean {
        if (d.optString("confiancaMensal", "sim") == "nao") return false
        if (d.optString("modo") == "TRAVAR") return false
        val roubo = d.optJSONObject("roubo")
        if (roubo != null && roubo.optLong("liberadoEm", 0L) == 0L) return false
        return d.optJSONObject("confianca")?.optString("mes") != mesAtual()
    }
}
