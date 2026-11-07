package br.com.nexumcell.trava

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.text.Normalizer
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Uma parcela com o valor a pagar hoje (desconto antecipado ou multa + juros). Mesma regra do painel (encargos). */
data class Fatura(
    val n: Int, val total: Int, val venc: LocalDate, val valor: Double, val pago: Boolean,
    val aPagar: Double, val desconto: Double, val encargos: Double, val diasAtraso: Long, val diasParaVencer: Long
)

object Faturas {
    val brl: NumberFormat = NumberFormat.getCurrencyInstance(Locale("pt", "BR"))
    val fmt: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun lista(d: JSONObject, hoje: LocalDate = LocalDate.now()): List<Fatura> {
        val arr = d.optJSONArray("parcelas") ?: return emptyList()
        val desc = d.optDouble("descontoPct", 0.0)
        val multa = d.optDouble("multaPct", 0.0)
        val mora = d.optDouble("moraDiaPct", 0.0)
        val out = mutableListOf<Fatura>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val venc = try { LocalDate.parse(p.optString("venc")) } catch (e: Exception) { continue }
            val valor = p.optDouble("valor", 0.0)
            val pago = p.optBoolean("pago")
            val ate = ChronoUnit.DAYS.between(hoje, venc)          // > 0: ainda vai vencer
            val atraso = if (pago) 0L else maxOf(0L, -ate)
            var aPagar = valor; var d0 = 0.0; var enc = 0.0
            if (!pago && ate > 0) { d0 = r2(valor * desc / 100); aPagar = r2(valor - d0) }
            else if (!pago && atraso > 0) { enc = r2(valor * multa / 100) + r2(valor * mora / 100 * atraso); aPagar = r2(valor + enc) }
            out.add(Fatura(p.optInt("n"), arr.length(), venc, valor, pago, aPagar, d0, enc, atraso, ate))
        }
        return out
    }

    private fun r2(x: Double) = Math.round(x * 100) / 100.0

    /**
     * Quitação antecipada (CDC art. 52, §2º): parcelas ainda não vencidas são cobradas só pela parte do valor
     * financiado, sem os juros. Vencidas entram com multa e juros. Retorna (valor para quitar, economia) ou null.
     */
    fun quitacao(d: JSONObject): Pair<Double, Double>? {
        val fin = d.optDouble("financiado", 0.0); val n = d.optInt("nParcelas", 0)
        if (fin <= 0 || n <= 0) return null
        val principal = fin / n
        var total = 0.0; var cheio = 0.0
        for (f in lista(d)) {
            if (f.pago) continue
            cheio += if (f.diasAtraso > 0) f.aPagar else f.valor
            total += if (f.diasParaVencer > 0) minOf(principal, f.valor) else f.aPagar
        }
        if (total <= 0) return null
        return r2(total) to r2(cheio - total)
    }

    /** PIX copia e cola (BR Code estático com valor). */
    fun pix(d: JSONObject, valor: Double, txid: String): String? {
        val chave = d.optString("chavePix").trim()
        if (chave.isEmpty()) return null
        fun f(id: String, v: String) = id + v.length.toString().padStart(2, '0') + v
        fun limpa(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .uppercase(Locale.ROOT).replace(Regex("[^A-Z0-9 ]"), "").trim()
        val nome = limpa(d.optString("recebedor", "NEXUMCELL")).ifEmpty { "NEXUMCELL" }.take(25)
        val cidade = limpa(d.optString("cidadeRecebedor", "FOZ DO IGUACU")).ifEmpty { "FOZ DO IGUACU" }.take(15)
        val tx = txid.replace(Regex("[^A-Za-z0-9]"), "").take(25).ifEmpty { "***" }
        var p = f("00", "01") + f("26", f("00", "br.gov.bcb.pix") + f("01", chave)) + f("52", "0000") + f("53", "986") +
            f("54", String.format(Locale.US, "%.2f", valor)) + f("58", "BR") + f("59", nome) + f("60", cidade) + f("62", f("05", tx)) + "6304"
        return p + crc16(p)
    }

    private fun crc16(s: String): String {
        var crc = 0xFFFF
        for (b in s.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x1021) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return String.format("%04X", crc)
    }

    fun qr(txt: String, tam: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(txt, BarcodeFormat.QR_CODE, tam, tam)
        val px = IntArray(tam * tam)
        for (y in 0 until tam) for (x in 0 until tam) px[y * tam + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, tam, tam, Bitmap.Config.RGB_565)
    }

    /** Janela "Pagar com PIX". Se a loja tiver o servidor Asaas, gera PIX com baixa automática; senão, PIX estático. */
    fun mostrarPix(a: Activity, d: JSONObject, valor: Double, descricao: String, txid: String, parcela: Int = 0) {
        val servidor = d.optString("servidorPix").trim().trimEnd('/')
        val token = Store(a).token
        if (parcela > 0 && servidor.startsWith("https://") && token.isNotEmpty()) {
            Toast.makeText(a, "Gerando PIX…", Toast.LENGTH_SHORT).show()
            Thread {
                val r = try {
                    val c = (java.net.URL("$servidor/api/pix").openConnection() as java.net.HttpURLConnection).apply {
                        requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 20000
                        setRequestProperty("Content-Type", "application/json")
                    }
                    c.outputStream.use { it.write(JSONObject().put("token", token).put("n", parcela).toString().toByteArray()) }
                    if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()) else null
                } catch (e: Exception) { null }
                a.runOnUiThread {
                    val cod = r?.optString("copiaECola").orEmpty()
                    if (cod.isNotEmpty()) dialogo(a, cod, r!!.optDouble("valor", valor), descricao, true)
                    else estatico(a, d, valor, descricao, txid)
                }
            }.start()
            return
        }
        estatico(a, d, valor, descricao, txid)
    }

    private fun estatico(a: Activity, d: JSONObject, valor: Double, descricao: String, txid: String) {
        val codigo = pix(d, valor, txid)
        if (codigo == null) {
            Toast.makeText(a, "Chave PIX não cadastrada. Fale com a loja.", Toast.LENGTH_LONG).show(); return
        }
        dialogo(a, codigo, valor, descricao, false)
    }

    private fun dialogo(a: Activity, codigo: String, valor: Double, descricao: String, automatico: Boolean) {
        if (a.isFinishing) return
        val dp = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0) }
        box.addView(TextView(a).apply { text = "${brl.format(valor)}\n$descricao"; gravity = Gravity.CENTER; textSize = 16f; setTextColor(Color.parseColor("#0A1733")) })
        box.addView(ImageView(a).apply { setImageBitmap(qr(codigo)); layoutParams = LinearLayout.LayoutParams((230 * dp).toInt(), (230 * dp).toInt()).apply { topMargin = (10 * dp).toInt() } })
        box.addView(TextView(a).apply { text = codigo; textSize = 11f; setTextIsSelectable(true); setTextColor(Color.parseColor("#696784")); setPadding(0, (8 * dp).toInt(), 0, 0) })
        box.addView(TextView(a).apply {
            text = if (automatico) "O pagamento é confirmado sozinho. Em poucos minutos a parcela aparece como paga e o aparelho é liberado."
                   else "Depois de pagar, envie o comprovante para a loja. Assim que o pagamento for confirmado, o aparelho é liberado automaticamente."
            textSize = 13f; setTextColor(Color.parseColor("#1B1A2E")); setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        AlertDialog.Builder(a).setTitle("Pagar com PIX").setView(box)
            .setPositiveButton("Copiar código PIX") { _, _ ->
                a.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("PIX", codigo))
                Toast.makeText(a, "Código PIX copiado. Cole no app do seu banco.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Fechar", null).show()
    }

    /** Monta os cartões das faturas dentro de um LinearLayout. */
    fun desenhar(a: Activity, alvo: LinearLayout, d: JSONObject) {
        alvo.removeAllViews()
        val dp = a.resources.displayMetrics.density
        val num = d.optLong("vendaNumero")
        for (f in lista(d)) {
            val card = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card_claro)
                setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (10 * dp).toInt() }
            }
            val (rotulo, cor) = when {
                f.pago -> "Paga" to "#0E8A5F"
                f.diasAtraso > 0 -> "Atrasada há ${f.diasAtraso} dia(s)" to "#C23B3B"
                f.diasParaVencer == 0L -> "Vence hoje" to "#A86A12"
                else -> "Vence em ${f.diasParaVencer} dia(s)" to "#1A5CFF"
            }
            card.addView(TextView(a).apply { text = "Parcela ${f.n} de ${f.total}  ·  $rotulo"; textSize = 13f; setTextColor(Color.parseColor(cor)) })
            card.addView(TextView(a).apply { text = "Vencimento ${f.venc.format(fmt)}"; textSize = 14f; setTextColor(Color.parseColor("#696784")) })
            card.addView(TextView(a).apply {
                text = when {
                    f.pago -> brl.format(f.valor)
                    f.desconto > 0 -> "${brl.format(f.aPagar)}  (com ${brl.format(f.desconto)} de desconto pagando antes do vencimento)"
                    f.encargos > 0 -> "${brl.format(f.aPagar)}  (inclui ${brl.format(f.encargos)} de multa e juros)"
                    else -> brl.format(f.aPagar)
                }
                textSize = 18f; setTextColor(Color.parseColor("#1B1A2E")); setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (!f.pago) card.addView(Button(a).apply {
                text = "Pagar com PIX"; isAllCaps = false; setTextColor(Color.WHITE); setBackgroundResource(R.drawable.btn_azul)
                layoutParams = LinearLayout.LayoutParams(-1, (46 * dp).toInt()).apply { topMargin = (10 * dp).toInt() }
                setOnClickListener { mostrarPix(a, d, f.aPagar, "Parcela ${f.n}/${f.total} · vencimento ${f.venc.format(fmt)}", "NXC${num}P${f.n}", f.n) }
            })
            alvo.addView(card)
        }
    }
}
