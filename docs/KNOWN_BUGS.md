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
- **Contorno ativo:** `BOX64_DYNAREC_SAFEFLAGS=2` para as execuções do host
  (CI `box64-arm64` step RUN anyhost; app M2 `runM2Test` via `extraEnv`).
  Custo: leve perda de performance do dynarec enquanto o workaround estiver
  ativo (o relinker não precisa do knob).
- **Impacto futuro:** no M3 a SDL2 guest (x86-64 estática) será substituída
  pelo wrapper do box64 → SDL2 nativa, eliminando o código disparador. Se o
  padrão de flags afetar código do jogo no M4+, reavaliar (upgrade do box64
  upstream, bisect fino do dynarec, ou SAFEFLAGS global).
- **Status:** CONTORNADO (workaround documentado; não é fechamento definitivo).
