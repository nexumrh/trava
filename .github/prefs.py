import json, time
from xml.sax.saxutils import escape
parc = []
meses = ["2026-10-10","2026-11-10","2026-12-10","2027-01-10","2027-02-10","2027-03-10","2027-04-10","2027-05-10","2027-06-10","2027-07-10","2027-08-10","2027-09-10"]
for i, v in enumerate(meses):
    parc.append({"n": i + 1, "venc": v, "valor": 226.67, "pago": i < 2})
d = {"clienteNome": "Wevellen Errera", "marca": "Realme", "modelo": "C71", "vendaNumero": 10,
     "lojaNome": "Zoom Tech Foz", "lojaTelefone": "(45) 99118-7639", "parcelas": parc,
     "descontoPct": 5, "multaPct": 2, "moraDiaPct": 0.033, "financiado": 2000, "nParcelas": 12,
     "maxDiasOffline": 1, "chavePix": "pix@nexumcell.com.br", "nomePix": "NEXUMCELL", "cidadePix": "FOZ DO IGUACU",
     "portal": "https://nexumrh.github.io/cliente/"}
agora = int(time.time() * 1000)
print("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>")
print('    <string name="cacheJson">%s</string>' % escape(json.dumps(d, ensure_ascii=False), {'"': "&quot;"}))
print('    <long name="ultimoSyncOk" value="%d" />' % agora)
print("</map>")
