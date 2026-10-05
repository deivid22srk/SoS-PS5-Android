# KNOWN_BUGS — SoS-PS5-Android

Bugs conhecidos e workarounds ativos. Cada entrada: sintoma, causa raiz,
contorno, status e quando reavaliar.

---

## KB-001 — SIGSEGV na init da SDL2 estática sob o dynarec ARM do box64 (M2)

- **Sintoma:** o `anyhost` (host x86-64 estático com SDL2) morre com SIGSEGV
  (exit 139) logo após `SOS_HOST_STARTED`, sob box64 com dynarec ARM, no runner
  ARM64 — com qualquer driver de vídeo (offscreen/dummy). Sob o intérprete do
  box64 (`BOX64_DYNAREC=0`) o mesmo binário roda até o fim (exit 1 limpo com
  todos os markers). Nativamente em x86-64 não há crash.
- **Evidência:** matriz de diagnóstico do workflow temporário `debug-m2.yml`
  (runs 37228217771 e 37228988961, branch main):
  | cenário | env | exit |
  |---|---|---|
  | A baseline (dynarec ON) | — | 139 |
  | B intérprete | `BOX64_DYNAREC=0` | 1 |
  | C dummy video (dynarec ON) | `SDL_VIDEODRIVER=dummy` | 139 |
  | D intérprete + dummy | ambos | 1 |
  | E `--version` (sem SDL_Init) | — | 0 |
  | **H dynarec + SAFEFLAGS=2** | `BOX64_DYNAREC_SAFEFLAGS=2` | **1** |
  | I dynarec + BIGBLOCK=0 | `BOX64_DYNAREC_BIGBLOCK=0` | 139 |
  | J dynarec + STRONGMEM=1 | `BOX64_DYNAREC_STRONGMEM=1` | 139 |
  | K dynarec FPU conservador | X87DOUBLE/FASTNAN/FASTROUND | 139 |
  | G relinker (usage) sob dynarec | — | 1 |
- **Causa raiz (isolação por bisection da matriz):** bug de manuseio parcial de
  EFLAGS no dynarec ARM do box64 (pinado em `abfb8c3b2fad`), disparado pelo
  código de init da SDL2 estática. `BOX64_DYNAREC_SAFEFLAGS=2` (manuseio
  conservador de flags) elimina o crash com dynarec LIGADO; knobs de memória
  (STRONGMEM), de blocos (BIGBLOCK=0) e de FPU não afetam.
- **Contorno (REMOVIDO no M3):** `BOX64_DYNAREC_SAFEFLAGS=2` estava ativo no
  CI (RUN anyhost) e no app (`runM2Test` via `extraEnv`) durante o M2.
- **Reavaliação M3 (2026-10-05, run 37239106249):** com a SDL2 guest estática
  substituída pelo **wrapper nativo do box64** (`wrappedsdl2.c` → SDL2 ARM64
  nativa), o código disparador deixou de existir. A matriz KB-001 do job
  `box64-arm64` executou o fluxo M3 completo (host dinâmico + FFmpeg/freetype
  guest + SDL2 wrapper) **SEM** `SAFEFLAGS`: **PASS** limpo
  (`KB001_REEVAL_NO_SAFEFLAGS_PASS`), zero sinais, zero `symbol not found`.
  Workaround removido do CI e do app (`KB001_SAFEFLAGS_REMOVED=1`). Benchmark
  de custo não foi necessário (contorno não é mais usado); se o SIGSEGV
  reaparecer em outro guest code (M4+, ex. código do jogo), a matriz volta a
  rodar automaticamente e mede o delta antes de re-adotar.
- **Status:** FECHADO para o cenário M3 (reabrir via matriz KB-001 se crash
  dynarec reaparecer).

---

## KB-002 — Host M3 dinâmico morre com SIGSEGV no aparelho: superfície de símbolos do box64/bionic incompleta (M3)

- **Sintoma (2026-10-05, motorola edge 30 fusion / Android 14; logcats
  integrais em `docs/evidence/m3-device-logcat-2026-10-05-falha1.txt` e
  `-falha2.txt` — o 2º log JÁ COM a glibc guest do fix #1 no APK):** botão M3
  falha com `FALHA (M3): resultado inesperado, exit=139` (SIGSEGV). O logcat
  mostra ~27 relocações não resolvidas antes do crash — `__libc_start_main`
  (GLOB_DAT; `Warning, function my___libc_start_main not found` 2×),
  `__errno_location`, família locale (`__wcsftime_l`, `__towlower_l`,
  `__wcscoll_l`, `__mbsnrtowcs_chk`, `__iswctype_l`, `__strtod_l`,
  `__wcsxfrm_l`, `__freelocale`, `__uselocale`, `__strcoll_l`, `__strtof_l`,
  `__strftime_l`, `__wmemcpy_chk`, `__strxfrm_l`, `__nl_langinfo_l`,
  `__mbsrtowcs_chk`, `__wmemset_chk`, `__wctype_l`, `__newlocale`,
  `__towupper_l`, `__duplocale`), gettext (`gettext`, `dgettext`,
  `bindtextdomain`, `bind_textdomain_codeset`) — cada uma com exigência de
  versão (`optver=N / GLIBC_2.x`) — seguidas de `Unhandled signal caught`.
  SDL2 nativa OK antes disso (`avc: granted { execute }` sobre a
  `rootfs/lib/libSDL2-2.0.so.0`).
- **Fix #1 (glibc guest no APK) NÃO resolveu** — o logcat #2 (APK do run
  37242064025, commit 8ab2e97) reproduz o mesmo crash com os assets presentes
  e extraídos (`Runtime M3 pronto (glibc guest)` nos 3 arquivos).
