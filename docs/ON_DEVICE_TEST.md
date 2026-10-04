# Teste no aparelho — PoC box64 (M1 + M2)

Este roteiro valida a prova de conceito: **rodar um ELF x86-64 dentro do app Android
ARM64 usando o box64** e ver o resultado na tela e no logcat — o payload estático do
M1 (seção 3) e o host AnyPS5 do M2 (seção 7).

## 0. Pré-requisitos

- Aparelho Android **arm64-v8a** (de preferência Android 13 ou mais novo; em versões
  anteriores o app avisa e continua).
- APK do artifact do CI: **`SoS-PS5-Android-PoC-debug.apk`**
  (GitHub Actions → workflow **build** → job "APK PoC (box64 + payload x86-64)" → artifact).
- Opcional: `adb` do platform-tools no computador (para logcat).
- Cadeado: permitir "instalar apps desconhecidos" para o gerenciador de arquivos/navegador.

## 1. Instalar o APK

1. Baixe o artifact `SoS-PS5-Android-PoC-debug.apk` (zip do CI) e descompacte.
2. Toque no APK e confirme a instalação (não precisa desativar Play Protect para
   instalar; se ele reclamar depois, escolha "Instalar mesmo assim").
3. Abra o app **"SoS PS5 - PoC box64"**.

## 2. Conferir o diagnóstico na tela

Na abertura, o app mostra (sem precisar fazer nada):

```
SoS PS5 - box64 (M1 + M2)
Versão do Android: API 33            (exemplo)
ABI principal: arm64-v8a
Pasta de bibliotecas nativas: /data/app/.../lib/arm64
libbox64.so: presente
libpayload64.so: presente
libanyhost64.so: presente
```

- Se aparecer `AUSENTE` em qualquer um dos três: o APK foi gerado sem os binários —
  o CI falhou antes (ver o job), não adianta testar.
- Em Android < 13 aparece também: "Aviso: este app é destinado a Android 13+;
  continuando mesmo assim...".

## 3. Executar o teste

1. (Opcional) Marque **"Log detalhado do box64 (BOX64_LOG=DEBUG)"** se quiser mais
   verbosidade do box64.
2. Toque em **"Executar teste x86-64 (box64)"**.
3. Aguarde ~2–10 s (primeira execução traduz mais código).

### O que é SUCESSO (tela)

Linha em verde:

```
SUCESSO: SOS_POC_STATIC_OK detectado
```

e, no quadro de saída, linhas do box64 terminando com algo como:

```
SOS-PS5 PoC: static x86-64 payload started
argc=1 argv0=... pid=1234 uid=10123
uname: sys=Linux nodename=localhost release=... machine=x86_64
math: acc=7.485471 expected=7.485471 diff=0.000e+00 ichk=4201695289734782276 expected=4201695289734782276
SOS_POC_STATIC_MATH_OK
SOS_POC_STATIC_OK
```

`machine=x86_64` no uname é o guest; o host é o Android do aparelho — ou seja, o
código x86-64 realmente rodou traduzido pelo box64.

### O que é FALHA (tela)

- `FALHA — ver saída abaixo (código de saída: N).` → leia a saída no quadro abaixo;
  normalmente o box64 imprime o motivo (ex.: payload inválido).
- `libbox64.so não encontrado em ...` → APK sem o binário (problema de CI).
- `FALHA — box64 não terminou em 120 s` → travou; mande o logcat (abaixo).

## 4. Capturar o logcat (importante para o relatório do M1)

Com o aparelho conectado por USB (depuração USB ativada):

```bash
# Captura só o nosso canal (recomendado):
adb logcat -c
# ... toque em "Executar teste x86-64 (box64)" ...
adb logcat -d -s SOSBox64:V > sosbox64.log

# Visão ampla (box64 também imprime em stdout/stderr do processo, que a Activity
# já espelha na tag SOSBox64; este grep pega qualquer outra menção direta):
adb logcat -d | grep -iE 'box64|payload' > sosbox64-raw.log
```

Cole o `sosbox64.log` no relatório/issue do M1. As primeiras linhas devem mostrar o
banner de diagnóstico do app e depois o log do box64 (`BOX64_LOG=INFO` imprime
informações de tradução/execução).

## 5. Resultado esperado — resumo

