#!/usr/bin/env bash
# Build Hero Tran Launcher (vn.herotran.launcher) - KHONG CAN Gradle, KHONG CAN mang.
# Chay duoc:
#   - Windows (Git Bash) : ./build.sh
#   - macOS / Linux     : ./build.sh
#   - GitHub Actions (ubuntu-latest) : workflow .github/workflows/build-apk.yml goi file nay
# Ket qua: build/tvlauncher.apk (da ky)
set -euo pipefail

# ---------- 1. PROJECT_DIR ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -n "${PROJECT_DIR:-}" ]; then
  :
elif [ -f "$SCRIPT_DIR/AndroidManifest.xml" ]; then
  PROJECT_DIR="$SCRIPT_DIR"
elif [ -f "$PWD/AndroidManifest.xml" ]; then
  PROJECT_DIR="$PWD"
else
  for cand in "$SCRIPT_DIR"/"hero Tran_launcher source code" "$PWD"/"hero Tran_launcher source code" "$SCRIPT_DIR"/hero-tran-launcher; do
    if [ -f "$cand/AndroidManifest.xml" ]; then PROJECT_DIR="$cand"; break; fi
  done
fi
if [ -z "${PROJECT_DIR:-}" ] || [ ! -f "$PROJECT_DIR/AndroidManifest.xml" ]; then
  echo "❌ Khong tim thay AndroidManifest.xml (PROJECT_DIR=$PROJECT_DIR)"
  exit 1
fi
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
cd "$PROJECT_DIR"
echo "📁 PROJECT_DIR=$PROJECT_DIR"

# ---------- 2. Android SDK ----------
if [ -n "${ANDROID_HOME:-}" ]; then
  SDK="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  SDK="$ANDROID_SDK_ROOT"
elif [ -n "${ANDROID_SDK:-}" ]; then
  SDK="$ANDROID_SDK"
elif [ -d "/usr/local/lib/android/sdk" ]; then
  # GitHub Actions ubuntu-latest: SDK cai san o day
  SDK="/usr/local/lib/android/sdk"
elif [ -d "$HOME/Android/Sdk" ]; then
  SDK="$HOME/Android/Sdk"
elif [ -d "$HOME/Library/Android/sdk" ]; then
  SDK="$HOME/Library/Android/sdk"
elif [ -d "/opt/android-sdk" ]; then
  SDK="/opt/android-sdk"
elif [ -d "/usr/lib/android-sdk" ]; then
  SDK="/usr/lib/android-sdk"
elif [ -d "/c/Users/TRANHUNGIT/AppData/Local/Android/Sdk" ]; then
  SDK="/c/Users/TRANHUNGIT/AppData/Local/Android/Sdk"
else
  echo "❌ Khong tim thay Android SDK. Hay dat ANDROID_HOME."
  exit 1
fi
echo "📦 SDK=$SDK"

if [ -n "${BUILD_TOOLS_VERSION:-}" ]; then
  BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
elif [ -d "$SDK/build-tools/35.0.0" ]; then
  BT="$SDK/build-tools/35.0.0"
else
  LATEST_BT="$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V | tail -n 1 || true)"
  if [ -z "$LATEST_BT" ]; then echo "❌ Khong thay build-tools trong $SDK/build-tools"; exit 1; fi
  BT="$SDK/build-tools/$LATEST_BT"
fi
if [ -n "${PLATFORM_VERSION:-}" ]; then
  PLAT="$SDK/platforms/android-$PLATFORM_VERSION/android.jar"
elif [ -f "$SDK/platforms/android-35/android.jar" ]; then
  PLAT="$SDK/platforms/android-35/android.jar"
else
  LATEST_PLAT="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -n 1 || true)"
  if [ -z "$LATEST_PLAT" ]; then echo "❌ Khong thay platforms/android-* trong $SDK"; exit 1; fi
  PLAT="$LATEST_PLAT/android.jar"
fi
if [ ! -f "$PLAT" ]; then echo "❌ Khong thay $PLAT"; exit 1; fi
echo "🔧 Build-tools: $BT"
echo "🔧 Platform jar: $PLAT"

EXE=""
case "$(uname -s 2>/dev/null || echo Linux)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac
AAPT2="$BT/aapt2$EXE"
ZIPALIGN="$BT/zipalign$EXE"

if command -v python3 >/dev/null 2>&1; then PY=python3;
elif command -v python >/dev/null 2>&1; then PY=python;
else echo "❌ Khong thay python3"; exit 1; fi

# ---------- 3. Keystore ----------
ALIAS="${KEY_ALIAS:-vnhero}"
PASS="${KEYSTORE_PASSWORD:-${KEYSTORE_PASS:-android}}"
KEY_PASS="${KEY_PASSWORD:-$PASS}"
KEYSTORE=""
if [ -n "${KEYSTORE_BASE64:-}" ]; then
  mkdir -p build
  # base64 decode tuong thich Linux (-d) / macOS (-D) / --decode
  if ! echo "$KEYSTORE_BASE64" | base64 -d > build/release.jks 2>/dev/null; then
    echo "$KEYSTORE_BASE64" | base64 --decode > build/release.jks
  fi
  KEYSTORE="$PROJECT_DIR/build/release.jks"
  echo "🔑 Dung keystore tu KEYSTORE_BASE64 (Secrets)"
