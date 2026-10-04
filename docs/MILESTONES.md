# MILESTONES — SoS-PS5-Android

Regra: só avançar quando o marco anterior passar. Reportar ao usuário ao fim de cada um.
Status: `[ ]` pendente · `[~]` em andamento · `[x]` concluído · `[!]` bloqueado/invíavel (com explicação)

## M1 — Prova de conceito do tradutor `[x]`

**Objetivo:** rodar um ELF x86-64 simples dentro de um app Android ARM64 usando box64.

Critérios de aceite:
1. [x] **CONCLUÍDO (run 37220048527, 2026-10-04)** — Job CI `box64-arm64`
   (`ubuntu-24.04-arm`) compila box64 v0.4.5 nativo com `ARM_DYNAREC=ON` e executou
   de verdade: payload **estático** (`SOS_POC_STATIC_OK` + `SOS_POC_STATIC_MATH_OK`,
   checksum exato) E payload **dinâmico** com glibc guest
   (`SOS_POC_DYNAMIC_OK`). Evidência: artifacts `box64-arm64-poc` (run_static.log /
   run_dynamic.log) e job summary.
2. [x] **CONCLUÍDO (mesmo run)** — APK `poc` compilado no CI: box64 (NDK r29/bionic,
   `ARM_DYNAREC=ON`, flexível page sizes) + payload estático empacotados como
   jniLibs; artifact `SoS-PS5-Android-PoC-debug.apk` (15,3 MB) publicado.
3. [x] **CONCLUÍDO (2026-10-04, reteste on-device com APK v2)** — motorola edge 30
   fusion (Android 14): `SUCESSO: SOS_POC_STATIC_OK detectado (exit=0)`; log mostra
   payload x86-64 rodando com checksum EXATO (`acc=7.485471`,
   `ichk=4201695289734782276`), `uname machine=x86_64` (fake do box64 correto) e
   zero sinais/crashes. CI run 37222328087 (sha 7f1aa9d): **success** nos 2 jobs.
   APK v2 arquivado em download/SoS-PS5-Android-PoC-debug.apk.

Histórico de status:
- Task 1-a (2026-10-04): payloads + job `box64-arm64` entregues; verificação local em x86_64 OK; aguardava CI.
- Task 1-b (2026-10-04): APK PoC entregue (box64 NDK/bionic + payload como jniLibs).
- Task 1-c (2026-10-04): revisão crítica independente — 2 MAJOR corrigidos (watchdog de
  timeout, IOException), 4 MINOR corrigidos, veredito GO.
- Fix CI (2026-10-04): branch do box64 upstream mudou de `master` para `main`.
- Run 37220048527 (2026-10-04): **verde** — critérios 1 e 2 concluídos (evidência acima);
  critério 3 aguarda o aparelho do usuário.
- On-device #1 (2026-10-04): teste no aparelho (motorola edge 30 fusion, Android 14)
  FALHOU com exit 159 — SIGSYS/seccomp no syscall arm64 99 (`set_robust_list`) que o
  box64 repassava direto ao kernel. Fix: patch seccomp-safe (`patches/0001`), box64
  pinado em `abfb8c3b2fad`, app decodifica sinais ≥ 128. Aguardando reteste com o
  APK v2.
- Run 37222328087 + on-device #2 (2026-10-04): CI **success** nos 2 jobs e reteste no
  aparelho **SUCESSO** (exit=0, markers + checksum exato). **M1 CONCLUÍDO** —
  execução real de código x86-64 traduzido por box64 (dynarec ARM) dentro de app
  Android ARM64, validada em CI e no aparelho do usuário.

Nota (Task 1-a): `payloads/x86_64/hello_static.c` é fonte canônica COMPARTILHADA
com a task 1-b (APK usa o mesmo payload como `libpayload64.so`). Os marcadores
`SOS_POC_STATIC_OK` e `SOS_POC_STATIC_MATH_OK` são contrato do CI 1-a e NÃO
podem ser removidos/renomeados (colisão 1-a×1-b já ocorrida e mesclada — ver
`docs/PROGRESS.md`).

## M2 — Núcleo AnyPS5 em Linux x86-64 `[x]`

**Objetivo (reformulado — ver PLAN.md):** host AnyPS5 compila e roda em Linux x86-64
(relinker sem `--windows` = saída Linux nativa; entrada de host não-Win32 com SDL2),
pronto para rodar sob box64. Espec/contrato: `docs/M2-LINUX-HOST.md` (M2-a).
Critérios: binário Linux x86-64 construído no CI; roda sob box64 (runner arm64) até a
tela de "arquivos do jogo ausentes" sem crash (execução real validada em CI);
teste no aparelho com APK do host (critério 3).