| Onde | SUCESSO | FALHA |
|---|---|---|
| Tela | `SUCESSO: SOS_POC_STATIC_OK detectado` (verde) | `FALHA — ...` (vermelho) + saída |
| Saída | linha `SOS_POC_STATIC_OK` presente | marcador ausente / exceção descrita |
| Logcat | tag `SOSBox64` com todo o stdout do box64 | mensagem de erro com causa em PT-BR |

Depois do teste, desinstale normalmente (ou deixe instalado — o app não roda nada em
background).

## 6. Se falhar — como o app ajuda no diagnóstico

O app decodifica códigos de saída ≥ 128 (processo morto por sinal `exit − 128`):

| Código | Sinal | Significado provável |
|---|---|---|
| 136 | SIGFPE(8) | divisão por zero / float inválido no guest |
| 139 | SIGSEGV(11) | acesso inválido à memória (bug de tradução/mapeamento) |
| 134 | SIGABRT(6) | abort() — assertion do box64 ou do guest |
| 137 | SIGKILL(9) | morto pelo sistema (ex.: OOM) |
| 159 | SIGSYS(31) | **syscall bloqueado pelo seccomp do Android** — confira se o APK foi gerado com `patches/0001-android-seccomp-robust-list.patch` (box64 pinado + patch; ver `scripts/ci-build-box64.sh`) |

Histórico: o 1º teste on-device (APK v1) morreu exatamente com 159 —
`set_robust_list` (x86-64 273 → arm64 99) repassado direto ao kernel. Corrigido no
APK v2; se um APK novo ainda reportar 159, o APK não contém o patch (verifique no
log do CI a linha `patch applied cleanly on box64 abfb8c3...`).

Linhas de logcat com `avc: denied ... name="tests"` e `sh: lscpu: inaccessible or not
found` são ruído conhecido do box64 no Android (não fatais).

## 7. Teste M2 (host AnyPS5 — arquivos ausentes)

A partir do M2 o APK também carrega o **host Linux do AnyPS5** (`anyhost`, empacotado
como `jniLibs/arm64-v8a/libanyhost64.so`). Este teste roda o host sob o box64 com
`--game-dir` apontando para uma pasta **VAZIA** dentro do sandbox do app
(`<filesDir>/game`, criada pelo próprio app antes de executar).

> **NÃO É BUG:** "arquivos do jogo ausentes" é o comportamento CORRETO e ESPERADO
> desta fase. Nesta etapa do projeto ainda não há dump do jogo no aparelho; o que o
> M2 prova é que o host AnyPS5 (binário x86-64 + SDL2 estática) roda sob o box64 e
> atinge a "tela de arquivos do jogo ausentes" de forma LIMPA — exit code 1, sem
> crash, sem sinal (paridade com o launcher Win32: sem `eboot.bin`/`eboot.elf` na
> pasta → mensagem ao usuário + exit 1).

### 7.1 Pré-requisitos

- APK do CI que já inclui o M2 — mesmo artifact e mesmo procedimento de instalação da
  seção 1 (M1): GitHub Actions → workflow **build** → job "APK PoC (box64 + payload
  x86-64)" → `SoS-PS5-Android-PoC-debug.apk`. Pode instalar por cima do app já
  instalado do M1.
- No diagnóstico de abertura (seção 2), confira `libanyhost64.so: presente`. Se
  aparecer `AUSENTE`, o APK foi gerado sem o host — verifique o job apk do CI.
- Opcional: `adb` do platform-tools para o logcat.

### 7.2 Passo a passo

1. Abra o app (nome no launcher: **"SoS PS5 - PoC box64"**; título na tela:
   **"SoS PS5 - box64 (M1 + M2)"**).
2. (Opcional) Marque **"Log detalhado do box64 (BOX64_LOG=DEBUG)"**.
3. Toque no botão **"M2: Rodar host AnyPS5 (validação)"** (logo abaixo do botão do
   teste M1, que continua funcionando como regressão).
4. Aguarde alguns segundos (limite interno: 120 s — depois disso o app mata o
   processo e reporta timeout).

### 7.3 O que é SUCESSO (tela)

Linha em **verde**:

```
SUCESSO (M2): o host AnyPS5 rodou sob box64 e atingiu a tela de arquivos do jogo ausentes (exit=1 limpo, sem crash). Instale os arquivos do jogo em um marco futuro.
Evidência SDL2: SOS_HOST_SDL2_OK driver=offscreen
Marker: SOS_HOST_MISSING_GAME_FILES reason=no-eboot
```

