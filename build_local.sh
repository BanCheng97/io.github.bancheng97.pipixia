#!/bin/bash
# 手机上直接出 APK（不依赖 gradle / Android Studio）
# 依赖：openjdk-17、aapt(aapt1)、/root/android/{android.jar,d8.jar,apksigner.jar}、xposed api-82.jar
set -e
cd "$(dirname "$0")"
ANDROID_JAR=/root/android/android.jar
D8=/root/android/d8.jar
SIGNER=/root/android/apksigner.jar
XPOSED_JAR=/root/android/dl/api-82.jar
VERSION=6.2
OUT=pipixia-adfree-$VERSION.apk

rm -rf build/classes build/dex build/base.apk build/$OUT
mkdir -p build/classes build/dex

echo "[1/5] javac"
javac -encoding UTF-8 -nowarn -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" -cp "$XPOSED_JAR" \
  -d build/classes $(find app/src/main/java -name '*.java')

echo "[2/5] d8"
java -Xmx1g -cp "$D8" com.android.tools.r8.D8 --release --min-api 21 \
  --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')

echo "[3/5] aapt package"
aapt package -f -M app/src/main/AndroidManifest.xml -S app/src/main/res -A app/src/main/assets \
  -I "$ANDROID_JAR" -F build/base.apk --min-sdk-version 21 --target-sdk-version 28

echo "[4/5] 塞 classes.dex"
(cd build/dex && aapt add ../../build/base.apk classes.dex)

echo "[5/5] 签名"
[ -f build/ks.jks ] || keytool -genkeypair -keystore build/ks.jks -storepass 123456 -keypass 123456 \
  -alias pxxhook -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=HalfCity Pipixia Hook, O=Local, C=CN"
java -jar "$SIGNER" sign --ks build/ks.jks --ks-pass pass:123456 --key-pass pass:123456 \
  --min-sdk-version 21 --out build/$OUT build/base.apk
java -jar "$SIGNER" verify build/$OUT && echo SIGN_OK
ls -l build/$OUT
