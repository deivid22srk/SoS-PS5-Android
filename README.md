# SoS-PS5-Android

Port para **Android ARM64** do projeto [SoS-PS5](https://github.com/OverkillLabs2/SoS-PS5)
(camada de compatibilidade [AnyPS5](https://github.com/boykopovar/AnyPS5) que roda
*God of War: Sons of Sparta* para PS5 em PCs x86-64), usando **box64** como tradutor
dinâmico x86-64→ARM64.

**Status: Marco 1 — prova de conceito do tradutor.** Veja `docs/MILESTONES.md`.

## Como funciona

O app Android empacota o `box64` (compilado com o NDK para bionic/ARM64) e o host
AnyPS5 **como código x86-64**, que roda dentro de um único processo traduzido pelo
box64 — o mesmo modelo usado por Winlator/Mobox. O código do jogo (que também é
x86-64) é traduzido pelo mesmo runtime, sem camada de emulação de CPU separada.

Nenhum arquivo do jogo é incluído no APK ou neste repositório. Você precisa fornecer,
a partir da sua própria cópia legítima do jogo, um dump **descriptografado**
(`eboot.elf`/`eboot.bin`, `sce_sys/`, `sce_module/`, `Media/` — ~25 GB). O app pedirá
a pasta via seletor do Android (SAF) nos marcos seguintes.

## CI

`.github/workflows/build.yml` roda a cada push:

| Job | Runner | O que valida |
|---|---|---|
| `box64-arm64` | `ubuntu-24.04-arm` | Compila box64 com dynarec ARM e **executa de verdade** payloads x86-64 traduzidos (estático obrigatório; dinâmico opcional no M1) |
| `apk` | `ubuntu-latest` | Compila box64 com NDK/bionic + payload, empacota o APK de debug e publica como artifact |

## Testar no aparelho (Marco 1)

Baixe o artifact `SoS-PS5-Android-PoC-debug.apk` do run do GitHub Actions, instale no
aparelho (Android 13+ recomendado) e toque em **"Executar teste x86-64 (box64)"**.
Passo a passo e coleta de logcat: `docs/ON_DEVICE_TEST.md`.

## Requisitos de dispositivo (marcos finais)

- Android 13 ou mais novo (verificação em runtime)
- GPU com driver **Vulkan 1.3**
- Espaço livre: ~25 GB para os arquivos do jogo
- Controle Bluetooth/USB suportado (prioridade) e toque como fallback

## Legal

- Código do port: **GPL-2.0** (derivado de SoS-PS5/AnyPS5, GPL-2.0). box64 é BSD-3 e
  fica fora deste repositório (compilado no CI a partir do upstream).
- Este projeto não contém e não distribui arquivos do jogo; não é afiliado à Sony nem
  aos detentores de *God of War*. Use apenas dumps obtidos do seu próprio console.
