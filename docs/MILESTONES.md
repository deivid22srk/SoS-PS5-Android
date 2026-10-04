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

## M2 — Núcleo AnyPS5 em Linux x86-64 `[ ]`

**Objetivo (reformulado — ver PLAN.md):** host AnyPS5 compila e roda em Linux x86-64
(relinker `--linux`, entrada de host não-Win32 com SDL2), pronto para rodar sob box64.
Critérios: binário Linux x86-64 construído no CI; roda sob box64 (runner arm64) até a
tela de "arquivos do jogo ausentes" sem crash (execução real validada em CI).

## M3 — Integração de libs no runtime box64/Android `[ ]`

SDL2 via wrapper (input/vídeo), FFmpeg para o ABI-alvo, fontes freetype; decisões de
empacotamento (o que vai em jniLibs vs rootfs mínima). Critérios: no runner arm64, o
host sob box64 inicializa SDL2 (vídeo dummy) + decoders sem símbolo ausente.

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
