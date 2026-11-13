#!/usr/bin/env bash
# Build Hero Tran Launcher (vn.herotran.launcher) - KHONG CAN Gradle, KHONG CAN mang.
# Chay duoc ca 3 noi:
#   - Windows (Git Bash) : ./build.sh
#   - macOS / Linux     : ./build.sh
#   - GitHub Actions (ubuntu-latest) : workflow .github/workflows/build-apk.yml goi file nay
# Ket qua: build/tvlauncher.apk (da ky)
#
# Bien moi truong tuy chon (workflow se truyen vao, local khong can):
#   PROJECT_DIR          : thu muc chua AndroidManifest.xml (mac dinh: thu muc chua build.sh)
#   ANDROID_HOME / ANDROID_SDK_ROOT : duong dan Android SDK (tren Actions tu dong co)
#   BUILD_TOOLS_VERSION  : vd 35.0.0 (mac dinh: tu dong lay ban moi nhat da cai)
#   PLATFORM_VERSION     : vd 35 (mac dinh: tu dong lay ban moi nhat da cai)
#   KEYSTORE_BASE64      : keystore dang base64 (uu tien nhat, dung cho Secrets tren GitHub)
#   KEYSTORE_FILE        : duong dan file .jks cu the
#   KEYSTORE_PASSWORD / KEYSTORE_PASS : mat khau keystore (mac dinh: android - cua file vn-hero-sign.jks di kem)
#   KEY_PASSWORD         : mat khau key (mac dinh: = KEYSTORE_PASSWORD)
#   KEY_ALIAS            : alias (mac dinh: vnhero)
set -euo pipefail

# ---------- 1. Xac dinh PROJECT_DIR ----------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -n "${PROJECT_DIR:-}" ]; then
  : # giu nguyen PROJECT_DIR do workflow / nguoi dung truyen vao
elif [ -f "$SCRIPT_DIR/AndroidManifest.xml" ]; then
  PROJECT_DIR="$SCRIPT_DIR"
elif [ -f "$PWD/AndroidManifest.xml" ]; then
  PROJECT_DIR="$PWD"
else
  # Fallback: truong hop push ca thu muc cu "hero Tran_launcher source code/" len GitHub
  for cand in "$SCRIPT_DIR"/"hero Tran_launcher source code" "$PWD"/"hero Tran_launcher source code" "$SCRIPT_DIR"/hero-tran-launcher; do
    if [ -f "$cand/AndroidManifest.xml" ]; then PROJECT_DIR="$cand"; break; fi
  done
fi
if [ -z "${PROJECT_DIR:-}" ] || [ ! -f "$PROJECT_DIR/AndroidManifest.xml" ]; then
  echo "❌ Khong tim thay AndroidManifest.xml"
  echo "   PROJECT_DIR=$PROJECT_DIR"
  echo "   Hay chay ./build.sh o thu muc goc chua AndroidManifest.xml"
  exit 1
fi
# Chuyen sang duong dan tuyet doi de KEYSTORE / build/ khong bi sai khi PROJECT_DIR la duong dan tuong doi
PROJECT_DIR="$(cd "$PROJECT_DIR" && pwd)"
cd "$PROJECT_DIR"
echo "📁 PROJECT_DIR=$PROJECT_DIR"

# ---------- 2. Xac dinh Android SDK ----------
if [ -n "${ANDROID_HOME:-}" ]; then
  SDK="$ANDROID_HOME"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  SDK="$ANDROID_SDK_ROOT"
elif [ -n "${ANDROID_SDK:-}" ]; then
  SDK="$ANDROID_SDK"
elif [ -d "$HOME/Android/Sdk" ]; then
  SDK="$HOME/Android/Sdk"
elif [ -d "$HOME/Library/Android/sdk" ]; then
  SDK="$HOME/Library/Android/sdk"
elif [ -d "/opt/android-sdk" ]; then
  SDK="/opt/android-sdk"
elif [ -d "/usr/lib/android-sdk" ]; then
  SDK="/usr/lib/android-sdk"
elif [ -d "/c/Users/TRANHUNGIT/AppData/Local/Android/Sdk" ]; then
  # Giu lai duong dan cu tren may Windows cua tac gia de chay local van duoc
  SDK="/c/Users/TRANHUNGIT/AppData/Local/Android/Sdk"
else
  echo "❌ Khong tim thay Android SDK. Hay dat bien ANDROID_HOME hoac cai SDK."
  echo "   Tren GitHub Actions, workflow se tu cai SDK bang android-actions/setup-android."
  exit 1
fi
echo "📦 SDK=$SDK"

# Build-tools: uu tien bien moi truong, roi 35.0.0, roi ban moi nhat da cai
if [ -n "${BUILD_TOOLS_VERSION:-}" ]; then
  BT="$SDK/build-tools/$BUILD_TOOLS_VERSION"
else
  if [ -d "$SDK/build-tools/35.0.0" ]; then
    BT="$SDK/build-tools/35.0.0"
  else
    # Lay ban moi nhat (sort -V)
    LATEST_BT="$(ls -1 "$SDK/build-tools" 2>/dev/null | sort -V | tail -n 1 || true)"
    if [ -z "$LATEST_BT" ]; then echo "❌ Khong thay build-tools trong $SDK/build-tools"; exit 1; fi
    BT="$SDK/build-tools/$LATEST_BT"
  fi
fi
# Platform: uu tien 35, roi ban moi nhat
if [ -n "${PLATFORM_VERSION:-}" ]; then
  PLAT="$SDK/platforms/android-$PLATFORM_VERSION/android.jar"
