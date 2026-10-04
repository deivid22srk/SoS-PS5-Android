# Teste no aparelho — PoC box64 (M1)

Este roteiro valida a prova de conceito: **rodar um ELF x86-64 dentro do app Android
ARM64 usando o box64** e ver o resultado na tela e no logcat.

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
SoS PS5 - PoC box64 (M1)
Versão do Android: API 33            (exemplo)
ABI principal: arm64-v8a
Pasta de bibliotecas nativas: /data/app/.../lib/arm64
libbox64.so: presente
libpayload64.so: presente
```

- Se aparecer `AUSENTE` em qualquer um dos dois: o APK foi gerado sem os binários —
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
