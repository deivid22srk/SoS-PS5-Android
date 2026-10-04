# PROGRESS — SoS-PS5-Android

Fonte de verdade incremental por tarefa. Regra: tudo que se afirma aqui foi
executado de verdade (comando + saída) ou está explicitamente marcado como
"aguardando CI".

---

## On-device #2 — SUCESSO com APK v2 (2026-10-04) — **M1 CONCLUÍDO**

- Log do usuário (pastebin cMZGcxKy, cópia local `logs/success_v2.log`): após instalar
  o APK v2 (CI run 37222328087, sha 7f1aa9d), o mesmo aparelho (motorola edge 30
  fusion, Android 14) executou o payload x86-64 sob box64 **até o fim**:
  - `SOS-PS5 PoC: static x86-64 payload started` + `argc/argv0/pid/uid` corretos;
  - `uname ... machine=x86_64` (box64 mascarando a arquitetura — comportamento certo);
  - `math: acc=7.485471 expected=7.485471 diff=0.000e+00` e
    `ichk=4201695289734782276` — **checksum exato** igual à referência x86-64 nativa;
  - `SOS_POC_STATIC_MATH_OK` + `SOS_POC_STATIC_OK`, `exit=0`, zero sinais/crash,
    zero avc novo (só o ruído conhecido `sh: lscpu`).
- Tela do app: `SUCESSO: SOS_POC_STATIC_OK detectado` (verde).
- CI run 37222328087: job `box64-arm64` **success** (6 min) e job `apk` **success**
  (2,5 min) — patch seccomp-safe aplicado e compilado nos dois caminhos (Linux ARM64
  e NDK/bionic), confirmando que o patch é no-op no Linux.
- Conclusão: **M1 completo** — os 3 critérios de aceite verificados com execução real
  (CI ARM64 + aparelho do usuário). Pilha provada: ELF x86-64 estático → box64
  (NDK/bionic, dynarec ARM, patch seccomp-safe) → ProcessBuilder no app → logcat.
- APK v2 arquivado: `download/SoS-PS5-Android-PoC-debug.apk` (16,5 MB).

---

## Fix on-device #1 — FALHA exit 159 (SIGSYS / set_robust_list) → patch seccomp-safe (2026-10-04)

### Evidência do aparelho (log do usuário, pastebin 1ZCBL8fA)

- Aparelho identificado pelo log: **motorola edge 30 fusion (tundra)**, Android **14**
  (SDK 34), `arm64-v8a`, GPU **Adreno** (Snapdragon 888+). O app em si funcionou:
  diagnóstico correto, `libbox64.so`/`libpayload64.so` presentes e processo lançado
  (`I SOSBox64: Executando: .../libbox64.so .../libpayload64.so (BOX64_LOG=DEBUG)`).
- Crash: `F libc: Fatal signal 31 (SIGSYS), code 1 (SYS_SECCOMP), syscall 99` no tid do
  guest (`libpayload64.so`); backtrace `libc.so (syscall+36)` ←
  `libbox64.so (x64Syscall_linux+752)` ← código guest JIT. O app reportou
  `FALHA: marcador ausente, exit=159` (159 = 128 + 31, morto por sinal).
- **Causa raiz:** a glibc guest (payload estático) chama `set_robust_list`
  (x86-64 syscall **273**) na inicialização da NPTL; `x64Syscall_linux` repassa direto
  via `syscallwrap[273] = {__NR_set_robust_list, 2}`; no arm64 `__NR_set_robust_list`
  = **99**, que NÃO está na allowlist seccomp de `untrusted_app` → o kernel mata o
  processo com SIGSYS. (`rseq`/x86-64 **334** já é tratado upstream com `-ENOSYS`
  no `case 334` — não é problema.) Números conferidos em
  `/usr/include/aarch64-linux-gnu/asm/unistd_64.h` (99/100/293) e unistd_64 x86_64
  (273/274/334). Clone local do box64 == upstream main `abfb8c3b2fad` (sem diff —
  o upstream NÃO contorna isso; o patch é nosso).
- Ruído não-fatal no log (ignorar): avc `denied { search } name="tests"`
  (`shell_test_data_file`) ×3 e `sh: lscpu: inaccessible or not found` (box64 invoca
  `lscpu` via shell no Android).

### Fix aplicado (`patches/0001-android-seccomp-robust-list.patch`)

- `src/emu/x64syscall.c` do box64: entradas `[273]`/`[274]` da `syscallwrap[]`
  agora só existem `#ifndef __ANDROID__`; casos explícitos sob `#ifdef __ANDROID__`:
  `case 273 → 0` (fake success — glibc segue a init) e `case 274 → -ENOSYS`, nos DOIS
  switches (`x64Syscall_linux` e `my_syscall`). Marcador `BOX64_ANDROID_SECCOMP_STUB`
  para detecção idempotente. Em builds não-Android o código compila fora
  (comportamento do Linux inalterado).
- CI: box64 agora **PINADO** em `abfb8c3b2faddf2273d8ebe532c362d86bfa0356`
  (`build.yml` `ref:` + script faz fetch-by-SHA com fallback para clone de main +
  WARNING); patch aplicado nos 2 jobs (`git apply` idempotente; falha ⇒
  `BOX64_PATCH_FAILED` antes de gastar minutos de build).