Sub-etapas:
- [x] M2-a (2026-10-05): mapeamento do launcher Win32 + spec do `anyhost` + descoberta
  de que o relinker já tem caminho Linux nativo (`docs/M2-LINUX-HOST.md`).
- [x] M2-b (2026-10-05, entregue — aceite = job `host-linux` verde): implementação do
  `anyhost` (host/anyhost.cpp + superprojeto CMake standalone + patch 0002) com
  validação de build/execução NATIVA x86-64 local (dir vazio →
  `SOS_HOST_MISSING_GAME_FILES reason=no-eboot`, exit 1).
- [x] M2-c (2026-10-05, entregue — aceite = CI verde): CI — job `host-linux` (build
  x86-64 estático anyhost+relinker, smoke nativo) + job `box64-arm64` rodando o host
  sob box64 até o marker com exit 1 limpo.
- [x] M2-d (2026-10-05, entregue — aceite = on-device): APK de teste (botão M2 +
  `libanyhost64.so` como jniLibs).
- [x] M2-e (2026-10-05): revisão crítica independente + loop de correção — patch
  re-aplicado e re-buildado em worktree limpo do upstream pinado, smokes reproduzidos,
  build.yml/MainActivity validados programaticamente; doc atualizado com os desvios
  aprovados (offscreen headless, duplo-fail SDL2, relink sem restart); GO emitido.

Critérios de aceite:
1. [x] **CONCLUÍDO (run 37230357330, 2026-10-05)** — job `host-linux` verde:
   `anyhost` + `relinker` x86-64 **statically linked**, smoke nativo
   (`HOST_NATIVE_SMOKE_PASSED`: exit 1 + 4 markers/ checks).
2. [x] **CONCLUÍDO (mesmo run)** — job `box64-arm64` verde (19/19 steps): host sob
   box64 (dynarec ARM ON + `BOX64_DYNAREC_SAFEFLAGS=2`, ver KB-001) atinge
   `SOS_HOST_MISSING_GAME_FILES reason=no-eboot` com `SOS_HOST_SDL2_OK
   driver=offscreen`, exit 1 limpo, nenhum `Fatal signal`
   (`HOST_UNDER_BOX64_PASSED`); smokes M1 de regressão PASS (estático, dinâmico)
   e relinker sob box64 limpo (usage, exit 1).
3. [x] **CONCLUÍDO (2026-10-05, 1º teste on-device)** — motorola edge 30 fusion
   (Android 14, SDK 34, arm64-v8a), APK do run 37230357330: diagnóstico abertura
   confirma `libbox64.so`/`libpayload64.so`/`libanyhost64.so` presentes; app
   reporta `SUCESSO (M2): missing game files atingido (exit=1,
   SOS_HOST_SDL2_OK driver=offscreen)`; logcat mostra `SOS_HOST_STARTED` →
   `SOS_HOST_SDL2_OK driver=offscreen` → `SOS_HOST_MISSING_GAME_FILES
   reason=no-eboot`, **zero** `Fatal signal`/SIGSEGV/SIGSYS (ruído conhecido
   `avc: denied`/`lscpu` presente e não fatal, conforme previsto em
   ON_DEVICE_TEST.md §§6-7.5). Evidência bruta:
   `docs/evidence/m2-device-logcat-2026-10-05.txt`.

Histórico de status:
- M2-a..M2-e (2026-10-05): entregues — mapeamento/contrato (`M2-LINUX-HOST.md`),
  `anyhost` + patch 0002, CI (`host-linux`/`box64-arm64`/`apk`), botão M2 no app,
  revisão crítica independente GO (ver PROGRESS.md).
- Fix-loop CI (2026-10-05, 3 iterações + matriz de debug): #1 path do relinker no
  CMake (saída no root do build); #2 SIGSEGV do host sob dynarec ARM na init da
  SDL2 → workaround `BOX64_DYNAREC_SAFEFLAGS=2` (KB-001, `KNOWN_BUGS.md`); #3
  call-site Java do M1. Run 37230357330: **3/3 jobs SUCCESS**.
- On-device #1 (2026-10-05): **SUCESSO** no aparelho do usuário — **M2
  CONCLUÍDO** (critérios 1, 2 e 3 com execução real em CI e no aparelho).

## M3 — Integração de libs no runtime box64/Android `[~]` (próximo marco)

Contrato/empacotamento: `docs/M3-LIBS-RUNTIME.md` (M3-a). SDL2 via wrapper nativa
ARM64 (wrappedsdl2 do box64), FFmpeg n6.1.2 + freetype 2.13.3 como libs guest
x86-64 (`_sos` SONAMEs), rootfs mínima = 1 arquivo (libSDL2 nativa renomeada no
filesDir), libstdc++/libgcc_s estáticas no host.

