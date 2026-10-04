# PLAN — SoS-PS5-Android

Port de **SoS-PS5** (camada de compatibilidade AnyPS5 para *God of War: Sons of Sparta*, PPSA28997)
para Android ARM64, usando **box64** como tradutor dinâmico x86-64→ARM64.

Repositório de origem: https://github.com/OverkillLabs2/SoS-PS5 (GPL-2.0)
Referências: https://github.com/ptitSeb/box64 (BSD-3), https://github.com/boykopovar/AnyPS5

## Arquitetura escolhida (Decisão A do usuário, com ajustes técnicos)

**Processo único x86-64 sob box64** (padrão comprovado por Winlator/Mobox):

```
┌────────────────────────────────────────────────────────────┐
│ App Android (APK, arm64-v8a)                               │
│                                                            │
│  MainActivity (Java/Kotlin, PT-BR)                         │
│   ├─ Checagem runtime: Android ≥ 13, Vulkan 1.3 disponível │
│   ├─ Seletor de pasta do dump (SAF, persist permission)    │
│   ├─ Preparação: copia layout p/ armazenamento do app      │
│   └─ Dispara runtime via ProcessBuilder (nativeLibraryDir) │
│                                                            │
│  box64 (executável PIE bionic, empacotado como             │
│  jniLibs/arm64-v8a/libbox64.so → extraído executável)      │
│   └─ executa: host AnyPS5 Linux x86-64 (único binário)     │
│        ├─ relinker --linux (preparação 1ª execução)        │
│        ├─ reimplementações libSce*/libkernel (x86-64)      │
│        ├─ AgcDriver → Vulkan 1.3 (via ponte p/ driver      │
│        │   nativo do aparelho)                             │
│        ├─ SDL2 x86-64 → wrapper box64 → SDL2 nativa APK    │
│        └─ CÓDIGO DO JOGO x86-64 (eboot.elf parcheado)      │
│            ← traduzido pelo MESMO box64 (mesma ISA host/   │
│              guest, sem ponte de ABI separada)             │
└────────────────────────────────────────────────────────────┘
```

### Por que não "host ARM64 nativo + ponte SysV" (M2/M3 do pedido original)

box64/FEX traduzem **processos inteiros**; não expõem API de "CPU embutível" para executar
funções guest x86-64 dentro de um processo ARM64 nativo. Misturar host ARM64 nativo com
guest x86-64 exigiria escrever um JIT próprio (R&D de meses — exatamente o que se decidiu
evitar ao adotar box64). Como host e jogo são **ambos x86-64**, rodar os dois sob o mesmo
box64 elimina a ponte de ABI: as chamadas guest→libSce* são chamadas x86-64→x86-64.

### Decisões registradas

| ID | Decisão | Motivo |
|----|---------|--------|
| D1 | Tradutor: **box64** (não FEX) | Precedente Android (Winlator/Mobox), build mais simples, wrapper SDL2 |
| D2 | Pilha inteira x86-64 em 1 processo sob box64 | Ver seção acima |
| D3 | `targetSdk 28`, `minSdk 28`, checagem runtime Android 13+ e Vulkan 1.3 (msg PT-BR) | Android bloqueia exec/mmap executável de dados do app em `targetSdk ≥ 29`; padrão da categoria (Winlator). `minSdk ≤ targetSdk` obrigatório no Gradle |
| D4 | Validação ARM64 real no CI `ubuntu-24.04-arm` (repo público) | Ambiente local sem KVM/SDK; runner ARM64 executa de verdade |
| D5 | Licenças: port em **GPL-2.0** (derivado de AnyPS5/SoS-PS5); box64 fica **fora do repositório** (build no CI a partir do upstream; patches como arquivos .patch com notice BSD-3) | Conformidade legal |
| D6 | Nenhum arquivo do jogo no APK/repo; dump fornecido pelo usuário via SAF | Regra do usuário; README da origem idem |
| D7 | Game files exigidos **em runtime**: `eboot.elf`/`eboot.bin` descriptografado + `sce_sys/` + `sce_module/` + `Media/` (~25 GB) | Investigação (ver 01-diagnostico) |

## Riscos principais (vivos — atualizar conforme descobertas)

| Risco | Impacto | Estado |
|---|---|---|
| Build do box64 com NDK/bionic pode exigir patches | M1 pode atrasar; fallback: rootfs mínimo + proot (padrão Mobox) | aberto |
| Vulkan dentro do processo x86-64 sob box64 (ponte p/ driver nativo) | M5 é o marco de maior risco técnico | aberto |
| VA de 39 bits do kernel ARM64 vs endereços fixos do PS5 | M4; box64 tem mapeamento de VM próprio, mas precisa validação | aberto |
| Performance: AAA PS5 traduzido em SoC mobile | M7 pode resultar em <30 FPS; expectativa honesta no relatório | aberto |
| Camada Linux do AnyPS5 hoje parcial (relinker --linux não é o caminho testado) | M2 pode revelar lacunas além do launcher Win32 | MITIGADO PARCIALMENTE (M2-a): relinker tem caminho Linux nativo real (sem `--windows` → `LinuxElfPatcher`); a peça ausente é só o launcher — reimplementado como `anyhost` (ver docs/M2-LINUX-HOST.md) |
| SDL2 estática mínima (dummy) sob box64/Android pode expor novas syscalls bloqueadas | M2 on-device pode falhar com SIGSYS | FECHADO (2026-10-05): teste on-device do M2 passou sem SIGSYS (exit 1 limpo, zero sinais) — nenhuma ampliação do patch 0001 foi necessária; evidência em `docs/evidence/m2-device-logcat-2026-10-05.txt` |

## Regras do projeto

- Sem arquivos do jogo no repo/APK. Token GitHub só via env (`GITHUB_TOKEN`).
- Cada marco: fan-out de sub-agentes por área + revisor crítico independente + loop de correção.
- Progresso em MD (`MILESTONES.md`, `docs/PROGRESS.md`, `docs/KNOWN_BUGS.md`) — fonte de verdade para retomar sessões.
- "Funcional ≠ compilável": validação com execução real (CI arm64 e/ou aparelho do usuário + logcat).
