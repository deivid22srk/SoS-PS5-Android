# M2 — Host Linux do AnyPS5 (especificação e mapeamento do launcher Win32)

Fonte: `OverkillLabs2/SoS-PS5` @ `757692f6d7f41c557efa52db708b9d4d0c1b9a03` (pinado).
Status: **M2-a concluído (2026-10-05)**; **M2-b/M2-c/M2-d entregues (2026-10-05)** — revisão
independente M2-e concluída no mesmo dia (ver worklog). Desvios do texto original desta
spec que viraram padrão na entrega e foram APROVADOS na revisão estão marcados com
**[M2-e]**.

## 1. Mapeamento do launcher Win32 (`launcher/launcher.cpp`, `wWinMain`)

O launcher Windows NÃO contém código do jogo: ele valida arquivos, prepara o
executável via relinker e dispara o jogo. Fluxo real (linhas do fonte):

| Passo | Função/linhas | O que faz | Paridade Linux (anyhost) |
|---|---|---|---|
| 1 | `wWinMain` 871-876 | Mutex de instância única (`Local\SonsOfSpartaLauncher`) | **Fora do escopo M2** (específico de desktop GUI) |
| 2 | 878-882 | `directory` = pasta do exe; `SetCurrentDirectory` | `--game-dir` (default: diretório atual) |
| 3 | 884-893 | Descobre `eboot.bin` (ou `eboot.elf`) e prepara `prep/eboot.elf` via `ConvertOrCopy` | idem (copiar se já ELF; `ExtractSelf` marcado como caminho não testado no M2) |
| 4 | 894-899 | Sem eboot → **"The game files were not found"** + exit 1 | **TELA-ALVO DO M2**: mesmo texto (PT-BR) + marker `SOS_HOST_MISSING_GAME_FILES reason=no-eboot` |
| 5 | 900-902 | `IsElf` (magic `7F 45 4C 46`) → "not decrypted" | `reason=not-elf`, exit 1 |
| 6 | 904-921 | Obrigatórios `LAUNCHER_REQUIRED = "sce_sys;Media"` | `reason=no-sce_sys` / `reason=no-media`, exit 1 |
| 7 | 922-925 | Programas: `libs/` (dir) e `tools/relinker.exe` | `reason=no-libs` / `reason=no-relinker` (binário `tools/relinker` sem `.exe`), exit 1 |
| 8 | 927-945 | GDI+/ícone/atalho/dialog de opções | Fora do escopo M2 (sem GUI desktop) |
| 9 | 953-988 | `PrepareModules` (converte `*.prx` p/ `prep/sce_module`) + relinker `--windows --registry --windows-diagnostics --skip-syscall-check --exclude/defer-sce-module ... "<prep/eboot.elf>" "<sos_runtime.exe>"` | Caminho Linux: `tools/relinker --registry --skip-syscall-check --exclude-sce-module ... --defer-sce-module ... <prep/eboot.elf> sos_runtime` (SEM `--windows`/`--windows-diagnostics`), `+x` no output |
| 10 | 990 | `LinkIntoApp0(Media, sce_sys)` (junction Windows) | No Linux o relinker espera `libs/` + `app0/` ao lado do executável (saída do relinker imprime o layout) — anyhost cria symlinks `app0/Media`, `app0/sce_sys` quando prepara |
| 11 | 992-1062 | Roda `sos_runtime.exe` com logs em `logs/game.log`, reinício em crash, journal | `exec` do binário relinkado com stdout/stderr em `logs/game.log` (implementado; **não exercido no M2** — exige dump do jogo) |

Fatos que confirmam SDL2 no runtime: janela do jogo tem classe `SDL_app` e título
`… | FPS: …` (launcher.cpp:410-424, `RequestFullscreen`); CMake raiz compila SDL2
estática (`SDL_SHARED OFF`, `SDL_STATIC ON`, `SDL_VULKAN ON`).

### Descoberta-chave sobre o relinker (reduz risco do M2)

- `relinker` **já é multi-alvo nativamente**: sem `--windows`, `main.cpp:72` imprime
  `System: Linux` e usa `LinuxElfPatcher` (main.cpp:100-108); saída = ELF Linux
  executável com `DT_RUNPATH $ORIGIN/libs` (default `--rpath "$ORIGIN/libs"`).
- `core/relinker/CMakeLists.txt` contém apenas o target `relinker` (sem
  `project()`): pode ser incluído por um superprojeto nosso desde que este defina
  `CMAKE_CXX_STANDARD 20` etc.
- O relinker não depende de SDL2/ffmpeg/freetype — build isolado é possível sem
  tocar no CMake raiz.
- Submódulos pinados: SDL2 `4b69833bc54abf3dd3288d4aa7afbba527775e5b`.

## 2. Especificação do `anyhost` (host Linux x86-64 mínimo)

