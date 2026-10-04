#!/usr/bin/env bash
# m3-build-guest-libs.sh — M3 (SoS-PS5-Android): constrói as libs guest x86-64
# com SONAME controlado (_sos) + SDL2 dinâmica x86-64 para verificação LOCAL.
#
# Contrato: sos-ps5-android/docs/M3-LIBS-RUNTIME.md seções 1-3.
#
# Produz no STAGE ($1, default /home/z/my-project/build/m3-stage):
#   libavcodec_sos.so    (FFmpeg n6.1.2 shared, mínima; SONAME patcheado)
#   libavutil_sos.so     (idem; NEEDED do avcodec é re-escrito para cá)
#   libfreetype_sos.so   (submodule upstream 3rdparty/freetype @ 42608f7)
#   libSDL2-2.0.so.0     (submodule 3rdparty/SDL2 @ 4b69833 shared headless;
#                         SONAME original PRESERVADO — sem patchelf)
#   libSDL2-2.0.so       (symlink → libSDL2-2.0.so.0, para o linker)
#
# SDL2 x86-64 aqui é SÓ para link/verificação local; o CI usa apt
# libsdl2-dev e o runtime Android usa a SDL2 nativa ARM64 (wrapper box64).
#
# Uso: m3-build-guest-libs.sh [STAGE] [WORK]
#   STAGE default /home/z/my-project/build/m3-stage
#   WORK  default /home/z/my-project/build/m3-work
# Requer: cmake, ninja, g++, git, curl|wget, tar, xz, python3/pip, patchelf.
set -euo pipefail

STAGE="${1:-/home/z/my-project/build/m3-stage}"
WORK="${2:-/home/z/my-project/build/m3-work}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Clone upstream (sobrescreva com SOS_PS5_ROOT se a árvore estiver em outro lugar)
UPSTREAM="${SOS_PS5_ROOT:-$(cd "${SCRIPT_DIR}/../.." && pwd)/SoS-PS5}"

log() { printf '[m3-guest-libs] %s\n' "$*"; }

JOBS="$(nproc)"; [ "${JOBS}" -gt 2 ] && JOBS=2
log "STAGE=${STAGE} WORK=${WORK} UPSTREAM=${UPSTREAM} JOBS=${JOBS}"
mkdir -p "${STAGE}" "${WORK}"

# ---- patchelf (idempotente) -------------------------------------------------
if ! command -v patchelf >/dev/null 2>&1; then
    log "instalando patchelf via pip"
    python3 -m pip install --quiet patchelf 2>/dev/null \
        || python3 -m pip install --quiet --break-system-packages patchelf
fi
PATCHELF="$(command -v patchelf)"
log "patchelf: ${PATCHELF} ($("${PATCHELF}" --version))"

# ---- FFmpeg n6.1.2 shared mínima (libavutil + libavcodec) -------------------
FFMPEG_DIR="${WORK}/ffmpeg-6.1.2"
FFMPEG_BUILD="${WORK}/ffmpeg-build"
if [ ! -f "${FFMPEG_DIR}/configure" ]; then
    log "baixando FFmpeg n6.1.2 (tarball oficial ffmpeg.org)"
    if curl -fL --retry 3 -o "${WORK}/ffmpeg-6.1.2.tar.xz" \
            "https://ffmpeg.org/releases/ffmpeg-6.1.2.tar.xz"; then
        tar -xJf "${WORK}/ffmpeg-6.1.2.tar.xz" -C "${WORK}"
    else
        log "ffmpeg.org falhou — fallback codeload.github.com (tar.gz)"
        curl -fL --retry 3 -o "${WORK}/ffmpeg-6.1.2.tar.gz" \
            "https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n6.1.2"
        rm -rf "${FFMPEG_DIR}"
        tar -xzf "${WORK}/ffmpeg-6.1.2.tar.gz" -C "${WORK}"
    fi
fi
if [ ! -f "${FFMPEG_BUILD}/libavcodec/libavcodec.so.60" ]; then
    log "configurando FFmpeg (shared mínima: avutil+avcodec, hevc+h264)"
    mkdir -p "${FFMPEG_BUILD}"
    (
        cd "${FFMPEG_BUILD}"
        ../ffmpeg-6.1.2/configure \
            --enable-shared --disable-static \
            --disable-everything \
            --enable-decoder=hevc --enable-decoder=h264 \
            --disable-doc --disable-programs \
            --disable-avdevice --disable-avfilter --disable-avformat \
            --disable-swscale --disable-postproc --disable-swresample \
            --disable-network \
            --disable-zlib --disable-bzlib --disable-lzma --disable-iconv \
            --disable-x86asm \
            --prefix="${WORK}/ffmpeg-install"
    )
    make -C "${FFMPEG_BUILD}" -j"${JOBS}"
    # make install: headers consumíveis num ÚNICO include dir (avconfig.h e
    # ffversion.h são GERADOS no build tree — sem install o consumidor precisaria
    # de dois -I). Instala apenas as libs construídas (avutil+avcodec).
    make -C "${FFMPEG_BUILD}" install
fi