else
  if [ -f "$SDK/platforms/android-35/android.jar" ]; then
    PLAT="$SDK/platforms/android-35/android.jar"
  else
    LATEST_PLAT="$(ls -d "$SDK"/platforms/android-* 2>/dev/null | sort -V | tail -n 1 || true)"
    if [ -z "$LATEST_PLAT" ]; then echo "❌ Khong thay platforms/android-* trong $SDK"; exit 1; fi
    PLAT="$LATEST_PLAT/android.jar"
  fi
fi
if [ ! -f "$PLAT" ]; then echo "❌ Khong thay $PLAT"; exit 1; fi
echo "🔧 Build-tools: $BT"
echo "🔧 Platform jar: $PLAT"

# Hau to .exe chi co tren Windows (Git Bash / MSYS / Cygwin)
EXE=""
case "$(uname -s 2>/dev/null || echo Linux)" in
  MINGW*|MSYS*|CYGWIN*) EXE=".exe" ;;
esac
AAPT2="$BT/aapt2$EXE"
ZIPALIGN="$BT/zipalign$EXE"

# Python: uu tien python3 (tren ubuntu-latest, 'python' co the khong co)
if command -v python3 >/dev/null 2>&1; then PY=python3;
elif command -v python >/dev/null 2>&1; then PY=python;
else echo "❌ Khong thay python3"; exit 1; fi

# ---------- 3. Keystore / Signing ----------
ALIAS="${KEY_ALIAS:-vnhero}"
PASS="${KEYSTORE_PASSWORD:-${KEYSTORE_PASS:-android}}"
KEY_PASS="${KEY_PASSWORD:-$PASS}"
KEYSTORE=""

if [ -n "${KEYSTORE_BASE64:-}" ]; then
  # Uu tien 1: Secrets tren GitHub (ANDROID_KEYSTORE_BASE64)
  mkdir -p build
  echo "$KEYSTORE_BASE64" | base64 --decode > build/release.jks 2>/dev/null || echo "$KEYSTORE_BASE64" | base64 -d > build/release.jks
  KEYSTORE="$PROJECT_DIR/build/release.jks"
  echo "🔑 Dung keystore tu KEYSTORE_BASE64 (Secrets)"
elif [ -n "${KEYSTORE_FILE:-}" ] && [ -f "$KEYSTORE_FILE" ]; then
  KEYSTORE="$KEYSTORE_FILE"
  echo "🔑 Dung keystore tu KEYSTORE_FILE=$KEYSTORE"
elif [ -f "$PROJECT_DIR/vn-hero-sign.jks" ]; then
  # Uu tien 2: file di kem trong repo (de chay ngay khong can Secrets)
  KEYSTORE="$PROJECT_DIR/vn-hero-sign.jks"
  echo "🔑 Dung keystore trong repo: $KEYSTORE"
elif [ -f "$PROJECT_DIR/../vn-hero-sign.jks" ]; then
  # Tuong thich nguoc voi build.sh cu (no de ../vn-hero-sign.jks)
  KEYSTORE="$PROJECT_DIR/../vn-hero-sign.jks"
  echo "🔑 Dung keystore (legacy ../): $KEYSTORE"
else
  # Uu tien 3: tu tao debug keystore de van build duoc APK cai dat
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

# 4.1. Bien dich resource + link voi manifest
LINK_RES=""
if [ -d res ]; then
  mkdir -p "$OUT/compiled"
  "$AAPT2" compile --dir res -o "$OUT/compiled/res.zip"
  LINK_RES="$OUT/compiled/res.zip"
fi
"$AAPT2" link -o "$OUT/base.apk" -I "$PLAT" --manifest AndroidManifest.xml $LINK_RES
echo "✅ aapt2 link xong"

# 4.2. Bien dich Java (khong can Gradle, khong can R.java - code dung getIdentifier + android.R)
find src -name "*.java" > "$OUT/sources.txt"
# JDK 17 tren Actions chay tot voi --release 8. Neu JDK moi hon khong ho tro 8 thi fallback sang 11.
if ! javac --release 8 -Xlint:-options -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources.txt"; then
  echo "⚠️ javac --release 8 that bai, thu lai voi --release 11..."
  javac --release 11 -Xlint:-options -cp "$PLAT" -d "$OUT/classes" @"$OUT/sources.txt"
fi
echo "✅ javac xong"

# 4.3. Dong goi bytecode thanh classes.dex (uu tien binary d8, fallback d8.jar)
if [ -x "$BT/d8$EXE" ]; then
  "$BT/d8$EXE" --release --min-api 30 --lib "$PLAT" --output "$OUT" $(find "$OUT/classes" -name "*.class")
elif [ -f "$BT/lib/d8.jar" ]; then
  java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 30 --lib "$PLAT" \
    --output "$OUT" $(find "$OUT/classes" -name "*.class")
else
  echo "❌ Khong thay d8 trong $BT"; exit 1
fi
echo "✅ d8 xong"

# 4.4. Nhung classes.dex vao APK
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

# 4.5. Canh le va ky
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

# Giu them 1 ban ten de hieu khi tai ve tu Actions
cp -f "$OUT/tvlauncher.apk" "$OUT/hero-tran-launcher.apk"

echo ""
echo "🎉 Built: $PROJECT_DIR/$OUT/tvlauncher.apk"
ls -lh "$OUT/tvlauncher.apk" "$OUT/hero-tran-launcher.apk"