Arquivo novo (via patch `patches/0002-sos-linux-host.patch` sobre o upstream pinado):
`host/anyhost.cpp` + `host/CMakeLists.txt` (superprojeto standalone; NÃO altera o
CMakeLists raiz).

### 2.1 CLI

```
anyhost [--game-dir <dir>] [--version] [--help]
```
- `--game-dir` (default: `.` — paridade com o Win32, que roda na pasta do jogo).
- Sem game-dir válido → comportamento de arquivos ausentes (não é erro interno).

### 2.2 SDL2 (evidência real, não decorativo)

- `SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER)`; se falhar, tentar de novo
  com `SDL_VIDEODRIVER=dummy` forçado (setenv) antes do 2º `SDL_Init`.
- **[M2-e]** Drivers headless válidos: `dummy` **e** `offscreen`. A SDL 2.33 compila
  `SDL_OFFSCREEN` por default e é ele quem sobe primeiro na SDL mínima (X11/Wayland/
  KMSDRM desligados; ordem de bootstrap em `SDL_video.c`: `OFFSCREEN_bootstrap` antes
  de `DUMMY_bootstrap`) — comprovado no build de revisão: sem env → `driver=offscreen`,
  com `SDL_VIDEODRIVER=dummy` → `driver=dummy`. Ambos significam "sem tela real";
  CI/aparelho esperam qualquer um dos dois (asserts só exigem `SOS_HOST_SDL2_OK`).
- Markers obrigatórios (stdout, linha própria, ASCII, greppable):
  - `SOS_HOST_STARTED` — primeira linha do host.
  - `SOS_HOST_SDL2_OK driver=<nome>` — SDL2 inicializado (driver real ou dummy).
  - `SOS_HOST_MISSING_GAME_FILES reason=<motivo>` — TELA-ALVO (ver 2.3).
  - `SOS_HOST_VALIDATE_OK` — todos os arquivos presentes (só em máquina com dump).
  - `SOS_HOST_SDL2_FAIL reason=<msg>` — fatal só se falhar TAMBÉM com dummy;
    **[M2-e]** no duplo-fail o host emite também `SOS_HOST_INTERNAL_ERROR
    reason=sdl2-init-failed` e sai com exit 2 (paridade com a regra de exit codes).
  - **[M2-e]** Marcadores adicionais do caminho pós-validação (fora do alcance de
    teste do M2): `SOS_HOST_RELINK_START`, `SOS_HOST_RELINK_OK`, `SOS_HOST_GAME_START`.
- Texto humano em PT-BR (equivalente ao texto do launcher Win32) imediatamente
  antes do marker de missing.
- **[M2-e]** Se o driver de vídeo ativo NÃO for headless (`dummy`/`offscreen`):
  mostrar a mensagem via `SDL_ShowSimpleMessageBox` (tela real). Com driver headless
  (CI/arm64 e Android): stdout apenas (a "tela" no aparelho é a UI do app, que
  espelha o stdout via logcat — igual ao M1).

### 2.3 Ordem de validação (paridade estrita com wWinMain)