Critérios de aceite:
1. [x] **CONCLUÍDO (run 37239106249, 2026-10-05)** — job `host-linux`:
   guest libs construídas (FFmpeg/freetype shared + patchelf `_sos`,
   `M3_GUEST_LIBS_OK`) + anyhost **dinâmico** (DT_NEEDED = SDL2 + 3 `_sos` +
   libc; libstdc++/libgcc_s PROIBIDAS como DT_NEEDED) + relinker estático
   (`HOST_M3_EVIDENCE_OK`); smoke nativo com TODOS os markers
   (`HOST_NATIVE_SMOKE_PASSED`: SDL2 offscreen + FREETYPE 2.13.3 + FFMPEG
   6.1.2 + MISSING_GAME_FILES reason=no-eboot, exit 1).
2. [x] **CONCLUÍDO (mesmo run)** — job `box64-arm64` (19/19): host M3 sob box64
   com wrapper SDL2 nativa (`NATIVE_SDL2_DLOPEN_OK`), **TODOS os markers sem
   `symbol not found`**; **KB-001 reavaliado**: leg A SEM
   `BOX64_DYNAREC_SAFEFLAGS` = PASS (`KB001_REEVAL_NO_SAFEFLAGS_PASS`) →
   workaround removido do CI e do app (KB-001 FECHADO p/ M3); regressão M1
   PASS (estático + dinâmico); relinker sob box64 limpo.
3. [x] **CONCLUÍDO (mesmo run)** — job `apk`: SDL2 nativa ARM64/bionic
   (dummy/offscreen, submodule pinado) + box64 NDK + anyhost dinâmico + 3 libs
   guest no APK (**7 jniLibs**, `JNI_LIBS_M3_EVIDENCE_OK`); botão M3 no app
   com veredito verde exigindo os 5 markers + exit 1 (M1/M2 intocados).
4. [ ] Teste on-device (motorola edge 30 fusion, Android 14): botão M3 verde
   no app, logcat com markers, sem crash. 1ª tentativa (APK v1, run
   37239106249) = FALHA exit=139 por glibc guest ausente no device (KB-002) →
   fix #1 (run
   [37242064025](https://github.com/deivid22srk/SoS-PS5-Android/actions/runs/37242064025),
   APK v2 25,6 MB com glibc guest via assets/rootfs) aguardando o usuário.

Histórico de status (fix-loop CI do M3, 2026-10-05):
- Run 37237030734: host-linux SUCCESS no 1º try; box64-arm64 SUCCESS
  (KB-001 PASS sem SAFEFLAGS); apk FALHOU — wrapper jar do android-project da
  SDL vendored reprovava a validação do setup-gradle → removido (d5f4c34).
- Run 37237672692: apk FALHOU — stubs JNI de haptic incondicionais na SDL
  @4b69833 exigem `SDL_HAPTIC=ON` no Android (e4e2412).
- Run 37238368339: apk FALHOU — CMake da SDL no ANDROID gera `libSDL2.so` (sem
  sufixo de versão); find/assert atualizados (deba957).
- Run 37239106249: **3/3 jobs SUCCESS** — critérios 1-3 concluídos; APK v4
  arquivado (download/SoS-PS5-Android-M3-debug.apk).
- Run 37242064025 (fix #1 pós-falha de aparelho, KB-002): **3/3 jobs SUCCESS
  no 1º try** — glibc guest x86-64 via assets/rootfs (`M3_GLIBC_ASSETS_OK`) +
  UI rolável + versionCode 2; APK v5 arquivado
  (download/SoS-PS5-Android-M3-fix1-debug.apk).

## M4 — Memória `[ ]`

Endereços fixos do PS5 sob VA de 39 bits (reserva/marcação de faixas), mmap executável
e W^X no Android, shims de /proc e paths. Critérios: relinker + loader mapeiam o
eboot.elf de teste sintético em endereços fixos e executam trampolins sob box64 no
aparelho (logcat prova execução em endereço fixo).

## M5 — AGC→Vulkan 1.3 no Android `[ ]`

Ponte Vulkan (processo x86-64 → driver nativo do aparelho), primeira passada de
renderer com clear/triângulo, shader recompiler RDNA→SPIR-V funcionando no GPU do
usuário. Critérios: frame renderizado validado por captura/logcat no aparelho.

## M6 — Launcher Android `[ ]`

Activity final, SAF com persistência, preparação 1ª execução com progresso, gamepad
Bluetooth/USB prioridade (wrapper SDL2) e touch fallback, PT-BR completo.

## M7 — Boot do jogo `[ ]`

Dump do usuário no aparelho (via SAF), preparação (~2 min) e boot até menu/gameplay.
Critérios: logcat limpo de crash, FPS reportado honestamente, limitações listadas.

## Transversal

- CI `build.yml` verde a cada push; APK como artifact. `[~]`
- Docs de progresso atualizadas por marco. `[~]`
