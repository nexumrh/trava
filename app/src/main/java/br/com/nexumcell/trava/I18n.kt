package br.com.nexumcell.trava

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale

/**
 * Espanhol para clientes paraguaios e argentinos: se o celular estiver em espanhol,
 * as telas e avisos do app aparecem em espanhol. Textos fixos por tabela, textos com
 * números por padrões. O que não estiver na tabela continua em português.
 */
object I18n {
    fun espanhol(): Boolean = Locale.getDefault().language == "es"

    private val fixos = mapOf(
        "Aparelho bloqueado" to "Equipo bloqueado",
        "Atualizar" to "Actualizar",
        "Atualizando…" to "Actualizando…",
        "Chamada de emergência (190 · 192 · 193)" to "Llamada de emergencia (190 · 192 · 193)",
        "Liberar por 24 h (1 vez por mês)" to "Liberar por 24 h (1 vez por mes)",
        "Ligar para a loja" to "Llamar a la tienda",
        "Minhas faturas" to "Mis cuotas",
        "Pagar agora com PIX" to "Pagar ahora con PIX",
        "Já paguei — verificar agora" to "Ya pagué — verificar ahora",
        "Verificando…" to "Verificando…",
        "Contrato quitado" to "Contrato cancelado (pagado)",
        "App não ativado" to "App no activada",
        "Configurando…" to "Configurando…",
        "Conecte o aparelho à internet para carregar o seu contrato." to "Conecte el equipo a internet para cargar su contrato.",
        "Bloqueio solicitado pela loja" to "Bloqueo solicitado por la tienda",
        "Liberado pela loja" to "Liberado por la tienda",
        "Em dia" to "Al día",
        "Pagar com PIX" to "Pagar con PIX",
        "Copiar código PIX" to "Copiar código PIX",
        "Fechar" to "Cerrar",
        "Cancelar" to "Cancelar",
        "Liberar" to "Liberar",
        "Liberar por 24 horas?" to "¿Liberar por 24 horas?",
        "Tenho um código da loja" to "Tengo un código de la tienda",
        "Cartão de crédito ou boleto" to "Tarjeta de crédito o boleto",
        "Seu score NexumCell" to "Su puntaje NexumCell",
        "Faltam" to "Faltan",
        "pontos para" to "puntos para",
        "Pague em dia para subir e ganhar limite maior." to "Pague al día para subir y ganar más crédito.",
        "Nível máximo! Você tem as melhores condições." to "¡Nivel máximo! Tiene las mejores condiciones.",
        "Código da loja" to "Código de la tienda",
        "Digite o código de 6 números que a loja passou. Funciona mesmo sem internet." to "Escriba el código de 6 números que le dio la tienda. Funciona incluso sin internet.",
        "Aparelho liberado pela loja." to "Equipo liberado por la tienda.",
        "O código tem 6 números." to "El código tiene 6 números.",
        "Código inválido ou já usado. Peça um código novo à loja." to "Código inválido o ya usado. Pida un código nuevo a la tienda.",
        "Muitas tentativas erradas. Tente de novo em" to "Demasiados intentos incorrectos. Intente de nuevo en",
        "Chip trocado. Procure a loja para liberar o aparelho." to "Chip cambiado. Acuda a la tienda para liberar el equipo.",
        "Liberado com código da loja" to "Liberado con código de la tienda",
        "Parcela em atraso" to "Cuota atrasada",
        "Funções limitadas por atraso" to "Funciones limitadas por atraso",
        "Sua parcela vence hoje" to "Su cuota vence hoy",
        "Sua parcela vence em 3 dias" to "Su cuota vence en 3 días",
        "Aviso da loja" to "Aviso de la tienda",
        "Código PIX copiado. Cole no app do seu banco." to "Código PIX copiado. Péguelo en la app de su banco.",
        "Gerando PIX…" to "Generando PIX…",
        "O IMEI deste aparelho não confere com o contrato. Procure a loja." to "El IMEI de este equipo no coincide con el contrato. Acuda a la tienda.",
        "Liberação de confiança (24 h)" to "Liberación de confianza (24 h)",
        "Liberação temporária" to "Liberación temporal",
        "Você pode usar o celular normalmente por 24 horas, uma vez por mês. As parcelas continuam em atraso, com multa e juros. Depois das 24 horas, se não houver pagamento, o aparelho volta a ser bloqueado." to
            "Puede usar el celular normalmente por 24 horas, una vez al mes. Las cuotas siguen atrasadas, con multa e intereses. Después de las 24 horas, si no hay pago, el equipo vuelve a bloquearse.",
        "Liberado por 24 horas. Pague o quanto antes." to "Liberado por 24 horas. Pague lo antes posible.",
        "Não foi possível liberar. Verifique a internet ou se já usou este mês." to "No fue posible liberar. Verifique internet o si ya lo usó este mes.",
        "Depois de pagar, envie o comprovante para a loja. Assim que o pagamento for confirmado, o aparelho é liberado automaticamente." to
            "Después de pagar, envíe el comprobante a la tienda. Cuando se confirme el pago, el equipo se libera automáticamente.",
        "O pagamento é confirmado sozinho. Em poucos minutos a parcela aparece como paga e o aparelho é liberado." to
            "El pago se confirma solo. En pocos minutos la cuota aparece como pagada y el equipo se libera.",
    )

