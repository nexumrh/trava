import subprocess, sys, re, xml.etree.ElementTree as ET
alvo = sys.argv[1]
subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
x = subprocess.run(["adb", "shell", "cat", "/sdcard/ui.xml"], capture_output=True, text=True).stdout
root = ET.fromstring(x[x.index("<"):])
melhor = None
for n in root.iter("node"):
    t = n.get("text", "")
    if t.strip() == alvo or (melhor is None and alvo in t):
        b = list(map(int, re.findall(r"\d+", n.get("bounds"))))
        melhor = ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2)
        if t.strip() == alvo: break
print(alvo, melhor)
if melhor: subprocess.run(["adb", "shell", "input", "tap", str(melhor[0]), str(melhor[1])])