- App: `MainActivity.explainSignalExit()` — exit ≥ 128 agora é decodificado em
  PT-BR (ex.: 159 → "sinal 31 (SIGSYS) — syscall bloqueado pelo seccomp do Android").
- Nota de projeto: o patch é cumulativo — o jogo real (glibc dinâmico) também chama
  `set_robust_list` na init do libc; sem este fix, M2+ morreria igual no aparelho.

### Validação local (container sem NDK/SDK)

- `git apply --check` do patch em worktree limpo do upstream pinado: OK; a sequência
  exata do CI (git init + fetch-by-SHA + checkout FETCH_HEAD) foi provada localmente
  contra o GitHub.
- `gcc -fsyntax-only` de `src/emu/x64syscall.c`: (a) host Linux sem `__ANDROID__`
  → exit 0; (b) com `-D__ANDROID__ -DANDROID` → exit 0 (o ramo do stub compila;
  a compilação real NDK fica no job `apk`).
- `bash -n` no script; PyYAML no `build.yml` + strings obrigatórias; 0 duplicatas de
  `case 273/274` nos switches; balanceamento de chaves do Java OK (sem javac no
  container — a compilação real fica no job `apk`).

### Risco novo registrado (para M5)

- Aparelho = Adreno 660 (Snapdragon 888+). O log mostra
  `Updatable production driver is not supported on the device`. Adreno 660 expõe
  Vulkan 1.1/1.2 (1.3 não confirmado) — o core SoS-PS5 exige **Vulkan 1.3**. Risco a
  medir no aparelho na hora do M5 (query via `PackageManager`/vkEnumerate); NÃO
  bloqueia M1–M4.

---

## Task 1-c — Revisão crítica independente do M1 (2026-10-04)

Revisor externo (não escreveu o código). Escopo: workflow, script de CI, payloads,
app Android, consistência de docs e higiene do repo. Correções pequenas e
inequívocas aplicadas e re-verificadas; achados arquiteturais apenas reportados.

### Tabela de achados

| # | Severidade | Arquivo:linha | Achado | Status |
|---|---|---|---|---|
| F1 | MAJOR | `app/.../MainActivity.java` (`runBox64`) | Timeout de 120 s era **código morto**: `readLine()` bloqueia até EOF (o processo fechar stdout), então `waitFor(120 s)` só era alcançado DEPOIS que o processo terminava; num travamento real do box64 o app ficaria preso em "aguarde" indefinidamente. | **FIXED** — watchdog thread: espera 120 s e `destroyForcibly()`; EOF/waitFor seguintes apenas coletam o estado |
| F2 | MAJOR | `app/.../MainActivity.java` (catch `FileNotFoundException`) | ProcessBuilder **não lança** `FileNotFoundException` para programa ausente/não-executável — lança `IOException` simples (error=2 ENOENT / error=13 EACCES; comprovado em OpenJDK 21 localmente: `Cannot run program ... error: 2`; libcore/Android usa `ErrnoException.rethrowAsIOException`). As mensagens PT-BR específicas (`explainMissingFile`) nunca disparavam. | **FIXED** — `catch (IOException)` + checagem de existência dos 2 arquivos antes da mensagem genérica |
| F3 | MINOR | `scripts/ci-build-box64.sh:152-153,174-175` | `cp`/`chmod` dos `.so` para jniLibs sem checagem (script usa `set -uo pipefail`, **sem** `-e`): falha silenciosa produziria APK sem binário. | **FIXED** — `\|\| die` nos 4 pontos |
| F4 | MINOR | `.github/workflows/build.yml` (job `apk`) | Sem `timeout-minutes` (o job A tem 60) — hang queimaria até 6 h de runner. | **FIXED** — `timeout-minutes: 60` |
| F5 | MINOR | `docs/ON_DEVICE_TEST.md:11` | Citava workflow "PoC APK (box64 + payload x86-64)" — nome do `apk.yml` antigo (removido); o nome atual é workflow `build`, job "APK PoC (box64 + payload x86-64)". | **FIXED** |
| F6 | MINOR | `docs/PROGRESS.md` (seção 1-b) | Nota do `apk.yml` dizia "o pai fará merge" sem registrar que o merge JÁ aconteceu (job `apk` hoje vive no `build.yml`; `apk.yml` não existe mais). | **FIXED** — nota Task 1-c inline |
| F7 | NIT | `build.yml` job `apk` (step Ensure SDK) | `yes \| sdkmanager ... \|\| true` engole falha do sdkmanager. Aceitável: o Gradle falha duro se `platforms;android-35` faltar; só dificulta o diagnóstico. | OPEN (aceito) |
| F8 | NIT | `build.yml` job A (Build box64) | `./box64-build/box64 --version \|\| true` — padrão "nunca falha", mas é só evidência informativa (não gating). | OPEN (aceito) |
| F9 | NIT | repo | Sem Gradle wrapper (`gradlew`) — CI pina Gradle 8.9 via setup-gradle; funciona, mas wrapper daria reprodutibilidade local idêntica ao CI. | OPEN |
| F10 | INFO | `build.yml` | box64 via `ref: main` flutuante (decisão D5); SHA registrado no summary. Reprodutibilidade limitada por design. | OPEN (D5) |