    private val padroes: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("^(\\d+) parcelas? em atraso$") to { m -> "${m.groupValues[1]} cuota(s) atrasada(s)" },
        Regex("^Funções limitadas: (\\d+) dias de atraso$") to { m -> "Funciones limitadas: ${m.groupValues[1]} días de atraso" },
        Regex("^Parcela em atraso há (\\d+) dia\\(s\\)$") to { m -> "Cuota atrasada hace ${m.groupValues[1]} día(s)" },
        Regex("^Faltam (\\d+) dia\\(s\\) para (.*)$") to { m -> "Faltan ${m.groupValues[1]} día(s) para " + (fixos[m.groupValues[2]] ?: m.groupValues[2]) },
        Regex("^Quitar o contrato agora: (.*) \\(economize (.*)\\)$") to { m -> "Cancelar el contrato ahora: ${m.groupValues[1]} (ahorre ${m.groupValues[2]})" },
        Regex("^Chave PIX: (.*)\\n\\(toque para copiar\\)$") to { m -> "Clave PIX: ${m.groupValues[1]}\n(toque para copiar)" },
        Regex("^Aparelho sem conexão há mais de (\\d+) dias\\. (.*)$") to { m -> "Equipo sin conexión hace más de ${m.groupValues[1]} días. Conéctese a internet (Wi-Fi o datos) y toque verificar." },
        Regex("^Aparelho com comunicação de (.*)\\. (.*)$") to { m -> "Equipo con denuncia de ${m.groupValues[1]}. Si encontró este celular, entréguelo en una tienda NexumCell." },
    )

    fun t(s: String): String {
        if (!espanhol()) return s
        fixos[s]?.let { return it }
        for ((r, f) in padroes) r.find(s)?.let { if (it.value == s) return f(it) }
        return s
    }

    /** Traduz todos os textos de uma tela (botões e rótulos). */
    fun traduzir(v: View?) {
        if (v == null || !espanhol()) return
        if (v is TextView) { val s = v.text?.toString() ?: ""; val n = t(s); if (n != s) v.text = n }
        if (v is ViewGroup) for (i in 0 until v.childCount) traduzir(v.getChildAt(i))
    }

    // textos dos avisos (notificações) com contagem regressiva
    fun aviso(nivel: Int, dias: Long?): String = if (espanhol()) {
        val falta = if (dias != null && dias > 0) " Faltan $dias día(s) para el ${if (nivel == 2) "bloqueo total" else "límite de funciones"}." else ""
        (if (nivel == 2) "Redes sociales, videos y juegos están bloqueados hasta el pago." else "Tiene una cuota atrasada. Pague ahora para evitar limitaciones.") + falta + " Toque para pagar con PIX."
    } else {
        val falta = if (dias != null && dias > 0) " Faltam $dias dia(s) para ${if (nivel == 2) "o bloqueio total" else "limitar as funções"}." else ""
        (if (nivel == 2) "Redes sociais, vídeos e jogos estão bloqueados até o pagamento." else "Você tem parcela em atraso. Pague agora para evitar a limitação.") + falta + " Toque para pagar com PIX."
    }
}