log "patchelf FFmpeg → *_sos"
AVUTIL_REAL="$(find "${FFMPEG_BUILD}/libavutil" -maxdepth 1 -name 'libavutil.so.58*' -type f | head -n1)"
AVCODEC_REAL="$(find "${FFMPEG_BUILD}/libavcodec" -maxdepth 1 -name 'libavcodec.so.60*' -type f | head -n1)"
[ -n "${AVUTIL_REAL}" ] || { log "ERRO: libavutil.so.58 não encontrada"; exit 1; }
[ -n "${AVCODEC_REAL}" ] || { log "ERRO: libavcodec.so.60 não encontrada"; exit 1; }
cp "${AVUTIL_REAL}" "${STAGE}/libavutil_sos.so"
cp "${AVCODEC_REAL}" "${STAGE}/libavcodec_sos.so"
"${PATCHELF}" --set-soname libavutil_sos.so "${STAGE}/libavutil_sos.so"
"${PATCHELF}" --set-soname libavcodec_sos.so "${STAGE}/libavcodec_sos.so"
"${PATCHELF}" --replace-needed libavutil.so.58 libavutil_sos.so "${STAGE}/libavcodec_sos.so"

# ---- freetype do submodule upstream (pinado 42608f7) ------------------------
log "inicializando submodule 3rdparty/freetype (pin upstream)"
git -C "${UPSTREAM}" submodule update --init 3rdparty/freetype
FT_BUILD="${WORK}/freetype-build"
if [ ! -f "${FT_BUILD}/libfreetype.so.6" ]; then
    log "configurando freetype shared (todas as dependências externas OFF)"
    cmake -S "${UPSTREAM}/3rdparty/freetype" -B "${FT_BUILD}" -G Ninja \
        -DBUILD_SHARED_LIBS=ON \
        -DFT_DISABLE_ZLIB=ON -DFT_DISABLE_BZIP2=ON -DFT_DISABLE_PNG=ON \
        -DFT_DISABLE_HARFBUZZ=ON -DFT_DISABLE_BROTLI=ON \
        -DCMAKE_BUILD_TYPE=Release
    ninja -C "${FT_BUILD}"
fi
FT_REAL="$(find "${FT_BUILD}" -maxdepth 1 -name 'libfreetype.so.6*' -type f | head -n1)"
[ -n "${FT_REAL}" ] || { log "ERRO: libfreetype.so.6 não encontrada"; exit 1; }
cp "${FT_REAL}" "${STAGE}/libfreetype_sos.so"
"${PATCHELF}" --set-soname libfreetype_sos.so "${STAGE}/libfreetype_sos.so"

# ---- SDL2 shared x86-64 (SÓ link/verificação local; CI usa libsdl2-dev) -----
# Nomes de opção conferidos no CMakeLists.txt de 3rdparty/SDL2 @ 4b69833
# (2.33.0): subsistemas = option(SDL_<SUB>) gerado por SDL_SUBSYSTEMS
# (SDL_AUDIO, SDL_RENDER, SDL_JOYSTICK, SDL_HAPTIC, SDL_HIDAPI, SDL_SENSOR,
# SDL_POWER, ...); drivers de vídeo = SDL_X11/SDL_WAYLAND/SDL_KMSDRM/
# SDL_OPENGL/SDL_OPENGLES/SDL_VULKAN (dep_option)/SDL_OFFSCREEN/SDL_DUMMYVIDEO;
# biblioteca de teste = SDL_TEST (não "SDL_TEST_LIBRARY").
log "inicializando submodule 3rdparty/SDL2 (idempotente)"
git -C "${UPSTREAM}" submodule update --init 3rdparty/SDL2
SDL_BUILD="${WORK}/sdl2-build"
if [ ! -f "${SDL_BUILD}/libSDL2-2.0.so.0" ]; then
    log "configurando SDL2 shared headless (dummy+offscreen, joystick, resto OFF)"
    cmake -S "${UPSTREAM}/3rdparty/SDL2" -B "${SDL_BUILD}" -G Ninja \
        -DSDL_SHARED=ON -DSDL_STATIC=OFF \
        -DSDL_TEST=OFF -DSDL_TESTS=OFF \
        -DSDL_X11=OFF -DSDL_WAYLAND=OFF -DSDL_KMSDRM=OFF \
        -DSDL_OPENGL=OFF -DSDL_OPENGLES=OFF -DSDL_VULKAN=OFF \
        -DSDL_OFFSCREEN=ON -DSDL_DUMMYVIDEO=ON \
        -DSDL_JOYSTICK=ON -DSDL_HAPTIC=ON -DSDL_HIDAPI=OFF \
        -DSDL_SENSOR=OFF -DSDL_AUDIO=OFF -DSDL_RENDER=OFF \
        -DSDL_POWER=OFF \
        -DSDL_LIBUDEV=OFF \
        -DCMAKE_BUILD_TYPE=Release
    ninja -C "${SDL_BUILD}"
fi
SDL_REAL="$(find "${SDL_BUILD}" -maxdepth 1 -name 'libSDL2-2.0.so.0*' -type f | head -n1)"
[ -n "${SDL_REAL}" ] || { log "ERRO: libSDL2-2.0.so.0 não encontrada"; exit 1; }
# SONAME original PRESERVADO (sem patchelf): o DT_NEEDED do host é
# libSDL2-2.0.so.0 exatamente como no apt libsdl2-dev do CI.
cp "${SDL_REAL}" "${STAGE}/libSDL2-2.0.so.0"
ln -sfn libSDL2-2.0.so.0 "${STAGE}/libSDL2-2.0.so"

# ---- Evidência final --------------------------------------------------------
log "STAGE final:"
ls -la "${STAGE}"
file "${STAGE}"/*
log "OK"
