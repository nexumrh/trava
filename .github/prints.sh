set -x
PKG=br.com.nexumcell.trava
mkdir -p prints
exec > >(tee prints/run.txt) 2>&1
adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 5; done
adb shell getprop sys.boot_completed
timeout 180 adb install -r app/build/outputs/apk/debug/app-debug.apk
echo "sem device owner (a trava desligaria o adb)"
prefs() { python3 .github/prefs.py $1 > /tmp/nexumcell.xml; adb push /tmp/nexumcell.xml /data/local/tmp/nexumcell.xml; adb shell am force-stop $PKG;
  adb shell "run-as $PKG mkdir -p shared_prefs"; adb shell "run-as $PKG cp /data/local/tmp/nexumcell.xml shared_prefs/nexumcell.xml"; }
prefs
adb shell svc wifi disable; adb shell svc data disable
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
adb shell am start -n $PKG/.MainActivity
sleep 8
shot() { timeout 30 adb exec-out screencap -p > "prints/$1.png"; ls -la "prints/$1.png"; }
tap() { timeout 40 python3 .github/tap.py "$1"; sleep 3; }
shot 01-inicio
adb shell input swipe 540 1800 540 700 400; sleep 2; shot 02-inicio-baixo
tap "Parcelas"; shot 03-parcelas
adb shell input swipe 540 1800 540 600 400; sleep 2; shot 04-parcelas-lista
tap "Contrato"; shot 05-contrato
tap "Suporte"; shot 06-suporte
tap "Início"; sleep 2; tap "Pagar com PIX"; sleep 3; shot 07-pix
adb shell input keyevent KEYCODE_BACK; sleep 2
tap "Suporte"; tap "Iniciar atendimento"; sleep 4; shot 08-chat
tap "Qual o valor da próxima parcela?"; sleep 3; shot 09-chat-resposta
tap "Preciso de mais prazo"; sleep 3; shot 10-chat-prazo
adb shell input keyevent KEYCODE_BACK; sleep 2
prefs travado
adb shell am start -n $PKG/.MainActivity; sleep 10
shot 11-bloqueado
adb shell input swipe 540 1800 540 700 400; sleep 2; shot 12-bloqueado-baixo
tap "Pedir liberação ou falar com o suporte"; sleep 4; shot 13-chat-bloqueado
tap "Liberar meu celular"; sleep 6; shot 14-chat-liberar
timeout 30 adb logcat -d | grep -iE "AndroidRuntime|nexum" | tail -80 > prints/log.txt