- **Causa raiz CORRIGIDA (análise do código box64 @abfb8c3, auditoria de
  símbolos do APK run 19 — `scripts/audit-symbols.py`):** dois mecanismos
  independentes:
  1. **GO sem backing no bionic:** box64 trata `libc.so.6`, `libm.so.6` e
     `ld-linux-x86-64.so.2` como libs "essential **WRAPPED**"
     (`isEssentialLib` em `src/librarian/library.c`) — o wrapper é criado com
     `lib->w.lib = dlopen(NULL)` (o PRÓPRIO processo box64) e **nunca carrega
     os arquivos reais** do `BOX64_LD_LIBRARY_PATH` (por isso a glibc guest do
     fix #1 é inerte). Símbolos GO resolvem via `dlsym(dlopen(NULL), nome)`: no
     CI (box64 linkado à glibc) o escopo global tem TODOS os símbolos da
     glibc; no aparelho (bionic) nenhum dos 27 existe. Os 27 estão
     REGISTRADOS nos mapas do wrapper (`wrappedlibc_private.h`) — falta o
     PROVIDER no device, não o registro.
  2. **GOM sem exportação do binário:** `__libc_start_main` é GOM — resolve
     via `dlsym(box64lib, "my___libc_start_main")`. O binário do box64 no
     Android não exporta `my_*` porque o `ENABLE_EXPORTS ON` do CMake é
     no-op no toolchain Android (Platform/Android limpa
     `CMAKE_SHARED_LIBRARY_LINK_*_FLAGS` → `-rdynamic` nunca é passado).
     Provado no APK do run 19: `readelf --dyn-syms libbox64.so | grep -c
     my___libc_start_main` = **0** (2.662 símbolos).
  A auditoria também identificou `__xpg_strerror_r` (referenciado por
  `libavutil_sos.so`, GO registrado, ausente no bionic) que falharia LAZY
  (PLT) na primeira chamada — o device morreu antes (as libs guest têm binding
  lazy; só o binário principal reloca JUMP_SLOT eagerly).
- **Contorno (APLICADO — "M3 fix #2"):**
  1. `patches/0004-android-glibc-shims.patch` — bloco `#if defined(ANDROID)
     && !defined(STATICBUILD)` no fim de `wrappedlibc.c` com os 27 shims
     (locale C-only: `_l` → funções simples; gettext → identidade; `_chk` →
     variantes sem-check; `__errno_location` → `&errno`; `__xpg_strerror_r`
     → `strerror_r` POSIX + return buf; locale dummies não-NULL para
     `__newlocale/__duplocale/__uselocale`, `__freelocale` no-op).
     Limitação conhecida: `mbstate_t` glibc-x86_64 e bionic-arm64 têm o
     MESMO tamanho (8 B) e estados INICIAIS compatíveis (C-locale/ASCII
     seguros), mas um estado NÃO-inicial de multibyte dividido do guest
     poderia ser mal interpretado — irrelevante para os probes M3. Compila
     FORA em hosts glibc → CI/behavior desktop inalterados.
  2. `scripts/ci-build-box64.sh`: `-DCMAKE_EXE_LINKER_FLAGS=
     "-Wl,--export-dynamic"` no cmake do box64 → exporta `my_*` (GOM) e os
     shims para o `.dynsym` (o mesmo handle `dlopen(NULL)` cobre GO e GOM).
  3. Assert HARD pós-build (etapa 4b): todos os 28 exports obrigatórios
     (`my___libc_start_main` + 27 shims) presentes no `.dynsym` do
     `libbox64.so` — `M3_GLIBC_SHIMS_OK` / falha =
     `BOX64_EXPORT_SURFACE_INCOMPLETE`.
- **Status:** correção enviada (aguarda CI verde + reteste no aparelho). A
  glibc guest do fix #1 permanece no APK (inerte, inofensiva; candidata a
  remover ou reaproveitar no M4). Se o SIGSEGV persistir, reabrir com logcat
  completo + auditoria `scripts/audit-symbols.py` re-executada contra o novo
  APK.
