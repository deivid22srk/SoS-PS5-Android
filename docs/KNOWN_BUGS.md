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

## KB-002 — Host M3 dinâmico morre com SIGSEGV no aparelho: glibc x86-64 real ausente no device (M3)

- **Sintoma (2026-10-05, motorola edge 30 fusion / Android 14; logcat integral
  em `docs/evidence/m3-device-logcat-2026-10-05-falha1.txt`):** botão M3
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
- **Causa raiz:** o anyhost do M3 é **DINÂMICO** e exige a glibc x86-64 REAL
  (símbolos versionados — ex. `__libc_start_main@GLIBC_2.34` — que os wrappers
  internos do box64 @abfb8c3 não fornecem com a versão pedida). O CI
  (`box64-arm64`, leg A) só passou porque o `BOX64_LD_LIBRARY_PATH` incluía o
  diretório da glibc cross (`libc6-amd64-cross`) — **o device não tinha nada
  equivalente**. No M2 o problema não aparecia porque o binário era ESTÁTICO
  (sem relocações dinâmicas). Premissa do contrato M3 §1 ("glibc não vai no
  pacote") refutada por esta evidência; contrato emendado (§7).
- **Contorno (APLICADO — task 3-g, "M3 fix #1"):** o APK embarca a MESMA glibc
  cross do CI — `assets/rootfs/{libc.so.6, ld-linux-x86-64.so.2, libm.so.6}`,
  copiados do `libc6-amd64-cross` do MESMO runner que constrói o anyhost
  (paridade exata de versão com a glibc do link). O app extrai para
  `<filesDir>/rootfs/lib/` (já no `BOX64_LD_LIBRARY_PATH`); `libm.so.6` é
  DT_NEEDED de `libavutil_sos.so`. Assert HARD novo no job apk:
  `M3_GLIBC_ASSETS_OK` (ELF x86-64 dinâmico + `__libc_start_main@GLIBC_2.34+`
  via `readelf -W`).
- **Status:** correção enviada (aguarda CI verde + reteste no aparelho). Se o
  SIGSEGV persistir com a glibc presente, reabrir com logcat completo —
  próximo candidato documentado no contrato §7.4: dependência do ld.so real
  via PT_INTERP `/lib64/...` (hoje provado não-fatal pelo próprio log do
  crash); correção planejada: `patchelf --set-interpreter` p/ o caminho do
  filesDir.
