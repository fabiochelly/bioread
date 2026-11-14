#!/bin/sh
# Construit libbergamot.so (traduction hors-ligne, moteur de Firefox Translations) pour l'app :
#   tools/build_bergamot.sh            # arm64-v8a (appareils) et x86_64 (émulateur)
# Sources : https://github.com/browsermt/bergamot-translator, cloné avec ses sous-modules dans
# dict-out/bergamot. Il faut le NDK et CMake du SDK Android. Les bibliothèques produites vont
# dans app/src/main/jniLibs/<abi>/ (versionnées : l'app se construit sans ce script).
set -e
cd "$(dirname "$0")/.."
ROOT=$PWD
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
NDK=${ANDROID_NDK:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}
CMAKE=$(ls -d "$SDK"/cmake/*/bin | sort -V | tail -1)
SRC=$ROOT/dict-out/bergamot
[ -d "$SRC/3rd_party/marian-dev/src" ] || git clone --recursive https://github.com/browsermt/bergamot-translator.git "$SRC"
# SentencePiece (code d'entraînement, lié par Marian) : les clang récents refusent ce constexpr.
sed -i 's/^  constexpr unicode_script::ScriptType kAnyType =/  const unicode_script::ScriptType kAnyType =/' \
  "$SRC/3rd_party/marian-dev/src/3rd_party/sentencepiece/src/trainer_interface.cc"
# faiss (x86) : intrinsèques SSE employés sans leur en-tête, qu'Android n'inclut pas par ricochet.
F="$SRC/3rd_party/marian-dev/src/3rd_party/faiss/VectorTransform.cpp"
grep -q "immintrin" "$F" || sed -i 's/^#ifdef __SSE__$/#ifdef __SSE__\n#include <immintrin.h>/' "$F"
for ABI in ${ABIS:-arm64-v8a x86_64}; do
  B=$ROOT/dict-out/bergamot-build/$ABI
  # Jeu d'instructions de base : ruy (ARM) et intgemm (x86) choisissent à l'exécution les plus rapides.
  # Calcul flottant : ruy (sur ARM, Marian le choisit seul ; sur x86, sans MKL, il faut le demander).
  case $ABI in arm64-v8a) ARCH=armv8-a; EXTRA= ;; x86_64) ARCH=x86-64; EXTRA="-DUSE_RUY=ON -DUSE_RUY_SGEMM=ON" ;; esac
  "$CMAKE/cmake" -S tools/bergamot -B "$B" -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$ABI \
    -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release \
    -DBERGAMOT_SRC="$SRC" -DBUILD_ARCH=$ARCH $EXTRA
  "$CMAKE/cmake" --build "$B" --target bioread_bergamot -j$(nproc)
  mkdir -p app/src/main/jniLibs/$ABI
  cp "$B/libbergamot.so" app/src/main/jniLibs/$ABI/
  ls -la app/src/main/jniLibs/$ABI/libbergamot.so
done
