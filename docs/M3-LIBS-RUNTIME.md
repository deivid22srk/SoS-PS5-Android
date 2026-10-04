# M3 — Contrato: libs no runtime box64/Android (M3-a)

Status: APROVADO como plano técnico (decisão de empacotamento comunicada ao usuário
em 2026-10-05; aceitação final = critérios abaixo verdes em CI + aparelho).

## 1. Decisão de empacotamento (comunicada ao usuário)

- **jniLibs (arm64-v8a)** — **7 no total**: `libbox64.so` (ARM64/bionic, wrapper
  SDL2 compilado — `wrappedsdl2.c` entra em TODA build box64 não-STATIC, sem
  headers SDL2), `libanyhost64.so` (host x86-64, SDL2 **dinâmica**, DT_NEEDED
  `libSDL2-2.0.so.0`), `libpayload64.so` (regressão M1), libs guest x86-64
  `libavcodec_sos.so` / `libavutil_sos.so` / `libfreetype_sos.so` e
  `libSDL2_sos_native.so` (SDL2 nativa ARM64 dummy/offscreen, submodule pinado).
- **Rootfs mínima: 1 arquivo copiado em runtime** (correção da estimativa inicial
  "zero"): `libSDL2_sos_native.so` (ARM64 nativa, dummy/offscreen, construída do
  submodule 3rdparty/SDL2 pinado) → copiado pelo app para
  `<filesDir>/rootfs/lib/libSDL2-2.0.so.0`. Motivo: AGP só empacota `lib*.so`
  (não `*.so.0`) e o wrapper do box64 faz `dlopen("libSDL2-2.0.so.0")` NATIVO —
  o linker bionic precisa do nome exato via `LD_LIBRARY_PATH` (o logcat do
  aparelho confirma `permitted_path=/data/...:/data/user/0/<pkg>`).
- **SDL2 nativa (ARM64) no CI do APK**: options reais do CMake da SDL 2.33 —
  `SDL_X11/SDL_WAYLAND/SDL_KMSDRM/SDL_OPENGL/SDL_OPENGLES/SDL_VULKAN/
  SDL_HIDAPI/SDL_SENSOR/SDL_AUDIO/SDL_RENDER/SDL_TEST/SDL_TESTS/SDL_POWER/
  SDL_LIBUDEV=OFF`, `SDL_JOYSTICK=ON`, `SDL_SHARED=ON/SDL_STATIC=OFF`
  (nomes `SDL_*`, não `VIDEO_*`). **`SDL_HAPTIC=ON` obrigatório**: os stubs JNI
  de haptic em `src/core/android/SDL_android.c` são incondicionais nessa revisão
  e chamam `Android_AddHaptic`/`Android_RemoveHaptic`, que só existem com o
  subsistema haptic habilitado — `SDL_HAPTIC=OFF` NÃO compila no Android.
- **Env do processo filho (app)**: `LD_LIBRARY_PATH=<filesDir>/rootfs/lib`
  (nativo bionic) + `BOX64_LD_LIBRARY_PATH=<nativeLibraryDir>:<filesDir>/rootfs/lib`
  (guest). Guest libs com SONAME controlado (`_sos`) não precisam de cópia.
- **glibc não vai no pacote**: box64 resolve libc/libm/pthread/dl via wrappers
  internos (M1 dinâmico já provado). FFmpeg/freetype configurados mínimos.
- **SDL2 nunca como lib guest** (preferência do usuário): wrapper nativo. Fallback
  documentado (só se wrapper inviável no NDK): SDL2 guest x86-64 dinâmica —
  exigiria parar e perguntar antes.

## 2. Fontes das libs (ABI-alvo x86-64 guest)

| Lib | Fonte | Config mínima | Entrega |
|---|---|---|---|
| SDL2 nativa (ARM64) | submodule `3rdparty/SDL2` @ `4b69833` (2.33.x) | X11/Wayland/KMSDRM/OpenGL/GLES/Vulkan/HIDAPI/sensor OFF; joystick ON; dummy+offscreen ON | compartilhada, nome final `libSDL2-2.0.so.0` |
| FFmpeg (guest x86-64) | upstream `n6.1.2` (tarball oficial — divergência consciente do submodule `ffmpeg-core`, que é estático/embedded; migração p/ vendored no M4) | `--disable-everything --enable-decoder=hevc,h264 --disable-zlib,bzlib,lzma,iconv,network,avdevice,avfilter,avformat,swscale,postproc,swresample,doc,programs --disable-x86asm` + `--prefix=<work>/ffmpeg-install` + `make install` (avconfig.h/ffversion.h só na árvore de build) | compartilhada + `patchelf --set-soname/--replace-needed` → `libavcodec_sos.so`, `libavutil_sos.so` |
| freetype (guest x86-64) | submodule `3rdparty/freetype` @ `42608f7` | `FT_DISABLE_ZLIB/BZIP2/PNG/HARFBUZZ/BROTLI=ON` (idêntico ao root CMake do upstream) | compartilhada → `libfreetype_sos.so` |
| SDL2 de link do host (CI x86-64) | `apt libsdl2-dev` (Ubuntu) | padrão da distro | só p/ link; runtime no aparelho é a nativa acima |
| libstdc++/libgcc_s | **linkadas ESTÁTICAS no anyhost** (`-static-libstdc++ -static-libgcc`) | box64 @abfb8c3 não embriona nenhuma das duas e o APK não as empacota; DT_NEEDED delas no anyhost = FALHA HARD no CI | — |