### Verificação por item do checklist (evidência local, host x86_64)

- **build.yml**: parse PyYAML OK (BaseLoader; `on:` lido corretamente — push/pull_request/
  workflow_dispatch); jobs `box64-arm64` (ubuntu-24.04-arm, 11 steps) + `apk`
  (ubuntu-latest, 9 steps); actions `checkout@v4`, `setup-java@v4`,
  `gradle/actions/setup-gradle@v4`, `upload-artifact@v4`; `permissions: contents: read`
  no topo; estático = hard fail (`set +e` + RC + grep duplo nos DOIS markers + `exit 1`);
  dinâmico = soft fail (`DYNAMIC_TEST_FAILED reason=...` + `exit 0`); fallback
  `DYNAREC=OFF` visível no summary (com cauda do log); uploads com `if: always()` e
  `if-no-files-found` warn (job A) / error (APK); nenhum secret usado; greps do step
  "Locate cross glibc" são checados (vazio ⇒ `exit 1`).
- **ci-build-box64.sh**: `bash -n` OK. Flags cruzadas com o clone de referência
  `/home/z/my-project/box64` (SHA abfb8c3b2fad): `option(ANDROID ...)` existe
  (CMakeLists.txt:34); `option(ARM_DYNAREC ...)` :130, `-DARM_DYNAREC=ON` é a invocação
  canônica (defaults só ligam dynarec p/ plataformas específicas — o OFF implícito de
  ANDROID torna o flag explícito necessário); `option(NOLOADADDR ...)` :41; Python3 é
  OBRIGATÓRIO no configure (:138-153, FATAL_ERROR se ausente) — instalado no CI;
  ANDROID força `-O1` (:63-68); link Android = `c m dl` (:1370); `git_head.h` via
  `git rev-parse` (clone `--depth 1` atende). **PIE**: com Clang/NDK e `NOLOADADDR`
  default OFF, o upstream NÃO passa `-no-pie` quando `ANDROID` ("--image-base alone
  does not disable PIE with Clang/LLD. **Android requires PIE**", :1394-1397) e aplica
  `LINKER:--image-base=0x34800000` → o binário de saída É um PIE executável (ET_DYN),
  validando o modelo `libbox64.so` renomeado; o retry `NOLOADADDR=ON` apenas remove o
  image-base (continua PIE). `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` é flag do toolchain
  NDK (não do box64) e existe desde r27 — condicional r28+ do script está correto e é
  conservador. NDK discovery com `sort -V` + fallbacks OK; versão não-numérica cai no
  ramo do NOTE (sem crash). Marcadores `BOX64_BIONIC_BUILD_FAILED`/`PAYLOAD_BUILD_FAILED`
  precedem `exit 1`/`die` (exit≠0) — confirmado linha a linha.
- **Payloads**: `make static CC=gcc` (-O1 -Wall -Wextra -static) → exit 0,
  `SOS_POC_STATIC_MATH_OK` (diff=0.000e+00, ichk=4201695289734782276) +
  `SOS_POC_STATIC_OK`; `gcc -static -O2 -Wall -Wextra` (caminho do script 1-b) → idem;
  `hello_dyn` → `SOS_POC_DYNAMIC_OK`, exit 0; **zero warnings** nos três builds;
  Makefile com TABs reais (`cat -A` ⇒ `^I`), targets phony, `CC` e `STATIC_LDFLAGS`
  sobrescritíveis (provado com `make -n` para os dois overrides).
- **App**: AGP 8.7.3 ⇔ Gradle 8.9 ⇔ JDK 17 coerentes (AGP 8.7 exige Gradle ≥ 8.9 e
  JDK 17; compileSdk 35 suportado desde AGP 8.6); `minSdk 28 == targetSdk 28`
  (D3, intencional); `ndk.abiFilters arm64-v8a`; `packaging.jniLibs.useLegacyPackaging=true`
  presente na **DSL** (não só no manifest) e consistente com
  `extractNativeLibs="true"` (AGP 8 rejeita divergência — ambas true); `lint
  abortOnError=false`; zero androidx (imports + grep). Manifest: sem `package=` (AGP 8
  usa namespace do Gradle), `exported="true"` + intent-filter (obrigatório p/ API 31+),
  ícone `@android:drawable/sym_def_app_icon` e tema `@android:style/Theme.Material.
  Light.NoActionBar` — referências de FRAMEWORK resolvem no aapt sem pasta `res/`
  (padrão suportado pelo AGP). MainActivity: UI 100% atualizada via `runOnUiThread`
  (único toque fora é dentro do click listener, na UI thread); caminhos absolutos via
  `new File(nativeLibDir, ...)`; espelho linha a linha `Log.i("SOSBox64", ...)`; PT-BR
  correto; sem androidx.
- **Modelo de exec (platform knowledge)**: `nativeLibraryDir` é caminho de exec
  APROVADO (rótulo SELinux `apk_data_file` com execute para o app; dir é
  somente-leitura p/ o app — não é alvo das restrições W^X de `targetSdk ≥ 29`, que
  atingem armazenamento gravável; é o padrão Winlator/Mobox). Payload passado como
  caminho absoluto; payload STATIC não precisa de `BOX64_PATH`/`BOX64_LD_LIBRARY_PATH`;
  `HOME`/`TMPDIR`/`BOX64_LOG` setados; `~/.box64rc` cai dentro do sandbox.
- **Consistência de docs**: README (tabela de jobs/runner/artefato) ✔; MILESTONES
  (job `box64-arm64`, markers contratados, soft-fail dinâmico) ✔; ON_DEVICE_TEST
  (mensagens de tela batem com MainActivity; artifact `SoS-PS5-Android-PoC-debug.apk`
  bate com build.yml) — única ref errada corrigida (F5); `.gitignore` ignora
  `build/`, `app/build/`, `local.properties`, `jniLibs/arm64-v8a/*.so` e **não** ignora
  `.gitkeep` ✔.
- **Higiene**: LICENSE com 18.092 bytes (texto canônico GPL-2.0); zero binários na
  árvore (`find . -type f | file` — só texto/vazio); zero padrões de token/credencial;
  nenhuma referência viva a `apk.yml` fora da nota histórica; `worklog.md` com seções
  Task 1, 1-b e 1-a ✔.

### Achados abertos (não corrigidos — nenhum bloqueia o push)

- **O1 (NIT)**: step Ensure SDK engole falha do sdkmanager (F7) — diagnóstico pior, sem
  risco de falso-verde.
- **O2 (NIT)**: sem Gradle wrapper (F9).
- **O3 (INFO — risco vivo)**: o build bionic do box64 com NDK NÃO é validável neste
  container (sem NDK/Android) — a 1ª execução do job `apk` é o teste real. Mitigações
  já no repo: retry `ARM_DYNAREC=OFF + NOLOADADDR=ON`, marcador
  `BOX64_BIONIC_BUILD_FAILED` + exit 1, fallback proot/rootfs documentado.
- **O4 (INFO)**: `--image-base=0x34800000` (lld, PIE) + VA 39-bit do kernel do aparelho
  é configuração upstream-suportada, mas só tem prova real no aparelho (critério 3 do M1).
- **O5 (INFO)**: Java do MainActivity não compilável localmente (JRE sem javac/SDK no
  container) — revisado por parse (jdk.compiler, sem erros de sintaxe) + compilação
  real ficará no job `apk`.

### Veredito

**GO** para push ao GitHub e disparo do CI (workflow `build`): não há blocker; os dois
MAJOR eram de UX/resiliência do app e já estão corrigidos e re-verificados por parse;
o CI prova o restante (dynarec + execução real + APK).

---

## Task 1-a — PoC do tradutor: payloads x86_64 + CI box64/ARM64 (2026-10-04)

**Escopo:** validar box64 (x86-64→ARM64, dynarec) em hardware ARM64 real
(runner `ubuntu-24.04-arm`) executando payloads x86_64 mínimos. Nada de APK
ainda (fica para a task 1-b).

### O que foi construído

| Arquivo | Propósito |
|---|---|
| `payloads/x86_64/hello_static.c` | Payload x86-64 puro libc, compila com `-static`: imprime diagnóstico (argv/pid/uid, fib recursivo, uname), `SOS_POC_STATIC_OK`, checksum determinístico (soma harmônica H_1000 em double + fold inteiro uint64) comparado a constantes esperadas e `SOS_POC_STATIC_MATH_OK`, `exit(0)`. Prova carregamento de ELF + syscalls + stdio + chamadas recursivas + aritmética SSE2 (double/int) traduzida fielmente. **Fonte canônica compartilhada** com a task 1-b (APK empacota como `libpayload64.so`; marcador `SOS_POC_STATIC_OK` é o que a Activity procura). |
| `payloads/x86_64/hello_dyn.c` | Payload x86-64 dinâmico (PT_INTERP + glibc): imprime `SOS_POC_DYNAMIC_OK`, `exit(0)`. Exercita o caminho de link dinâmico do box64 (interpretador guest + libc guest via `BOX64_LD_LIBRARY_PATH`). |
| `payloads/x86_64/Makefile` | Alvos `static`/`dynamic` sempre reconstróem (targets phony — evita binário velho mascarar override). `CC` sobrescrevível via linha de comando (`CC=x86_64-linux-gnu-gcc` no CI); `STATIC_LDFLAGS ?= -static` sobrescrevível para fallback visível sem `-static`. |
| `.github/workflows/build.yml` | Dois jobs: `box64-arm64` (ubuntu-24.04-arm) e `apk` (placeholder p/ task 1-b). |

### Lógica do job `box64-arm64` (ubuntu-24.04-arm)

1. Checkout deste repo + checkout de `ptitSeb/box64` @ `main` em `box64-src`
   (box64 NUNCA vai no repo — decisão D5).
2. `apt-get install cmake ninja-build gcc-x86-64-linux-gnu libc6-dev-amd64-cross patchelf`.
3. Configure do box64: `cmake -S box64-src -B box64-build -G Ninja -DARM_DYNAREC=ON
   -DCMAKE_BUILD_TYPE=RelWithDebInfo`; se falhar, **retry explícito com
   `-DARM_DYNAREC=OFF`** e marca `DYNAREC=OFF` no summary (distinção obrigatória).
   Observação: verificado no CMakeLists upstream + no próprio CI do box64
   (release.yml) que `-DARM_DYNAREC=ON` é a invocação canônica para host
   aarch64 genérico.
4. Build: `cmake --build box64-build -j2`.
5. Payloads com cross compiler: `make static CC=x86_64-linux-gnu-gcc`; se o
   link `-static` falhar, fallback VISÍVEL para build cross sem `-static`
   (modo anotado como `cross-DYNAMIC-fallback` no summary); se nem isso
   compilar → erro duro. `hello_dyn` sempre cross dinâmico.
6. Localiza `libc.so.6` e `ld-linux-x86-64.so.2` do pacote `libc6-amd64-cross`
   via `dpkg -L` (sem hardcode de caminho), faz `patchelf --set-interpreter`
   no `hello_dyn` e cria symlink de fallback `/lib64/ld-linux-x86-64.so.2`.
7. **Execução real (estático, obrigatório):**
   `BOX64_LOG=INFO BOX64_LD_LIBRARY_PATH=<cross libs> ./box64-build/box64
   payloads/x86_64/hello_static` — asserta `SOS_POC_STATIC_OK` **e**
   `SOS_POC_STATIC_MATH_OK` no stdout; senão **falha o job**.
8. **Execução real (dinâmico, stretch goal):** mesmo esquema com
   `payloads/x86_64/hello_dyn` — asserta `SOS_POC_DYNAMIC_OK`; se falhar,
   imprime `DYNAMIC_TEST_FAILED reason=...` no summary e `exit 0`
   (NÃO derruba o workflow).
9. Summary: SHA do box64, `DYNAREC=ON/OFF`, SHA256 dos payloads, stdout
   completo das duas execuções, linhas `STATIC_TEST_PASSED/FAILED` e
   `DYNAMIC_TEST_PASSED/FAILED`. Artifact `box64-arm64-poc` (binário box64 +
   payloads + logs de run/configure).

### Verificação local (host x86_64, gcc 14.2.0 — evidência real)

Comandos: `make clean && make static CC=gcc && make dynamic CC=gcc`
em `payloads/x86_64/`.

Execução do estático (`./hello_static`, exit=0 — saída idêntica em `-O1` via Makefile e em `-O2 -static` direto como no `scripts/ci-build-box64.sh`; re-verificada pela task 1-b após a re-fusão final de `hello_static.c`):

```
SOS-PS5 PoC: static x86-64 payload started
argc=1 argv0=./hello_static pid=2448 uid=1001
uname: sys=Linux nodename=c-6ac27714-14810412-65a2e21ed96b release=5.10.134-013.15.kangaroo.al8.x86_64 machine=x86_64
math: acc=7.485471 expected=7.485471 diff=0.000e+00 ichk=4201695289734782276 expected=4201695289734782276
SOS_POC_STATIC_MATH_OK
SOS_POC_STATIC_OK
```

`file hello_static`:

```
hello_static: ELF 64-bit LSB executable, x86-64, version 1 (GNU/Linux), statically linked, BuildID[sha1]=d5f97342112b523b5762d702ef68b49bdbb28aac, for GNU/Linux 3.2.0, not stripped
```

Execução do dinâmico (`./hello_dyn`, exit=0):

```
SOS_POC_DYNAMIC_OK
```

`file hello_dyn` (note o PT_INTERP que o box64 precisará resolver no CI):

```
hello_dyn: ELF 64-bit LSB pie executable, x86-64, version 1 (SYSV), dynamically linked, interpreter /lib64/ld-linux-x86-64.so.2, BuildID[sha1]=810b3834ffeebbba2fdae340b8c39deb95e7b175, for GNU/Linux 3.2.0, not stripped
```

Constantes esperadas geradas pelo MESMO loop compilado a parte (estável em
`-O0`, `-O1` e `-O2`, gcc x86_64): `H_1000 = 7.4854708605503433` (bits
`0x401df11f45f4e618`), `ichk = 4201695289734782276` (0x3a4f6b9abf12dd44 —
fold FNV-1a + finalizador murmur3 sobre os bits do acumulador e n=1000,
versão final re-mesclada 1-b; ver nota na colisão abaixo).
Comparações: double com tolerância 1e-9; inteiro exato. Sem intrinsics SSE
(baseline x86-64, SSE2 escalar via gcc), sem libm.

### Colisão com a task 1-b (resolvida, registrar para revisão)

Durante esta task, o agente da task 1-b reescreveu `hello_static.c` em
paralelo (diagnóstico argv/pid/fib/uname para o APK) e **removeu** o bloco de
matemática + `SOS_POC_STATIC_MATH_OK`, o que quebraria o assert do CI 1-a.
Resolução: versão MESCLADA em `payloads/x86_64/hello_static.c` mantendo o
diagnóstico da 1-b E o teste de matemática obrigatório da 1-a; verificada
localmente nos DOIS caminhos de build (Makefile `-O1` e direto `-static -O2`
como no `scripts/ci-build-box64.sh`). Regra para o futuro: não remover
`SOS_POC_STATIC_OK` nem `SOS_POC_STATIC_MATH_OK` deste arquivo.
> [1-b] Houve uma SEGUNDA sobrescrita concorrente (16:53, agente 1-b). Estado
> final re-mesclado pela 1-b: diagnóstico argv/pid/uid/uname + bloco de
> matemática da 1-a (H_1000 + fold). O `ichk` da versão final é
> `4201695289734782276` (fórmula do fold explícita no fonte; a fórmula
> original da 1-a não estava no fonte sobrevivente). Re-verificado
> localmente em `-O1 -static` e `-O2`: exit=0, `diff=0.000e+00`, `ichk`
> igual à constante. CI 1-a segue verde (greps só exigem os dois markers).

Overrides testados por `make -n` (cross compiler não existe no host local,
mas o dry-run prova a substituição):

```
x86_64-linux-gnu-gcc -O1 -Wall -Wextra -static -o hello_static hello_static.c   # make -n static CC=x86_64-linux-gnu-gcc
x86_64-linux-gnu-gcc -O1 -Wall -Wextra -o hello_dyn hello_dyn.c                 # make -n dynamic CC=x86_64-linux-gnu-gcc
gcc -O1 -Wall -Wextra  -o hello_static hello_static.c                           # make -n static CC=gcc STATIC_LDFLAGS=
```

`build.yml` validado por parse YAML (estrutura de jobs/steps íntegra; a chave
`on:` aparece como booleano no PyYAML 1.1 — comportamento conhecido e
aceito pelo parser do GitHub Actions).

### O que o CI vai provar (e o local não consegue)

- box64 compila de verdade em ARM64 com dynarec (`-DARM_DYNAREC=ON`).
- Um ELF x86_64 estático EXECUTA sob box64 em CPU ARM64 real, com FP/integer
  corretos (markers + checksum assertados).
- (Stretch) ELF x86_64 dinâmico carrega ld.so/glibc cross e roda.

### Riscos/observações vivos para o próximo agente

- Caminho das libs cross pode variar entre versões do `libc6-amd64-cross`
  (`/usr/x86_64-linux-gnu/lib64` vs `.../lib`): o workflow descobre via
  `dpkg -L`, não hardcode. Se o pacote mudar de nome, ajustar o step 6.
- glibc guest 2.39 (noble) sob box64 master: box64 upstream acompanha glibc
  nova, mas é o ponto mais provável de falha do teste DINÂMICO (que é
  soft-fail por design).
- Se `ARM_DYNAREC=ON` falhar em configurar, o summary mostrará
  `DYNAREC=OFF` + cauda do log — NÃO aceitar como "verde pleno" sem ler.
- Fallback do payload estático (sem `-static`) exercita o caminho dinâmico;
  se acontecer, o summary mostra `mode=cross-DYNAMIC-fallback` e o teste
  segue sendo obrigatório (hard fail se os markers não saírem).

---

## Task 1-b — App Android PoC (box64 bionic + payload via ProcessBuilder) (2026-10-04)

> NOTA: esta seção foi reconstituída pelo agente da Task 1-a após uma colisão
> de escrita concorrente em `docs/PROGRESS.md` (a versão original da Task 1-b
> foi sobrescrita; o conteúdo abaixo resume o worklog 1-b e os artefatos reais
> no repo). Detalhe completo em `/home/z/my-project/worklog.md`, seção 1-b.

- Escopo: APK `poc` (M1 critério 2) que executa o payload x86-64 estático via
  box64 no aparelho; UI PT-BR; espelho em logcat (tag `SOSBox64`).
- Projeto Gradle na raiz: `settings.gradle`, `build.gradle` (AGP 8.7.3),
  `gradle.properties`, `.gitignore`; `app/` com namespace/applicationId
  `br.deivid22srk.sosps5`, `minSdk 28`/`targetSdk 28` (decisão D3 — W^X
  relaxado p/ dynarec), `compileSdk 35`, `abiFilters arm64-v8a`,
  `useLegacyPackaging=true` + `extractNativeLibs="true"` (permite executar
  `libbox64.so` — box64 PIE renomeado — via ProcessBuilder), SEM androidx.
- `app/src/main/java/br/deivid22srk/sosps5/MainActivity.java`: Activity 100%
  programática; diagnóstico (API, ABI, nativeLibraryDir, presença de
  `libbox64.so`/`libpayload64.so`); checkbox BOX64_LOG; botão de execução;
  ProcessBuilder(`$nativeLibDir/libbox64.so`, `$nativeLibDir/libpayload64.so`)
  com redirectErrorStream, timeout 120 s, log linha a linha (`SOSBox64`) e
  veredito procurando o marcador `SOS_POC_STATIC_OK`.
- `scripts/ci-build-box64.sh`: NDK discovery ($ANDROID_HOME/ndk/* sort -V +
  fallbacks), 16KB page sizes só em r28+, clona box64 master (D5), build
  bionic tentativa 1 `ARM_DYNAREC=ON` → tentativa 2 `OFF` + `NOLOADADDR=ON`,
  falha total → `BOX64_BIONIC_BUILD_FAILED` + exit 1; payload cross
  `x86_64-linux-gnu-gcc -static` → `libpayload64.so` com checagem
  "x86-64 + statically linked" (`PAYLOAD_BUILD_FAILED`).
- `.github/workflows/apk.yml` (temporário — o pai fará merge no build.yml):
  [nota Task 1-c: merge JÁ REALIZADO — o job `apk` agora vive dentro de
  `.github/workflows/build.yml` (junto do `box64-arm64`); o arquivo apk.yml
  foi removido e não existe mais no repo. Conteúdo do job da época:
  ubuntu-latest, JDK temurin 17, Gradle 8.9, SDK 35, roda o script, sobe
  artifact do APK debug.]
- `docs/ON_DEVICE_TEST.md`: roteiro PT-BR de teste no aparelho (instalação,
  diagnóstico, execução, captura `adb logcat -s SOSBox64:V`).
- Validação local da 1-b sem SDK: `bash -n`, YAML/XML parseados; build real
  fica no CI. Risco do build bionic avaliado no fonte do box64 (suporte
  ANDROID upstream confirmado); fallback: proot + rootfs x86-64 (padrão
  Mobox) se `BOX64_BIONIC_BUILD_FAILED`.

---

## Task 1 — Diagnóstico inicial (2026-10-04, resumo)

- SoS-PS5 original é x86-64-only (host Windows/Linux); jogo exige dump do
  usuário em runtime. Diagnóstico completo:
  `/home/z/my-project/download/SoS-PS5-Android-Diagnostico.md`.
- Decisão A do usuário (ver PLAN.md): processo único x86-64 sob box64 no
  Android ARM64. Validado em CI ARM64 real (este repo, M1).

## Task: M1 — Validação de CI (run 37220048527, 2026-10-04)

**Resultado: VERDE (ambos os jobs).**

| Item | Evidência |
|---|---|
| box64 v0.4.5, `ARM_DYNAREC=ON`, compilado nativamente em `ubuntu-24.04-arm` | Banner no log: `Box64 arm64 v0.4.5 8b150dc with Dynarec built on Oct 4 2026` |
| Payload estático x86-64 executado sob box64 (hardware ARM64 real) | `run_static.log`: `SOS_POC_STATIC_OK` + `SOS_POC_STATIC_MATH_OK` (checksum exato: `ichk=4201695289734782276`, `diff=0.000e+00`) |
| Payload dinâmico x86-64 (glibc guest via cross libc6-amd64) sob box64 | `run_dynamic.log`: `SOS_POC_DYNAMIC_OK` |
| APK PoC empacotado | Artifact `SoS-PS5-Android-PoC-debug.apk` (15,3 MB) — box64 NDK r29/bionic + payload estático como jniLibs |

Link do run: https://github.com/deivid22srk/SoS-PS5-Android/actions/runs/37220048527

Pendente do M1: critério 3 (execução no aparelho do usuário) — `docs/ON_DEVICE_TEST.md`.

---

# M2 — Núcleo AnyPS5 em Linux x86-64 (2026-10-05)

Contrato: `docs/M2-LINUX-HOST.md`. Sub-agentes: 2-b (host), 2-c (CI), 2-d (app),
2-e (revisor crítico independente — veredito **GO**, 0 defeitos de código, 4
correções documentais/higiene aplicadas e re-validadas).

## Task 2-a — Mapeamento do launcher Win32 + spec do host (2026-10-05)

- Mapeado `launcher/launcher.cpp` (`wWinMain`, tabela completa em
  `docs/M2-LINUX-HOST.md` §1): validação de arquivos → preparo via relinker →
  execução. A "tela de arquivos ausentes" é a validação do launcher (mensagens
  "The game files were not found" / "Some game files are missing").
- **Descoberta-chave**: o relinker upstream JÁ tem caminho Linux nativo — sem
  `--windows`, `main.cpp:72` imprime `System: Linux` e usa `LinuxElfPatcher`
  (`main.cpp:100`); saída = ELF executável com `DT_RUNPATH $ORIGIN/libs`. O
  CMake do relinker é standalone-incluível; não depende de SDL2/ffmpeg.
- Submódulos pinados: SDL2 `4b69833bc54abf3dd3288d4aa7afbba527775e5b`.
- Risco "camada Linux parcial" do PLAN.md atualizado para MITIGADO PARCIALMENTE.

## Task 2-b — Host Linux `anyhost` (2026-10-05)

- `patches/0002-sos-linux-host.patch` (sobre upstream pinado): adiciona
  `host/anyhost.cpp` (347 linhas) + `host/CMakeLists.txt` (superprojeto
  standalone; NÃO altera CMake raiz; só `host/*` no patch).
- `anyhost`: CLI `--game-dir/--version/--help`; SDL2 real
  (`SDL_Init(VIDEO|GAMECONTROLLER)` com fallback interno dummy); markers
  `SOS_HOST_STARTED` / `SOS_HOST_SDL2_OK driver=<n>` /
  `SOS_HOST_MISSING_GAME_FILES reason=<r>` / `SOS_HOST_VALIDATE_OK` /
  `SOS_HOST_SDL2_FAIL` / `SOS_HOST_INTERNAL_ERROR`; ordem de validação idêntica
  ao `wWinMain` (eboot.bin→eboot.elf → magic ELF → sce_sys → Media → libs/ →
  tools/relinker); mensagens PT-BR do launcher; exit 1 nos missing; exit 2
  interno; sem sinais/abort.
- SDL2 estática mínima dummy-only (X11/Wayland/KMSDRM/Vulkan/Render OFF).
  Nota: SDL2 2.33 traz o driver headless `offscreen` ligado por default — sem
  env ele é escolhido antes do dummy; tratado como headless (mesmo tratamento).
- Validação local (host x86-64, g++ 14.2, cmake 4.4.4): AMBOS `anyhost` e
  `relinker` saem `ELF 64-bit LSB executable, x86-64, statically linked`.
  Smokes (dir vazio → `reason=no-eboot`; +eboot falso → `no-sce_sys`; +sce_sys
  +Media → `no-libs`; +libs → `no-relinker`; magic MZ → `not-elf`;
  `--help/--version` exit 0; opção inválida exit 2): TODOS com exit limpo,
  zero sinais. Relinker sem args: usage em stderr, exit 1 limpo.
- Autocontenção provada: patch aplicado em worktree limpo do SHA pinado →
  configure+build do zero → verde; smokes reproduzidos.
- Evidência independente do orquestrador: smoke re-executado
  (`exit=1`, 3 markers, PT-BR) — PASS.

## Task 2-c — CI (2026-10-05)

- `build.yml` (+328/−2): novo job `host-linux` (ubuntu-latest): checkout
  upstream pinado + SDL2 pinada (actions/checkout standalone), patch 0002
  idempotente (falha ⇒ `SOS_PATCH_FAILED`), build, `file` exige
  `x86-64 + statically linked` (senão `HOST_NOT_STATIC`), smoke nativo HARD
  (RC==1 + `SOS_HOST_MISSING_GAME_FILES reason=no-eboot` + `SOS_HOST_SDL2_OK` +
  `SOS_HOST_STARTED` + sem `Fatal signal`; senão `HOST_NATIVE_SMOKE_FAILED`),
  artifact `host-linux-x64` (flat, if-no-files-found: error).
- `box64-arm64` estendido (`needs: host-linux`): steps M1 preservados verbatim
  (regressão); novos: download do artifact + `chmod +x`; HARD assert do host
  sob box64 (`BOX64_LOG=INFO`, RC==1, 3 markers, sem Fatal signal; falha ⇒
  `HOST_UNDER_BOX64_FAILED` + exit 1; sucesso ⇒ `HOST_UNDER_BOX64_PASSED`);
  relinker sob box64 = smoke soft (informativo). Logs no summary e no artifact.
- `apk` ajustado (`needs: host-linux`): cp do anyhost →
  `app/src/main/jniLibs/arm64-v8a/libanyhost64.so` + `chmod` + `file` no
  summary (fora do `ci-build-box64.sh`, contrato do script preservado).
- Validações: PyYAML ~95 asserts PASS; `bash -n` nos 24 run blocks; M1
  comprovadamente preservado (diff estrutural step-a-step).

## Task 2-d — App Android + doc on-device (2026-10-05)

- `MainActivity.java`: refatoração da lógica comum (`execUnderBox64` +
  `ExecResult`) SEM mudar comportamento M1 (textos byte-idênticos verificados
  por diff de literais); novo botão "M2: Rodar host AnyPS5 (validação)" roda
  `libbox64.so libanyhost64.so --game-dir <filesDir>/game` (dir vazio criado);
  veredito VERDE ⇔ `SOS_HOST_MISSING_GAME_FILES` && `SOS_HOST_SDL2_OK` &&
  exit==1 (texto PT-BR explica que é o esperado; evidência SDL2 mostrada);
  exit ≥128 decodificado (`explainSignalExit`); watchdog 120 s; espelho
  logcat tag `SOSBox64`; zero androidx, APIs ≤28.
- `docs/ON_DEVICE_TEST.md`: seção 7 "Teste M2" (bloco NÃO É BUG, passo a passo,
  aparência do sucesso, logcat, tabela de anormalidades, próximo passo).

## Task 2-e — Revisão crítica independente (2026-10-05)

- Veredito: **GO**. Nenhum defeito de código (patch, build.yml, app).
- Corrigido: F1 contrato atrasado vs código (offscreen headless, duplo-fail
  SDL2 → INTERNAL_ERROR/exit 2, relink sem loop de restart — agora
  documentados no `M2-LINUX-HOST.md`); F2 doc on-device previa `driver=dummy`
  no aparelho quando o correto/observado é `offscreen` (dummy também verde);
  F3 datas/status do MILESTONES.md; F4 `.gitignore` +5 dirs de reprodução.
- Registrados: needs-gating (host-linux falho skipa M1 nesse run — intencional);
  F7 (reason≠no-eboot pode ficar verde no futuro — decisão M3+).

## Pendente para fechar o M2

1. CI verde (critérios 1 e 2) — aguardando push.
2. Teste no aparelho do usuário com o APK (critério 3) — `docs/ON_DEVICE_TEST.md` §7.