1. `<game-dir>/eboot.bin` OU `<game-dir>/eboot.elf` existe? Não → `reason=no-eboot`
   ("Os arquivos do jogo não foram encontrados… copie seus arquivos descriptografados
   (eboot.elf ou eboot.bin, sce_sys, Media) para a pasta…").
2. magic ELF (4 bytes) no arquivo escolhido? Não → `reason=not-elf`.
3. `<game-dir>/sce_sys` é diretório? Não → `reason=no-sce_sys`.
4. `<game-dir>/Media` é diretório? Não → `reason=no-media`.
5. `<game-dir>/libs` é diretório? Não → `reason=no-libs`.
6. `<game-dir>/tools/relinker` existe (arquivo)? Não → `reason=no-relinker`.
7. Tudo ok → `SOS_HOST_VALIDATE_OK` + caminho de preparação/relink (fora do alcance
   de teste do M2 por exigir dump; código presente e guardado por `SOS_HOST_RELINK_START`).
   **[M2-e]** Simplificação declarada pela 2-b e aprovada: execução ÚNICA do jogo
   (`SOS_HOST_GAME_START`), sem o laço de reinício em crash do launcher Win32 — o
   journal (`logs/launcher.log`) registra 1 attempt; reinserir o restart é decisão
   do M4+ com dump real.

- Exit codes: `1` = arquivos ausentes/não-decriptados (limpo, com marker — É O
  RESULTADO ESPERADO NO M2); `2` = erro interno (com marker
  `SOS_HOST_INTERNAL_ERROR reason=...`); `0` = além da validação e também em
  `--version`/`--help`. **[M2-e]** Uso inválido de CLI (opção desconhecida ou
  `--game-dir` sem valor) = erro interno: usage em stderr + `SOS_HOST_INTERNAL_ERROR`
  (`usage-unknown-option`/`usage-missing-value`) + exit 2.
- PROIBIDO: `abort()`, `exit(-1)`, sinal em qualquer caminho de validação.
  "Sem crash" = nenhum `Fatal signal`/SIGSYS/SIGSEGV no log.

### 2.4 CMake (superprojeto standalone `host/CMakeLists.txt`)

- `cmake_minimum_required(3.20)` + `project(AnyHost CXX)`, `CMAKE_CXX_STANDARD 20`.
- SDL2 estática MÍNIMA (headless-first): `SDL_SHARED OFF`, `SDL_STATIC ON`,
  `SDL_TEST OFF`, `SDL_X11 OFF`, `SDL_WAYLAND OFF`, `SDL_KMSDRM OFF`, `SDL_OPENGL OFF`,
  `SDL_OPENGLES OFF`, `SDL_VULKAN OFF`, `SDL_RENDER OFF`, `SDL_JOYSTICK ON`,
  `SDL_HAPTIC ON`, `SDL_HIDAPI ON`, `SDL_SENSOR OFF`, `SDL_POWER OFF`,
  `SDL_STATIC_PIC ON`. (X11/Vulkan entram no M3/M5 via wrapper — não gastar risco
  de build estático X11 agora.)
- `add_subdirectory(${UPSTREAM}/3rdparty/SDL2)` + `add_subdirectory(${UPSTREAM}/core/relinker)`.
- `add_executable(anyhost host/anyhost.cpp)` link `SDL2-static`.
- Linker: `-static -static-libgcc -static-libstdc++` (paridade com o padrão upstream).
- `BUILD_TESTING` OFF (testes Python do relinker fora do M2).

## 3. Contratos de CI (M2-c) e APK (M2-d)

### CI — job novo `host-linux` (ubuntu-latest, x86-64)

1. Clone do upstream **pinado** (`757692f…`) + submódulo SDL2 (`--depth 1`).
2. `git apply` do `patches/0002-sos-linux-host.patch` (idempotente por grep;
   falha ⇒ `SOS_PATCH_FAILED` antes de build).
3. Build (`cmake -S host -B build-host -DCMAKE_BUILD_TYPE=Release`), evidência
   `file` (ambos os binários têm que sair `statically linked`).
4. Smoke NATIVO x86-64: `anyhost --game-dir ./ci-empty` (dir vazio criado no job)
   ⇒ exige `SOS_HOST_MISSING_GAME_FILES reason=no-eboot` + exit 1.
5. Artifact `host-linux-x64` (anyhost + relinker + logs).

### CI — job `box64-arm64` (ubuntu-24.04-arm, estendido)

- Mantém TUDO do M1 (payloads M1 continuam como regressão).
- Baixa artifact `host-linux-x64`; roda sob box64 (`BOX64_LOG=INFO`):
  - `anyhost --game-dir ./ci-empty` ⇒ **assert duro**: stdout contém
    `SOS_HOST_MISSING_GAME_FILES` E `SOS_HOST_SDL2_OK` E exit code == 1 E nenhum
    `Fatal signal`. Falha ⇒ `HOST_UNDER_BOX64_FAILED` + exit 1.
  - `relinker` sem argumentos ⇒ usage em stderr, exit != 0 LIMPO (smoke de que o
    C++ runtime do relinker vive sob box64; sem assert de texto).
- Summary com stdout integral + veredito `HOST_UNDER_BOX64_PASSED`.

### CI — job `apk` (M2-d)

- jniLibs ganham `libanyhost64.so` (anyhost estático renomeado, mesmo padrão do
  `libpayload64.so`).
- App: botão novo "M2: Rodar host AnyPS5 (validação)" — executa
  `libanyhost64.so --game-dir <filesDir>/game` (dir vazio na 1ª execução) e
  considera SUCESSO verde quando: `SOS_HOST_MISSING_GAME_FILES` presente E
  `SOS_HOST_SDL2_OK` presente E exit == 1 (texto PT-BR explicando que é o
  comportamento esperado — "tela de arquivos ausentes" atingida sem crash).
  Botão M1 do PoC permanece (regressão).

## 4. Riscos específicos do M2 (vivos)

| Risco | Mitigação |
|---|---|
| SDL2 estática nova geração de syscalls sob seccomp no aparelho (hidapi/udev) | dummy-only reduz superfície; falhas de /dev/input são não-fatais no SDL2; se algo estourar ⇒ patch seccomp incremental em `patches/` |
| pthreads da SDL2 estática (clone/set_robust_list) | set_robust_list já stubado (patch 0001); clone é permitido no seccomp de untrusted_app |
| cmake local (4.x) vs CI (3.28) divergirem no SDL2 | local usa `-DCMAKE_POLICY_VERSION_MINIMUM=3.5` se preciso; CI é a verdade |
| Binário estático + glibc NSS warnings sob box64 | host não usa NSS (getpwnam etc.); warnings são ruído, não crash |
| `relinker` estático pode falhar em edge cases do glibc estático | smoke é só usage (sem relink real); relink real exige dump (M4/M7) |