## 3. Extensão do anyhost (harness M3)

Entre `SOS_HOST_SDL2_OK` e a validação de arquivos do jogo, o anyhost executa as
sondas de libs (idempotentes, sem estado) e emite:

- `SOS_HOST_FREETYPE_OK version=<maj.min.pat>` — `FT_Init_FreeType` +
  `FT_Library_Version` (submodule pinado = **2.13.3**).
- `SOS_HOST_FFMPEG_OK codec=hevc,h264 avf=<av_version_info()>` —
  `avcodec_find_decoder` p/ HEVC e H264 + `avcodec_alloc_context3` +
  `avcodec_open2`/`avcodec_free_context` (open real do decoder; string de
  runtime, ex. `avf=6.1.2`).
- Falha de sonda → `SOS_HOST_M3_FAIL lib=<freetype|ffmpeg> reason=<msg>` +
  `SOS_HOST_INTERNAL_ERROR reason=m3-probe-failed`, **exit 2** (regra de exit codes
  do contrato M2 mantida).
- Build sem as sondas (opção CMake `ANYHOST_M3_PROBES=OFF`, default
  M2-compatível) emite `SOS_HOST_M3_SKIPPED` (marker simples, sem key=value) —
  no APK isso é FALHA (APK desatualizado).
- Markers/verdes do M2 (`SOS_HOST_MISSING_GAME_FILES`, etc.) continuam EXATAMENTE
  iguais; o M2 test do app continua verde com o binário novo (verde M2 é subconjunto).

## 4. Gating `reason≠no-eboot` (proposta → REGRA a partir do M3)

Resolve o achado F7 da revisão M2-e:

1. Os smokes de CI e o teste do app usam pasta de jogo **VAZIA por construção** ⇒
   o único reason aceito como verde no caminho missing-files é
   `reason=no-eboot` (exato).
2. Qualquer outro reason (`not-elf`, `no-sce_sys`, `no-media`, `no-libs`,
   `no-relinker`) com exit 1 = **FALHA HARD no CI** e vermelho no app (reason
   mostrado na tela). Só é alcançável se (a) arquivos apareceram onde não deviam
   (vazamento de dump) ou (b) a ordem de validação mudou sem atualização de
   contrato — ambos exigem investigação antes de re-push.
3. `SOS_HOST_VALIDATE_OK` num smoke de pasta vazia = FALHA HARD (idem 2).
4. Exit 2 (`SOS_HOST_INTERNAL_ERROR`) e exit ≥128 (sinais) = FALHA incondicional.
5. Reasons novos só entram no verde com atualização DESTE contrato (tag [M3]) e
   mudança simultânea em CI + app no mesmo commit.

## 5. KB-001 reavaliação (BOX64_DYNAREC_SAFEFLAGS=2)

- Com SDL2 como wrapper nativo, o código guest que disparava o SIGSEGV (init da
  SDL2 estática guest) deixa de existir. Matriz no job `box64-arm64`:
  **(A)** M3 flow SEM `BOX64_DYNAREC_SAFEFLAGS` (default) — se PASS, o workaround
  é REMOVIDO do build.yml (RUN anyhost) e do app (runM2Test/runM3Test), e KB-001
  vira "reavaliado/cerrado para o cenário M3" em KNOWN_BUGS.md.
  **(B)** se SIGSEGV: rerun COM `SAFEFLAGS=2` (obrigatório PASS) + benchmark
  determinístico (payload guest `bench_dyn`: loop FP+int de ~2-3 s, 3 execuções
  por config, reporta min/mediana e delta %) no job summary — custo medido e
  reportado ao usuário antes de manter.
- O mesmo runtime novo é revalidado no aparelho (botão M3).

## 6. Critérios de aceite do M3

1. CI job `host-linux`: constrói guest libs (`_sos`, via patchelf) + anyhost
   dinâmico; evidência `file`/`ldd` (anyhost dinâmico com DT_NEEDED esperado,
   relinker CONTINUA estático); smoke nativo x86-64 com TODOS os markers
   (SDL2 + FFMPEG + FREETYPE + MISSING_GAME_FILES reason=no-eboot, exit 1).
2. CI job `box64-arm64`: host + guest libs sob box64 (wrapper SDL2 nativo via
   libsdl2 do runner) — TODOS os markers sem `symbol not found`/símbolo ausente;
   matriz KB-001 (item 5); regressão M1 PASS; relinker smoke mantido.
3. CI job `apk`: box64 NDK + SDL2 nativa ARM64 (dummy) + guest libs + anyhost no
   APK; botão M3 no app com veredito verde exigindo os 5 markers + exit 1.
4. Teste on-device (motorola edge 30 fusion, Android 14): botão M3 verde no app,
   logcat com markers e sem Fatal signal.
