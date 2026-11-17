package br.com.nexumcell.trava

import org.json.JSONObject
import java.text.Normalizer

/**
 * Assistente virtual do suporte. Responde na hora, com os dados reais do contrato guardados no
 * aparelho (parcelas, valores, vencimentos). O que ela não souber vai para a IA do servidor e,
 * se ainda assim não resolver, o cliente é passado para um atendente humano.
 */
object Assistente {
    /** Resposta da assistente. [transferir] = já passa direto para um atendente. */
    data class Resposta(val texto: String, val transferir: Boolean = false)

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    private fun tem(q: String, vararg p: String) = p.any { q.contains(it) }
    private fun brl(v: Double) = Faturas.brl.format(v)
    private fun dias(n: Long) = if (n == 1L) "1 dia" else "$n dias"

    /** O cliente pediu explicitamente uma pessoa. */
    fun querAtendente(texto: String): Boolean {
        val q = norm(texto)
        return tem(q, "atendente", "humano", "pessoa", "falar com alguem", "falar com a equipe", "falar com o suporte", "operador")
    }

    fun responder(texto: String, d: JSONObject?): Resposta? {
        val q = norm(texto)

        if (tem(q, "roubad", "furtad", "perdi", "perdido", "assalt")) return Resposta(
            "Sinto muito pelo ocorrido. Esse caso precisa de um atendente: vou te passar agora para a equipe, " +
            "que pode bloquear o aparelho à distância. Se puder, já tenha o boletim de ocorrência em mãos.", true)

        if (d == null) return null
        val fat = Faturas.lista(d)
        val abertas = fat.filter { !it.pago }
        val atrasadas = abertas.filter { it.diasAtraso > 0 }
        val prox = abertas.minByOrNull { it.venc }

        val comoPagar = "Para pagar: toque em Parcelas, na barra de baixo, e depois em Pagar com PIX na parcela. " +
            "Copie o código ou leia o QR no app do seu banco. Assim que o pagamento cai, o app atualiza sozinho."

        return when {
            tem(q, "paguei", "ja pago", "nao liberou", "nao desbloqueou", "comprovante", "nao caiu", "nao baixou") -> Resposta(when {
                atrasadas.isEmpty() && (prox == null || prox.diasParaVencer > 0) ->
                    "Está tudo certo: não há nenhuma parcela em aberto vencida no seu contrato.\n\n" +
                    "Se o aparelho ainda aparece bloqueado, conecte-se à internet e toque em Atualizar, na aba Parcelas. A liberação acontece em poucos minutos."
                else -> {
                    val alvo = atrasadas.minByOrNull { it.venc } ?: prox!!
                    "Ainda não recebemos o pagamento da parcela ${alvo.n} (${brl(alvo.aPagar)}).\n\n" +
                    "O PIX costuma ser confirmado em até 5 minutos. Para conferir agora:\n" +
                    "1. Confirme que o aparelho está com internet.\n2. Na aba Parcelas, toque em Atualizar.\n\n" +
                    "Se você pagou há mais de 30 minutos e continua igual, me responda \"Não resolveu\" que eu encaminho para a equipe conferir o comprovante."
                }
            })

            tem(q, "bloque", "travou", "travad", "trava", "nao liga", "desbloque", "liberar") -> Resposta(when {
                atrasadas.isNotEmpty() -> {
                    val total = atrasadas.sumOf { it.aPagar }
                    "Seu aparelho é bloqueado quando há parcela em atraso. Hoje você tem ${atrasadas.size} " +
                    (if (atrasadas.size == 1) "parcela atrasada" else "parcelas atrasadas") + ", somando ${brl(total)} com multa e juros.\n\n" +
                    "$comoPagar\n\nDepois do pagamento a liberação é automática em poucos minutos. Mesmo bloqueado, você continua podendo ligar para emergência e falar com o suporte por aqui."
                }
                else -> "Não encontrei nenhuma parcela em atraso no seu contrato. Se o aparelho está bloqueado, o motivo mais comum é ter ficado muitos dias sem internet.\n\n" +
                    "1. Conecte no Wi-Fi ou nos dados móveis.\n2. Abra o app NexumCell e toque em Atualizar, na aba Parcelas.\n\n" +
                    "Se você já pagou e continua bloqueado depois de alguns minutos, escreva \"paguei e não liberou\" que eu confiro com você."
            })

            tem(q, "quitar", "quitacao", "quito", "pagar tudo", "liquidar", "terminar de pagar", "saldo devedor") -> {
                val qt = Faturas.quitacao(d)
                Resposta(if (qt == null) "Não há saldo em aberto para quitar. Seu contrato já está em dia por completo."
                else "Para quitar o contrato hoje você paga ${brl(qt.first)}" + (if (qt.second > 0.009) ", uma economia de ${brl(qt.second)} em juros" else "") + ".\n\n" +
                    "Na aba Parcelas, toque em Quitar o contrato agora. Depois da quitação a trava é removida automaticamente.")
            }

            tem(q, "atras", "vencid", "devendo", "em aberto", "quanto devo", "debito") -> Resposta(
                if (atrasadas.isEmpty()) "Você não tem nenhuma parcela em atraso. " + (prox?.let { "A próxima é a ${it.n}ª, com vencimento em ${it.venc.format(Faturas.fmt)}." } ?: "")
                else atrasadas.joinToString("\n") { "• Parcela ${it.n}: ${brl(it.aPagar)} (venceu em ${it.venc.format(Faturas.fmt)}, ${dias(it.diasAtraso)} de atraso)" } +
                    "\n\nTotal para regularizar: ${brl(atrasadas.sumOf { it.aPagar })}.\n\n$comoPagar")

            tem(q, "pix", "pagar", "pagamento", "pago", "boleto", "cartao", "como pago") -> Resposta(
                comoPagar + "\n\nSe preferir, na mesma parcela há a opção Cartão de crédito ou boleto." +
                (prox?.let { "\n\nSua próxima parcela é a ${it.n}ª: ${brl(it.aPagar)}, com vencimento em ${it.venc.format(Faturas.fmt)}." } ?: ""))

            tem(q, "desconto", "antecip", "adiant") -> Resposta(
                "Pagando antes do vencimento você ganha ${d.optDouble("descontoPct", 0.0).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}% de desconto na parcela." +
                (prox?.takeIf { it.desconto > 0 }?.let { "\n\nNa sua próxima parcela (${it.n}ª) isso dá ${brl(it.desconto)}: você paga ${brl(it.aPagar)} em vez de ${brl(it.valor)}." } ?: "") +
                "\n\nQuitando o contrato inteiro, você também deixa de pagar os juros das parcelas que ainda não venceram.")

            tem(q, "mudar", "trocar", "alterar") && tem(q, "venc", "dia") -> Resposta(
                "Você pode mudar o dia de vencimento uma vez por contrato, pelo portal do cliente, na opção Mudar dia do vencimento. " +
                "Na aba Contrato do app há um atalho direto para essa opção.")

            tem(q, "proxima", "parcela", "valor", "quanto", "vencimento", "vence", "quando") -> Resposta(
                if (prox == null) "Você não tem parcelas em aberto. Seu contrato está quitado."
                else buildString {
                    append("Sua próxima parcela é a ${prox.n}ª de ${prox.total}, com vencimento em ${prox.venc.format(Faturas.fmt)}")
                    append(when { prox.diasAtraso > 0 -> " (está ${dias(prox.diasAtraso)} em atraso)"; prox.diasParaVencer == 0L -> " (vence hoje)"; else -> " (daqui a ${dias(prox.diasParaVencer)})" })
                    append(".\n\nValor para pagar hoje: ${brl(prox.aPagar)}")
                    if (prox.desconto > 0) append(", já com ${brl(prox.desconto)} de desconto por pagar antes do vencimento")
                    if (prox.encargos > 0) append(", incluindo ${brl(prox.encargos)} de multa e juros")
                    append(".\n\nFaltam ${abertas.size} de ${prox.total} parcelas. Para pagar, abra a aba Parcelas e toque em Pagar com PIX.")
                })

            tem(q, "contrato", "pdf", "copia") -> Resposta(
                "Contrato nº ${d.optLong("vendaNumero")} · ${d.optString("marca")} ${d.optString("modelo")}.\n\nNa aba Contrato, toque em Baixar contrato assinado (PDF) para guardar uma cópia.")

            tem(q, "desinstal", "remover o app", "tirar o app", "sair do celular", "remover a trava", "tirar a trava") -> Resposta(
                "A proteção sai sozinha quando o contrato é quitado; aí você pode desinstalar o app normalmente. Antes disso ela não pode ser removida.")

            tem(q, "internet", "offline", "sem sinal", "wi-fi", "wifi") -> Resposta(
                "O app precisa de internet de vez em quando para confirmar seus pagamentos. Se o aparelho ficar ${d.optInt("maxDiasOffline", 0).takeIf { it > 0 }?.let { "mais de ${dias(it.toLong())}" } ?: "muitos dias"} sem conexão, ele trava por segurança. É só conectar e tocar em Atualizar, na aba Parcelas.")

            tem(q, "obrigad", "valeu", "resolveu", "deu certo") -> Resposta("Que bom que ajudou! Se precisar de mais alguma coisa, é só escrever aqui.")

            tem(q, "oi", "ola", "bom dia", "boa tarde", "boa noite") && q.length < 20 -> Resposta("Olá! Como posso te ajudar? Pode perguntar sobre parcelas, pagamento, quitação ou bloqueio.")

            else -> null
        }
    }

    /** Limpa a resposta da IA do servidor: tira marcações de markdown e telefones avulsos. */
    fun limpar(t: String): String = t
        .replace("**", "").replace("__", "")
        .replace(Regex("(?m)^#+\\s*"), "")
        .replace(Regex("(?m)^\\s*[*-]\\s+"), "• ")
        .replace(Regex("[^.!?\\n]*[Ww]hats[Aa]pp[^.!?\\n]*[.!?]?"), "")
        .trim()
}
