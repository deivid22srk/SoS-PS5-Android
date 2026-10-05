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
- **Rootfs mínima: 4 arquivos copiados em runtime** (atualizado pelo M3 fix #1,
  2026-10-05 — ver §7; estimativa inicial "1 arquivo" superada):
  `libSDL2_sos_native.so` (ARM64 nativa, dummy/offscreen, construída do
  submodule 3rdparty/SDL2 pinado) → copiado pelo app para
  `<filesDir>/rootfs/lib/libSDL2-2.0.so.0`, MAIS a glibc guest x86-64
  (`libc.so.6`, `ld-linux-x86-64.so.2`, `libm.so.6`) extraída de
  `assets/rootfs` para o MESMO diretório. Motivo (SDL2): AGP só empacota
  `lib*.so` (não `*.so.0`) e o wrapper do box64 faz `dlopen("libSDL2-2.0.so.0")`
  NATIVO — o linker bionic precisa do nome exato via `LD_LIBRARY_PATH` (o
  logcat do aparelho confirma `permitted_path=/data/...:/data/user/0/<pkg>`).
  Motivo (glibc): §7.
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
- **~~glibc não vai no pacote~~ — SUPERADO pelo §7 (M3 fix #1, 2026-10-05):** a
  premissa "box64 resolve libc/libm/pthread/dl via wrappers internos" valia para
  o M1/M2 (binário ESTÁTICO) e refutada para o M3 (host DINÂMICO exige glibc
  real versionada — SIGSEGV no aparelho, KB-002). FFmpeg/freetype configurados
  mínimos.
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
| glibc guest x86-64 | `libc6-amd64-cross` do runner (MESMO pacote/diretório do leg A do CI, `dpkg -L`) | n/a (binários da distro; SEM recompilação) | `assets/rootfs/` (libc.so.6 + ld-linux-x86-64.so.2 + libm.so.6) → extraídos pelo app p/ `<filesDir>/rootfs/lib` (§7) |

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

## 7. M3 fix #1 — glibc guest x86-64 embarcada no APK (2026-10-05)

A premissa original do §1 ("glibc não vai no pacote") foi **refutada pelo 1º
teste de aparelho** (motorola edge 30 fusion, Android 14, 2026-10-05): o host
M3 dinâmico morreu com SIGSEGV (exit=139) após ~27 relocações não resolvidas
(`__libc_start_main@GLIBC_2.34`, família locale/newlocale, gettext, `_chk`) —
os wrappers do box64 @abfb8c3 não fornecem esses símbolos versionados, e o CI
só havia passado porque a leg A punha a glibc cross do runner no
`BOX64_LD_LIBRARY_PATH`. Diagnóstico completo: KB-002 (KNOWN_BUGS.md).

Decisão (paridade exata com a leg A do CI, sem recompilar nada):

1. O APK embarca `assets/rootfs/{libc.so.6, ld-linux-x86-64.so.2, libm.so.6}`
   copiados do `libc6-amd64-cross` DO MESMO runner que constrói o anyhost
   (versão exata da glibc do link). Assets não têm a restrição `lib*.so` do
   AGP → sem renomeios; o app extrai para `<filesDir>/rootfs/lib/`, que JÁ
   está no `BOX64_LD_LIBRARY_PATH` — o loader guest do box64 passa a resolver
   libc/libm/ld-linux exatamente como na leg A. `libm.so.6` é DT_NEEDED de
   `libavutil_sos.so` (verificado no APK).
2. Assert HARD novo no job apk (`M3_GLIBC_ASSETS_OK`): os 3 arquivos são ELF
   x86-64 dinâmicos não-vazios e `libc.so.6` exporta `__libc_start_main@`
   `GLIBC_2.34+` (`readelf -W` — a busca exata que falhou no device).
3. A rootfs mínima passa a **4 arquivos** (SDL2 nativa + 3 glibc); libstdc++/
   libgcc_s continuam ESTÁTICAS no anyhost (nada muda); SDL2 segue NUNCA como
   lib guest (wrapper nativa).
4. O PT_INTERP `/lib64/ld-linux-x86-64.so.2` do anyhost segue apontando para o
   caminho padrão (não existe no device): o log do crash prova que o box64
   segue sem ele (não-fatal); o ld-linux embarcado cobre o DT_NEEDED
   homônimo via rootfs. Se o reteste mostrar dependência do ld.so real, o
   próximo passo documentado é `patchelf --set-interpreter` com o caminho do
   filesDir (decisão futura, fora do escopo do fix #1).

## 7.1 M3 fix #2 — shims bionic no box64 + export-dynamic (2026-10-05, SUPEROU o §7)

O **2º teste de aparelho** (mesmo device, APK do run 37242064025 / commit
`8ab2e97`, COM os assets do §7 presentes e extraídos) reproduziu o mesmo
crash exit=139 com as mesmas 27 relocações — **refutando o diagnóstico do
§7** (evidência: `docs/evidence/m3-device-logcat-2026-10-05-falha2.txt`).

Causa raiz real (KB-002 revisado; análise de `src/librarian/library.c`,
`src/wrapped/wrappedlib_init.h` e `src/emu/entrypoint.c` do box64 @abfb8c3):

1. `libc.so.6`/`libm.so.6`/`ld-linux-x86-64.so.2` são libs "essential
   WRAPPED": o box64 instancia o wrapper com `dlopen(NULL)` (o próprio
   processo box64) e **nunca abre os arquivos reais** — a glibc guest do §7
   é INERTE para o loader. Símbolos GO resolvem por
   `dlsym(dlopen(NULL), nome)`: no CI glibc o escopo global fornece tudo; no
   bionic, nenhum dos 27 existe.
2. `__libc_start_main` é GOM (`dlsym(box64lib, "my___libc_start_main")`), mas
   `entrypoint.c` só compila `my___libc_start_main` em hosts NÃO-Android
   (`#ifdef ANDROID` compila o variante bionic `my___libc_init`) e o CMake
   `ENABLE_EXPORTS` é no-op no toolchain Android → dupla ausência no device.

Decisão (paridade de superfície: tudo que o CI glibc resolve via escopo
global passa a existir no binário Android):

1. `patches/0004-android-glibc-shims.patch`:
   a. `wrappedlibc.c` — bloco `#if defined(ANDROID) && !defined(STATICBUILD)`
      com 27 shims (protótipos = assinaturas registradas em
      `wrappedlibc_private.h`): locale C-only (`_l` → funções simples),
      gettext → identidade, `_chk` → sem-check, `__errno_location` → `&errno`,
      `__xpg_strerror_r` → `strerror_r` POSIX (achado da AUDITORIA — falha
      LAZY que o device não alcançou), dummies não-NULL para
      newlocale/duplocale/uselocale.
   b. `entrypoint.c` — `my___libc_start_main` (e `my32___libc_start_main`)
      compilados INCONDICIONALMENTE (no Android coexistem com
      `my___libc_init` do upstream; hosts glibc: código-gerado idêntico).
2. `scripts/ci-build-box64.sh`: `-DCMAKE_EXE_LINKER_FLAGS="-Wl,--export-dynamic"`
   (exporta `my_*` + shims para o `.dynsym`; o mesmo `dlopen(NULL)` cobre GO
   e GOM) + aplicação do patch 0004 + **assert HARD etapa 4b**: os 28 exports
   obrigatórios no `.dynsym` (`M3_GLIBC_SHIMS_OK` / falha =
   `BOX64_EXPORT_SURFACE_INCOMPLETE`).
3. Validação local pré-push: build completo do box64 com NDK r28b (bionic,
   dynarec ON) — compila limpo e os 28 exports presentes
   (`M3_GLIBC_SHIMS_OK`).
4. `scripts/audit-symbols.py` (novo, também em `scripts/` do repo): audita
   cada símbolo UND dos ELFs guest do APK contra bionic ∪ exports box64 ∪
   GOM ∪ libs guest — provedor-modelo correto (GO ≠ provider). Run contra o
   APK do run 19: gaps = exatamente os 27 + `__xpg_strerror_r`, zero outros.
5. A glibc guest do §7 PERMANECE no APK (inerte/inofensiva — candidate à
   remoção ou reaproveitamento no M4); a rootfs mínima segue 4 arquivos.
