set -x
PKG=br.com.nexumcell.trava
mkdir -p prints
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner $PKG/.AdminReceiver || true
python3 .github/prefs.py > /tmp/nexumcell.xml
adb push /tmp/nexumcell.xml /data/local/tmp/nexumcell.xml
adb shell am force-stop $PKG
adb shell "run-as $PKG mkdir -p shared_prefs"
adb shell "run-as $PKG cp /data/local/tmp/nexumcell.xml shared_prefs/nexumcell.xml"
adb shell "run-as $PKG ls -la shared_prefs"
adb shell settings put system screen_off_timeout 1800000
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard || true
adb shell am start -n $PKG/.MainActivity
sleep 8
shot() { adb exec-out screencap -p > "prints/$1.png"; }
tap() { python3 .github/tap.py "$1"; sleep 3; }
shot 01-inicio
adb shell input swipe 540 1800 540 700 400; sleep 2; shot 02-inicio-baixo
tap "Parcelas"; shot 03-parcelas
adb shell input swipe 540 1800 540 600 400; sleep 2; shot 04-parcelas-lista
adb shell input swipe 540 1800 540 600 400; sleep 2; shot 05-parcelas-fim
tap "Contrato"; shot 06-contrato
tap "Suporte"; shot 07-suporte
adb shell input swipe 540 1800 540 700 400; sleep 2; tap "Posso pagar antes do vencimento?"; shot 08-suporte-faq
tap "Início"; tap "Pagar com PIX"; sleep 2; shot 09-pix
adb shell input keyevent KEYCODE_BACK; sleep 2
adb shell am start -n $PKG/.SuporteActivity; sleep 4; shot 10-chat
tap "Qual o valor da próxima parcela?"; sleep 2; shot 11-chat-resposta
tap "Meu celular bloqueou, e agora?"; sleep 2; shot 12-chat-bloqueio
adb logcat -d | grep -iE "AndroidRuntime|nexum" | tail -60 > prints/log.txt