elif [ -n "${KEYSTORE_FILE:-}" ] && [ -f "$KEYSTORE_FILE" ]; then
  KEYSTORE="$KEYSTORE_FILE"
  echo "🔑 Dung keystore tu KEYSTORE_FILE=$KEYSTORE"
elif [ -f "$PROJECT_DIR/vn-hero-sign.jks" ]; then
  KEYSTORE="$PROJECT_DIR/vn-hero-sign.jks"
  echo "🔑 Dung keystore trong repo: $KEYSTORE"
elif [ -f "$PROJECT_DIR/../vn-hero-sign.jks" ]; then
  KEYSTORE="$PROJECT_DIR/../vn-hero-sign.jks"
  echo "🔑 Dung keystore (legacy ../): $KEYSTORE"
else
  echo "⚠️ Khong thay keystore, tu tao debug keystore tam thoi..."
  mkdir -p build
  KEYSTORE="$PROJECT_DIR/build/debug.jks"
  if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
      -storepass "$PASS" -keypass "$KEY_PASS" -d "CN=Hero Tran, OU=Launcher, O=HeroTran, C=VN"
  fi
fi
echo "🔑 Alias=$ALIAS"

# ---------- 4. Build ----------
OUT=build
mkdir -p "$OUT/classes"
rm -rf "$OUT/classes" "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/aligned.apk" "$OUT/tvlauncher.apk" "$OUT/hero-tran-launcher.apk"
mkdir -p "$OUT/classes"

LINK_RES=""
if [ -d res ]; then
  mkdir -p "$OUT/compiled"
  "$AAPT2" compile --dir res -o "$OUT/compiled/res.zip"
  LINK_RES="$OUT/compiled/res.zip"
fi
"$AAPT2" link -o "$OUT/base.apk" -I "$PLAT" --manifest AndroidManifest.xml $LINK_RES
echo "✅ aapt2 link xong"


# ---------- 3b. AUTO-FIX: tu sua escape \d \s \. trong MarketService.java ----------
# Ly do: paste tu chat / GitHub web hay bi nuot mat 1 dau \  ->  "\d" thanh "\d" (1 gach)
# javac bao illegal escape. Fixer nay chay moi lan build, nen du file tren repo
# van con 1 gach thi build van xanh. Neu file da dung 2 gach thi khong doi gi.
"$PY" <<'FIXEOF'
import re, pathlib
# Escape hop le trong Java, KHONG duoc gap doi: \n \t \r \f \" \' \\ \uXXXX
# Con lai (\d \s \. \- \/ \( \[ ...) deu la regex -> phai gap doi thanh \\d \\s \\.
pat = re.compile(r'(?<!\\)\\(?![nrtf"\'\\u])')
total = 0
for p in pathlib.Path("src").rglob("*.java"):
    txt = p.read_text(encoding="utf-8", errors="ignore")
    new, n = pat.subn(lambda m: chr(92) + chr(92), txt)
    if n:
        print(f"FIX {p}: sua {n} escape 1-gach -> 2-gach")
        p.write_text(new, encoding="utf-8")
        total += n
if total:
    print(f"AUTO-FIX: da sua {total} cho")
else:
    print("AUTO-FIX: khong co escape 1-gach can sua")
FIXEOF

find src -name "*.java" > "$OUT/sources.txt"
if ! javac --release 8 -Xlint:-options -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources.txt"; then
  echo "⚠️ javac --release 8 that bai, thu lai voi --release 17..."
  javac --release 17 -Xlint:-options -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources.txt"
fi
echo "✅ javac xong"

if [ -x "$BT/d8$EXE" ]; then
  "$BT/d8$EXE" --release --min-api 30 --lib "$PLAT" --output "$OUT" $(find "$OUT/classes" -name "*.class")
elif [ -f "$BT/lib/d8.jar" ]; then
  java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 30 --lib "$PLAT" \
    --output "$OUT" $(find "$OUT/classes" -name "*.class")
else
  echo "❌ Khong thay d8 trong $BT"; exit 1
fi
echo "✅ d8 xong"

"$PY" - "$OUT" <<'PYEOF'
import sys, zipfile, os
out = sys.argv[1]
src = os.path.join(out, "base.apk")
dst = os.path.join(out, "unsigned.apk")
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(os.path.join(out, "classes.dex"), "classes.dex")
PYEOF
echo "✅ ghep dex xong"

"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ -x "$BT/apksigner$EXE" ]; then
  "$BT/apksigner$EXE" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$PASS" --key-pass "pass:$KEY_PASS" --out "$OUT/tvlauncher.apk" "$OUT/aligned.apk"
  "$BT/apksigner$EXE" verify "$OUT/tvlauncher.apk"
elif [ -f "$BT/lib/apksigner.jar" ]; then
  java -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-key-alias "$ALIAS" \
    --ks-pass "pass:$PASS" --key-pass "pass:$KEY_PASS" --out "$OUT/tvlauncher.apk" "$OUT/aligned.apk"
  java -jar "$BT/lib/apksigner.jar" verify "$OUT/tvlauncher.apk"
else
  echo "❌ Khong thay apksigner trong $BT"; exit 1
fi

cp -f "$OUT/tvlauncher.apk" "$OUT/hero-tran-launcher.apk"
echo ""
echo "🎉 Built: $PROJECT_DIR/$OUT/tvlauncher.apk"
ls -lh "$OUT/tvlauncher.apk" "$OUT/hero-tran-launcher.apk"
