#!/usr/bin/env bash
# Press remote keys and screenshot after each step, then build one montage.
# Usage: ./keys.sh name:KEY,KEY name2:KEY ...   (KEY without the KEYCODE_ prefix)
cd "$(dirname "$0")"
export ANDROID_SERIAL="${TV_HOST:?set TV_HOST to the TV IP, e.g. TV_HOST=192.168.1.50}:5555"
rm -f shots/step-*.png
i=0
for step in "$@"; do
  name=${step%%:*}
  IFS=, read -ra ks <<< "${step#*:}"
  for key in "${ks[@]}"; do adb shell input keyevent "KEYCODE_$key"; sleep 0.7; done
  sleep 1
  adb exec-out screencap -p > "shots/step-$(printf %02d $i)-$name.png"
  i=$((i+1))
done
python3 - <<'EOF'
from PIL import Image, ImageDraw; import glob
fs = sorted(glob.glob('shots/step-*.png')); W, H = 640, 360
m = Image.new('RGB', (W*2, H*((len(fs)+1)//2)), 'white')
for i, f in enumerate(fs):
    try: im = Image.open(f).convert('RGB').resize((W, H))
    except Exception: im = Image.new('RGB', (W, H), 'gray')  # 0-byte shot: Live TV/HDMI
    ImageDraw.Draw(im).text((10, 340), f, fill='yellow'); m.paste(im, ((i%2)*W, (i//2)*H))
m.save('shots/montage.jpg', quality=70)
EOF
echo shots/montage.jpg