E no quadro de saída, algo como:

```
SOS_HOST_STARTED
SOS_HOST_SDL2_OK driver=offscreen
Os arquivos do jogo não foram encontrados...   (texto PT-BR do host)
SOS_HOST_MISSING_GAME_FILES reason=no-eboot
```

Critérios exatos do verde: stdout contém **`SOS_HOST_MISSING_GAME_FILES`** **E**
**`SOS_HOST_SDL2_OK`** **E** exit code == **1**. A linha `SOS_HOST_SDL2_OK
driver=...` é a evidência de que a SDL2 estática inicializou sob box64 — no aparelho
o esperado é `driver=offscreen` (é o driver headless que a SDL2 mínima sobe por
default quando não há X11/Wayland/KMSDRM; `driver=dummy` também é válido — ambos
indicam "sem tela real"; o CI ARM64 mostra o mesmo comportamento).

### 7.4 Capturar o logcat

```bash
# Captura ao vivo (deixe rodando e toque no botão M2):
adb logcat -s SOSBox64:V > sosbox64-m2.log

# Ou dump pontual depois de executar:
adb logcat -c
# ... toque no botão M2 e aguarde ...
adb logcat -d -s SOSBox64:V > sosbox64-m2.log
```

As linhas do host aparecem na mesma tag `SOSBox64` usada pelo M1 (o app espelha o
stdout do processo linha a linha). Cole o `sosbox64-m2.log` no relatório/issue do M2.

### 7.5 Resultados anormais (tabela de diagnóstico)

| Sintoma na tela | Provável causa | O que fazer |
|---|---|---|
| `FALHA (M2) — ... (código de saída: 159). O processo foi morto pelo sinal 31 (SIGSYS)` | **seccomp do Android bloqueou uma syscall arm64** nova gerada pela SDL2 estática (hidapi/threads) | mande o logcat; provavelmente será preciso ampliar os stubs de `patches/0001-android-seccomp-robust-list.patch` |
| exit ≠ 1 e **nenhuma linha `SOS_HOST_SDL2_OK`** na saída | a SDL2 falhou ao inicializar **mesmo com o driver dummy** (init de vídeo) | verifique se existe `SOS_HOST_SDL2_FAIL reason=...` na saída e cole a saída integral na issue |
| `FALHA (M2) — o host AnyPS5 não terminou em 120 s (processo encerrado à força)` | travamento > 2 min — o **watchdog** do app matou o processo | mande o logcat completo; suspeitar de loop na tradução do box64 (dynarec) |
| `FALHA (M2) — ... (código de saída: 2)` | erro interno do host (marker `SOS_HOST_INTERNAL_ERROR`) | cole a saída integral — reason do marker indica o erro interno |
| `libanyhost64.so não encontrado em ...` | APK gerado **sem** o binário do host | o job apk do CI falhou antes (ver evidência `file` no summary do run) |
| `exit=1 foi retornado, mas os markers esperados não apareceram` | host rodou e saiu com 1 sem imprimir os markers (binário antigo/quebrado no APK) | confira no log do CI se o `libanyhost64.so` vem do artifact `host-linux-x64` do job `host-linux` |

Ruído conhecido (não fatais): as mesmas linhas `avc: denied ...` e `sh: lscpu` da
seção 6 podem aparecer também neste teste.

### 7.6 E depois?

**Resultado (2026-10-05, 1º teste on-device): SUCESSO — M2 CONCLUÍDO.**
motorola edge 30 fusion (Android 14, SDK 34, arm64-v8a), APK do run
[37230357330](https://github.com/deivid22srk/SoS-PS5-Android/actions/runs/37230357330)
(sha 7ce8ee7): diagnóstico de abertura confirma as 3 jniLibs presentes
(`libbox64.so`/`libpayload64.so`/`libanyhost64.so`); host executado como
`libbox64.so libanyhost64.so --game-dir <filesDir>/game (BOX64_LOG=DEBUG)`;
logcat mostra `SOS_HOST_STARTED` → `SOS_HOST_SDL2_OK driver=offscreen` →
texto PT-BR → `SOS_HOST_MISSING_GAME_FILES reason=no-eboot` e o veredito do
app `SUCESSO (M2): missing game files atingido (exit=1, SOS_HOST_SDL2_OK
driver=offscreen)`. **Zero** `Fatal signal`/SIGSEGV/SIGSYS no log; o ruído
conhecido (`avc: denied`, `sh: lscpu`) apareceu e foi não fatal, exatamente
como previsto nas seções 6 e 7.5. Evidência bruta:
`docs/evidence/m2-device-logcat-2026-10-05.txt`.

Com o M2 verde no aparelho, o próximo passo (marco futuro) é prover os arquivos do
jogo descriptografados (`eboot.elf`/`eboot.bin`, `sce_sys`, `Media`) no diretório do
app — aí o mesmo host avança para `SOS_HOST_VALIDATE_OK` e o caminho do relinker.

> **APK de referência do M2 (CI verde):** run
> [37230357330](https://github.com/deivid22srk/SoS-PS5-Android/actions/runs/37230357330)
> (sha 7ce8ee7, 2026-10-05) — artifact `SoS-PS5-Android-PoC-debug.apk`
> (18,6 MB; contém `libbox64.so` + `libpayload64.so` + `libanyhost64.so`).
> No CI, o host sob box64 no runner ARM64 atinge
> `SOS_HOST_MISSING_GAME_FILES reason=no-eboot` com `SOS_HOST_SDL2_OK
> driver=offscreen` e exit 1 limpo (`HOST_UNDER_BOX64_PASSED`).

## 8. Teste M3 (host + libs: SDL2 wrapper, FFmpeg, freetype)

O M3 acrescenta ao APK as bibliotecas do runtime e prova o caminho completo do
loader: o host (`anyhost`, agora x86-64 **dinâmico**) carrega a SDL2 via
**wrapper nativa ARM64** (compilada dentro do box64 — `wrappedsdl2`), e as
liberias guest x86-64 `libavcodec_sos.so`/`libavutil_sos.so`/
`libfreetype_sos.so` são resolvidas pelo box64 via `BOX64_LD_LIBRARY_PATH`
apontando para o `nativeLibraryDir` do app. O app também monta a "rootfs
mínima" (4 arquivos desde o M3 fix #1): copia `libSDL2_sos_native.so` para
`<filesDir>/rootfs/lib/libSDL2-2.0.so.0` — é por esse nome que a SDL2 nativa é
dlopen-ed pelo wrapper — e extrai a **glibc guest x86-64**
(`libc.so.6`, `ld-linux-x86-64.so.2`, `libm.so.6`) de `assets/rootfs` para o
MESMO diretório, já no `BOX64_LD_LIBRARY_PATH` (contrato M3 §7 — sem a glibc
real o host dinâmico morre com SIGSEGV, KB-002).

### 8.1 Pré-requisitos

- **APK do M3 fix #1 (versionCode 2 / versionName 0.2.0-poc-m3)** — contém a
  glibc guest x86-64 (KB-002). O APK do run 37239106249 (versionCode 1) está
  SUPERADO: nele o M3 falha com exit=139 (SIGSEGV por glibc ausente). Use o
  artifact `SoS-PS5-Android-PoC-debug.apk` do run do fix #1:
  [37242064025](https://github.com/deivid22srk/SoS-PS5-Android/actions/runs/37242064025)
  (25,6 MB; 7 jniLibs + assets/rootfs). O aparelho aceita instalar por cima
  (versionCode cresceu).
- Como conferir se o APK novo está instalado: no quadro de diagnóstico de
  abertura, além das **7 jniLibs** `presente`, a linha
  `glibc guest x86-64 (assets/rootfs):` deve mostrar
  `presentes (libc.so.6, ld-linux-x86-64.so.2, libm.so.6)`. Se aparecer
  `AUSENTES (0/3 ...)`, o APK é o antigo.
- A interface agora rola por inteiro (ScrollView de página inteira — relato
  "não dá pra rolar" de 2026-10-05 corrigido): título, status, botões,
  veredito e log rolam juntos.

### 8.2 Passo a passo

1. Abra o app (título: **"SoS PS5 - box64 (M1 + M2 + M3)"**).
2. (Opcional) Marque **"Log detalhado do box64 (BOX64_LOG=DEBUG)"**.
3. Toque no botão **"M3: Rodar host AnyPS5 + libs (FFmpeg/freetype)"** (abaixo
   do M2). Os botões M1 e M2 continuam funcionando como regressão.
4. Aguarde alguns segundos (watchdog: 120 s).

### 8.3 O que é SUCESSO (tela)

Linha em **verde**:

```
SUCESSO (M3): host + libs OK sob box64 (SDL2 via wrapper nativa, FFmpeg e freetype carregados, exit=1 limpo).
```

com as evidências extraídas (quando disponíveis): `driver=offscreen`,
`version=2.13.3` (freetype), `avf=6.1.2` (FFmpeg). No quadro de saída:

```
SOS_HOST_STARTED
SOS_HOST_SDL2_OK driver=offscreen
SOS_HOST_FREETYPE_OK version=2.13.3
SOS_HOST_FFMPEG_OK codec=hevc,h264 avf=6.1.2
Os arquivos do jogo não foram encontrados...   (texto PT-BR do host)
SOS_HOST_MISSING_GAME_FILES reason=no-eboot
```

Critérios exatos do verde (iguais ao CI): exit==1 **E**
`SOS_HOST_MISSING_GAME_FILES` com linha terminando **exatamente**
`reason=no-eboot` **E** `SOS_HOST_SDL2_OK` **E** `SOS_HOST_FFMPEG_OK` **E**
`SOS_HOST_FREETYPE_OK`, sem `SOS_HOST_M3_FAIL`/`SOS_HOST_INTERNAL_ERROR`/
`SOS_HOST_M3_SKIPPED`. Regra nova (contrato M3 §4): qualquer **outro**
`reason=` (`not-elf`, `no-sce_sys`, `no-media`, `no-libs`, `no-relinker`) é
**FALHA** (na pasta vazia do teste só `no-eboot` é alcançável — reason
diferente = investigar antes de re-testar).

### 8.4 Capturar o logcat

```bash
adb logcat -s SOSBox64:V > sosbox64-m3.log
```

### 8.5 Resultados anormais (tabela de diagnóstico)

| Sintoma na tela | Provável causa | O que fazer |
|---|---|---|
| `FALHA (M3) — SOS_HOST_M3_SKIPPED` | APK construído sem as sondas (binário desatualizado) | confira se o APK veio do run 37239106249 ou posterior |
| `SOS_HOST_M3_FAIL lib=ffmpeg reason=...` | decoder não registrou / `avcodec_open2` falhou sob box64 | mande o logcat completo + BOX64_LOG=DEBUG |
| `SOS_HOST_M3_FAIL lib=freetype reason=...` | `FT_Init_FreeType` falhou | idem |
| `FALHA — libSDL2_sos_native.so ausente no APK` | APK sem a SDL2 nativa (build apk antigo) | baixe o artifact do run correto |
| `FALHA — Falha ao extrair a glibc guest ... (assets/rootfs)` | APK sem os assets de glibc (build apk antigo, pré-fix #1) | baixe o APK do run do fix #1 (versionCode 2) |
| exit 139 com `Symbol ... not found` (locale/gettext/`_chk`/`__libc_start_main`) no logcat | glibc guest ausente no `BOX64_LD_LIBRARY_PATH` (KB-002) — APK pré-fix #1 ou extração falhou | confira a linha `glibc guest x86-64` no diagnóstico de abertura; mande o logcat se `presentes` e ainda assim falhar |
| exit 139 / Fatal signal no logcat | SIGSEGV sob dynarec (KB-001 reaparecido) | mande o logcat; a matriz KB-001 volta ao CI |
| `SOS_HOST_INTERNAL_ERROR reason=m3-probe-failed` | qualquer sonda falhou (exit 2) | cole a saída integral |
| exit ≠ 1 sem markers de sonda | loader não resolveu alguma lib guest | logcat + conferir as 7 libs no diagnóstico de abertura |

Ruído conhecido (não fatais): mesmas linhas `avc: denied ...` e `sh: lscpu`
das seções 6/7. Nenhum patch seccomp novo foi necessário no M3.

> **APK de referência:** use sempre o do run mais recente listado no
> PROGRESS.md. O run [37239106249](https://github.com/deivid22srk/SoS-PS5-Android/actions/runs/37239106249)
> (sha deba957, 2026-10-05) foi o CI verde dos critérios 1-3, mas o SEU APK
> (versionCode 1) está superado pelo M3 fix #1: no aparelho ele falha com
> exit=139 por falta da glibc guest (KB-002) — use o APK do run do fix #1
> (versionCode 2 / 0.2.0-poc-m3). No CI ARM64 o host M3 passou **sem**
> `BOX64_DYNAREC_SAFEFLAGS` (KB-001 reavaliado e fechado: wrapper nativo
> eliminou o gatilho).
