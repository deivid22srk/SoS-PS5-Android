/*
 * SoS-PS5-Android — PoC box64 (M1 + M2 + M3)
 *
 * M1 (task 1-b): proves the exec model — box64 (built with the Android NDK,
 * packaged as jniLibs/arm64-v8a/libbox64.so) is launched via ProcessBuilder
 * with a STATIC x86-64 ELF (packaged as jniLibs/arm64-v8a/libpayload64.so)
 * as its argument.
 *
 * M2 (task 2-d): the same exec model runs the AnyPS5 Linux host (anyhost,
 * packaged as jniLibs/arm64-v8a/libanyhost64.so) with --game-dir pointing at
 * an empty app-sandbox directory (<filesDir>/game). The EXPECTED outcome is
 * the clean "missing game files" screen: SOS_HOST_MISSING_GAME_FILES +
 * SOS_HOST_SDL2_OK + exit code 1 (no crash, no signal).
 *
 * M3 (task 3-d): the host binary is now DYNAMIC (DT_NEEDED libSDL2-2.0.so.0 +
 * libavcodec_sos.so + libavutil_sos.so + libfreetype_sos.so) and the SDL2
 * wrapper compiled into box64 does a NATIVE host-side dlopen("libSDL2-2.0.so.0").
 * prepareM3Runtime() (shared by M2 and M3) copies the ARM64 native SDL2 dummy
 * (jniLibs libSDL2_sos_native.so) to <filesDir>/rootfs/lib/libSDL2-2.0.so.0 and
 * exports LD_LIBRARY_PATH + BOX64_LD_LIBRARY_PATH.
 *
 * M3 fix #1 (task 3-g, on-device failure 2026-10-05): FIRST attempt — ship the
 * real x86-64 glibc as assets/rootfs. DIAGNOSIS CORRECTED by fix #2: box64
 * treats libc.so.6/libm.so.6/ld-linux as "essential WRAPPED" libs and NEVER
 * loads the real files from BOX64_LD_LIBRARY_PATH, so the glibc assets are
 * inert (kept: harmless, may serve M4 guest-lib experiments). The device log
 * (run 19 APK) reproduced all 27 unresolved relocations.
 *
 * M3 fix #2 (task 3-g, 2026-10-05): the REAL root cause of the on-device
 * SIGSEGV (exit=139) is two-fold:
 *   (a) box64's wrapped libc resolves GO symbols via dlsym(dlopen(NULL)) =
 *       the box64 process global scope, which on bionic lacks 27 glibc
 *       symbols dynamic guests reference (locale/_l family, gettext family,
 *       _chk family, __errno_location, __xpg_strerror_r) — on glibc hosts
 *       (CI arm64) the same lookup resolves from the host libc, which is why
 *       CI was green;
 *   (b) CMake's ENABLE_EXPORTS is a no-op on the Android toolchain, so
 *       box64's own my_* (GOM) symbols — e.g. my___libc_start_main — were
 *       not exported and dlsym(box64lib, "my_*") failed on device too.
 * Fix: patches/0004-android-glibc-shims.patch (ANDROID-only shims compiled
 * into the wrapped libc) + -Wl,--export-dynamic on the box64 NDK link, with
 * a hard CI assert on the exported surface (scripts/ci-build-box64.sh 4b).
 * The M3 button adds the FFmpeg/freetype probe markers and requires
 * reason=no-eboot EXACTLY (contract: docs/M3-LIBS-RUNTIME.md sections 3/4/7).
 *
 * All output is mirrored to logcat (tag "SOSBox64") and shown on screen (PT-BR).
 *
 * Framework-only UI (android.app.Activity, programmatic views, no androidx).
 * Layout: ONE full-page ScrollView (title/status/buttons/verdict/log all
 * scrollable) — the previous fixed-top + inner-scroll layout blocked touch
 * scrolling on device (selectable TextView inside a nested ScrollView).
 */
package br.deivid22srk.sosps5;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String TAG = "SOSBox64";

    // M1 payload marker (contract: must NOT be renamed — docs/MILESTONES.md).
    private static final String SUCCESS_MARKER = "SOS_POC_STATIC_OK";

    // M2 anyhost markers (contract: docs/M2-LINUX-HOST.md section 2.2).
    private static final String HOST_STARTED_MARKER = "SOS_HOST_STARTED";
    private static final String HOST_SDL2_OK_MARKER = "SOS_HOST_SDL2_OK";
    private static final String HOST_MISSING_MARKER = "SOS_HOST_MISSING_GAME_FILES";

    // M3 anyhost markers (contract: docs/M3-LIBS-RUNTIME.md sections 3 and 4).
    private static final String HOST_FREETYPE_OK_MARKER = "SOS_HOST_FREETYPE_OK";
    private static final String HOST_FFMPEG_OK_MARKER = "SOS_HOST_FFMPEG_OK";
    private static final String HOST_M3_FAIL_MARKER = "SOS_HOST_M3_FAIL";
    private static final String HOST_INTERNAL_ERROR_MARKER = "SOS_HOST_INTERNAL_ERROR";
    private static final String HOST_M3_SKIPPED_MARKER = "SOS_HOST_M3_SKIPPED";
    private static final String HOST_VALIDATE_OK_MARKER = "SOS_HOST_VALIDATE_OK";
    // Gating do M3 (contrato §4): na pasta vazia, o ÚNICO reason verde no
    // missing-files é exatamente este sufixo na linha do marker.
    private static final String HOST_MISSING_REASON_OK = "reason=no-eboot";

    /**
     * Glibc guest x86-64 embarcada no APK (M3 fix #1, task 3-g): o host
     * DINÂMICO precisa dos símbolos versionados da glibc real
     * (__libc_start_main@GLIBC_2.34, locale/gettext, _chk) que os wrappers do
     * box64 não fornecem — sem estes arquivos o host morre com SIGSEGV no
     * aparelho (evidência 2026-10-05). Mesma origem do CI leg A
     * (libc6-amd64-cross do runner que constrói o anyhost) → paridade exata de
     * versão. Extraídos para <filesDir>/rootfs/lib/ (já no BOX64_LD_LIBRARY_PATH).
     */
    private static final String[] GLIBC_GUEST_ASSETS = {
            "libc.so.6", "ld-linux-x86-64.so.2", "libm.so.6"
    };

    private static final int MAX_CAPTURED_LINES = 2000;
    private static final int TIMEOUT_SECONDS = 120;

    private TextView statusText;
    private TextView resultText;
    private TextView outputText;
    private Button runButton;
    private Button hostButton;
    private Button m3Button;
    private CheckBox verboseLog;
    private ScrollView pageScroll;

    private String nativeLibDir;

    /** Motivo PT-BR da última falha de prepareM3Runtime() (null se preparou OK). */
    private String runtimePrepFailure;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        nativeLibDir = getApplicationInfo().nativeLibraryDir;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("SoS PS5 - box64 (M1 + M2 + M3)");
        title.setTextSize(20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusText = new TextView(this);
        statusText.setTextSize(14f);
        statusText.setPadding(0, dp(8), 0, dp(8));
        statusText.setText(buildStatusText());
        root.addView(statusText);

        verboseLog = new CheckBox(this);
        verboseLog.setText("Log detalhado do box64 (BOX64_LOG=DEBUG)");
        verboseLog.setTextSize(14f);
        root.addView(verboseLog);

        runButton = new Button(this);
        runButton.setText("Executar teste x86-64 (box64)");
        runButton.setOnClickListener(v -> startBox64Test());
        root.addView(runButton);

        hostButton = new Button(this);
        hostButton.setText("M2: Rodar host AnyPS5 (validação)");
        hostButton.setOnClickListener(v -> startHostTest());
        root.addView(hostButton);

        m3Button = new Button(this);
        m3Button.setText("M3: Rodar host AnyPS5 + libs (FFmpeg/freetype)");
        m3Button.setOnClickListener(v -> startM3Test());
        root.addView(m3Button);

        resultText = new TextView(this);
        resultText.setTextSize(15f);
        resultText.setTypeface(Typeface.DEFAULT_BOLD);
        resultText.setPadding(0, dp(8), 0, dp(4));
        resultText.setText("Teste ainda não executado. Toque em um dos botões acima.");
        root.addView(resultText);

        outputText = new TextView(this);
        outputText.setTypeface(Typeface.MONOSPACE);
        outputText.setTextSize(12f);
        outputText.setText("(a saída do box64 aparecerá aqui)");
        outputText.setPadding(0, dp(4), 0, dp(24));
        root.addView(outputText);

        // UM ScrollView de página inteira: título, status, botões, veredito e o
        // log do box64 rolam juntos. O layout anterior (topo fixo + log num
        // ScrollView interno com TextView selecionável) engolia os gestos de
        // rolagem no aparelho ("não dá pra rolar", relato 2026-10-05).
        pageScroll = new ScrollView(this);
        pageScroll.addView(root);
        setContentView(pageScroll);

        // Mirror the device diagnosis to logcat as well.
        Log.i(TAG, "=== App iniciado (M1 + M2 + M3) ===");
        for (String line : buildStatusText().split("\n")) {
            Log.i(TAG, line);
        }
    }

    private String buildStatusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("Versão do Android: API ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("ABI principal: ").append(Build.SUPPORTED_ABIS[0]).append('\n');
        sb.append("Pasta de bibliotecas nativas: ").append(nativeLibDir).append('\n');

        boolean hasBox64 = new File(nativeLibDir, "libbox64.so").exists();
        boolean hasPayload = new File(nativeLibDir, "libpayload64.so").exists();
        boolean hasAnyhost = new File(nativeLibDir, "libanyhost64.so").exists();
        sb.append("libbox64.so: ").append(hasBox64 ? "presente" : "AUSENTE (APK sem o binário!)").append('\n');
        sb.append("libpayload64.so: ").append(hasPayload ? "presente" : "AUSENTE (APK sem o payload!)").append('\n');
        sb.append("libanyhost64.so: ").append(hasAnyhost ? "presente" : "AUSENTE (APK sem o host M2!)").append('\n');

        boolean hasSdl2Native = new File(nativeLibDir, "libSDL2_sos_native.so").exists();
        boolean hasGuestLibs = new File(nativeLibDir, "libavcodec_sos.so").exists()
                && new File(nativeLibDir, "libavutil_sos.so").exists()
                && new File(nativeLibDir, "libfreetype_sos.so").exists();
        sb.append("libSDL2_sos_native.so: ").append(hasSdl2Native ? "presente" : "AUSENTE (APK sem a SDL2 nativa M3!)").append('\n');
        sb.append("libs guest M3 (avcodec/avutil/freetype _sos): ")
                .append(hasGuestLibs ? "presentes" : "INCOMPLETAS (APK sem as libs guest M3!)").append('\n');
        sb.append("glibc guest x86-64 (assets/rootfs): ").append(glibcAssetStatus()).append('\n');

        if (Build.VERSION.SDK_INT < 33) {
            sb.append("Aviso: este app é destinado a Android 13+; continuando mesmo assim...");
        }
        return sb.toString();
    }

    /**
     * Diagnóstico de abertura: quais dos 3 arquivos da glibc guest estão no
     * APK (assets/rootfs). APK antigo (pré-fix #1) = "AUSENTE" → atualizar.
     */
    private String glibcAssetStatus() {
        int found = 0;
        try {
            List<String> names = Arrays.asList(getAssets().list("rootfs"));
            for (String asset : GLIBC_GUEST_ASSETS) {
                if (names.contains(asset)) {
                    found++;
                }
            }
        } catch (IOException e) {
            found = 0;
        }
        if (found == GLIBC_GUEST_ASSETS.length) {
            return "presentes (libc.so.6, ld-linux-x86-64.so.2, libm.so.6)";
        }
        return "AUSENTES (" + found + "/" + GLIBC_GUEST_ASSETS.length
                + " — APK antigo, atualize para o APK do M3 fix #1)";
    }

    /** M1 button handler — same texts and flow as task 1-b. */
    private void startBox64Test() {
        setBusyState("Executando box64 + payload... aguarde.");

        final String libPath = new File(nativeLibDir, "libbox64.so").getAbsolutePath();
        final String payloadPath = new File(nativeLibDir, "libpayload64.so").getAbsolutePath();
        final boolean debugLog = verboseLog.isChecked();

        Thread t = new Thread(() -> runM1Test(libPath, payloadPath, debugLog));
        t.setName("box64-test");
        t.start();
    }

    /** M2 button handler: runs the AnyPS5 host under box64 against an empty game dir. */
    private void startHostTest() {
        setBusyState("Executando host AnyPS5 (M2)... aguarde.");

        final String libPath = new File(nativeLibDir, "libbox64.so").getAbsolutePath();
        final String hostPath = new File(nativeLibDir, "libanyhost64.so").getAbsolutePath();
        final boolean debugLog = verboseLog.isChecked();

        Thread t = new Thread(() -> runM2Test(libPath, hostPath, debugLog));
        t.setName("box64-m2-host");
        t.start();
    }

    /** M3 button handler: runs the AnyPS5 host + M3 libs under box64 (empty game dir). */
    private void startM3Test() {
        setBusyState("Executando host AnyPS5 + libs (M3)... aguarde.");

        final String libPath = new File(nativeLibDir, "libbox64.so").getAbsolutePath();
        final String hostPath = new File(nativeLibDir, "libanyhost64.so").getAbsolutePath();
        final boolean debugLog = verboseLog.isChecked();

        Thread t = new Thread(() -> runM3Test(libPath, hostPath, debugLog));
        t.setName("box64-m3-host");
        t.start();
    }

    /** Shared pre-run UI state: gray "running" verdict, empty output, both buttons off. */
    private void setBusyState(String runningText) {
        runButton.setEnabled(false);
        hostButton.setEnabled(false);
        m3Button.setEnabled(false);
        resultText.setText(runningText);
        resultText.setTextColor(Color.GRAY);
        outputText.setText("");
    }

    /**
     * M1 flow (regression): box64 + static x86-64 payload.
     * Markers, verdict and PT-BR texts are byte-identical to task 1-b.
     */
    private void runM1Test(String libPath, String payloadPath, boolean debugLog) {
        ExecResult res = execUnderBox64(new String[]{libPath, payloadPath}, debugLog, null, SUCCESS_MARKER);
        showM1Verdict(res);
    }

    /**
     * M2 flow: box64 + anyhost --game-dir <filesDir>/game (dir created empty on
     * purpose). Exit code 1 with SOS_HOST_MISSING_GAME_FILES is the EXPECTED
     * result of this phase, not a failure. Desde o M3 o binário do host é
     * DINÂMICO, então este fluxo compartilha o prep de runtime do M3.
     */
    private void runM2Test(String libPath, String hostPath, boolean debugLog) {
        // O host M3 é dinâmico (DT_NEEDED libSDL2-2.0.so.0 + libs guest) — o M2
        // roda com o MESMO runtime prep do M3 (rootfs + LD_LIBRARY_PATH).
        String[] extraEnv = prepareM3Runtime();
        if (extraEnv == null) {
            showM2Verdict(prepFailureResult());
            return;
        }

        File gameDir = new File(getFilesDir(), "game");
        gameDir.mkdirs(); // idempotent; empty dir = host must hit reason=no-eboot

        // KB-001 reavaliado no M3 (docs/M3-LIBS-RUNTIME.md §5): o gatilho do
        // SIGSEGV era a init da SDL2 guest ESTÁTICA; com o wrapper nativo
        // (libSDL2-2.0.so.0 via dlopen bionic) esse código guest deixou de
        // existir, então o knob conservador de dynarec do M2 não é mais forçado
        // aqui (matriz do CI no job box64-arm64 confirma).
        ExecResult res = execUnderBox64(
                new String[]{libPath, hostPath, "--game-dir", gameDir.getAbsolutePath()},
                debugLog,
                extraEnv,
                HOST_STARTED_MARKER, HOST_SDL2_OK_MARKER, HOST_MISSING_MARKER);
        showM2Verdict(res);
    }

    /**
     * M3 flow: box64 + anyhost dinâmico com as sondas de libs (freetype/FFmpeg)
     * contra a MESMA pasta de jogo vazia do M2. Verde exige (contrato §3/§4):
     * SOS_HOST_MISSING_GAME_FILES com reason=no-eboot EXATO, SOS_HOST_SDL2_OK,
     * SOS_HOST_FFMPEG_OK, SOS_HOST_FREETYPE_OK e exit==1.
     */
    private void runM3Test(String libPath, String hostPath, boolean debugLog) {
        String[] extraEnv = prepareM3Runtime();
        if (extraEnv == null) {
            showM3Verdict(prepFailureResult());
            return;
        }

        File gameDir = new File(getFilesDir(), "game");
        gameDir.mkdirs(); // idempotent; empty dir = host must hit reason=no-eboot

        ExecResult res = execUnderBox64(
                new String[]{libPath, hostPath, "--game-dir", gameDir.getAbsolutePath()},
                debugLog,
                extraEnv,
                HOST_STARTED_MARKER, HOST_SDL2_OK_MARKER, HOST_MISSING_MARKER,
                HOST_FREETYPE_OK_MARKER, HOST_FFMPEG_OK_MARKER,
                HOST_M3_FAIL_MARKER, HOST_INTERNAL_ERROR_MARKER,
                HOST_M3_SKIPPED_MARKER, HOST_VALIDATE_OK_MARKER);
        showM3Verdict(res);
    }

    /**
     * Prepara o runtime M3, compartilhado por M2 e M3 (docs/M3-LIBS-RUNTIME.md §1):
     * o binário do host é DINÂMICO (DT_NEEDED libSDL2-2.0.so.0 + libs guest _sos)
     * e o wrapper SDL2 do box64 faz dlopen("libSDL2-2.0.so.0") NATIVO — o linker
     * bionic precisa do nome exato via LD_LIBRARY_PATH (permitted_path do app
     * cobre o filesDir, provado no logcat do aparelho no M2).
     *
     * Idempotente:
     * 1) garante <filesDir>/rootfs/lib/;
     * 2) copia <nativeLibraryDir>/libSDL2_sos_native.so (ARM64 nativa do jniLibs)
     *    para <filesDir>/rootfs/lib/libSDL2-2.0.so.0 (REPLACE_EXISTING sempre);
     * 3) extrai a glibc guest x86-64 (assets/rootfs: libc.so.6,
     *    ld-linux-x86-64.so.2, libm.so.6 — M3 fix #1) para o MESMO diretório,
     *    que já está no BOX64_LD_LIBRARY_PATH: sem isto o host dinâmico não
     *    resolve os símbolos versionados da glibc e morre com SIGSEGV
     *    (evidência de aparelho 2026-10-05 — paridade com o leg A do CI);
     * 4) devolve o env para o execUnderBox64:
     *    LD_LIBRARY_PATH=<filesDir>/rootfs/lib (dlopen nativo do wrapper) e
     *    BOX64_LD_LIBRARY_PATH=<nativeLibraryDir>:<filesDir>/rootfs/lib (guest;
     *    as libs _sos ficam no jniLibs, sem cópia).
     *
     * Retorna null em falha (libSDL2_sos_native.so ausente no APK, assets de
     * glibc ausentes ou erro de cópia) — o motivo PT-BR fica em
     * runtimePrepFailure para a tela.
     */
    private String[] prepareM3Runtime() {
        runtimePrepFailure = null;

        File nativeSdl2 = new File(nativeLibDir, "libSDL2_sos_native.so");
        if (!nativeSdl2.exists()) {
            runtimePrepFailure = "libSDL2_sos_native.so ausente no APK (" + nativeLibDir
                    + "). O APK foi gerado sem a SDL2 nativa ARM64 "
                    + "(ver o job apk do CI / docs/M3-LIBS-RUNTIME.md).";
            Log.e(TAG, runtimePrepFailure);
            return null;
        }

        File rootfsLib = new File(getFilesDir(), "rootfs/lib");
        File target = new File(rootfsLib, "libSDL2-2.0.so.0");
        try {
            rootfsLib.mkdirs(); // idempotente
            Files.copy(nativeSdl2.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Log.i(TAG, "Runtime M3 pronto: " + target.getAbsolutePath()
                    + " (" + Files.size(target.toPath()) + " bytes)");
        } catch (IOException e) {
            runtimePrepFailure = "Falha ao preparar a rootfs M3 (cópia da SDL2 nativa para "
                    + target.getAbsolutePath() + "): " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? (": " + e.getMessage()) : "");
            Log.e(TAG, runtimePrepFailure);
            return null;
        }

        // Glibc guest x86-64 (M3 fix #1, task 3-g): extrai os 3 arquivos da
        // glibc REAL para rootfs/lib. O BOX64_LD_LIBRARY_PATH já cobre este
        // diretório, então o loader guest do box64 passa a resolver
        // libc.so.6/libm.so.6/ld-linux exatamente como no CI (leg A), em vez de
        // cair nos wrappers (que não têm os símbolos versionados). Os nomes dos
        // assets já são os nomes finais — assets não têm a restrição lib*.so do
        // AGP, então não há renomeio aqui.
        for (String asset : GLIBC_GUEST_ASSETS) {
            File glibcTarget = new File(rootfsLib, asset);
            try (InputStream in = getAssets().open("rootfs/" + asset)) {
                Files.copy(in, glibcTarget.toPath(), StandardCopyOption.REPLACE_EXISTING);
                Log.i(TAG, "Runtime M3 pronto (glibc guest): " + glibcTarget.getAbsolutePath()
                        + " (" + Files.size(glibcTarget.toPath()) + " bytes)");
            } catch (IOException e) {
                runtimePrepFailure = "Falha ao extrair a glibc guest '" + asset
                        + "' (assets/rootfs) para " + glibcTarget.getAbsolutePath()
                        + ": " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? (": " + e.getMessage()) : "")
                        + ". APK sem os assets de glibc — atualize para o APK do M3 fix #1 "
                        + "(job apk do CI).";
                Log.e(TAG, runtimePrepFailure);
                return null;
            }
        }

        return new String[]{
                "LD_LIBRARY_PATH=" + rootfsLib.getAbsolutePath(),
                "BOX64_LD_LIBRARY_PATH=" + nativeLibDir + ":" + rootfsLib.getAbsolutePath(),
        };
    }

    /** ExecResult sintético para falha de preparação (falha rápida, sem rodar o box64). */
    private ExecResult prepFailureResult() {
        ExecResult res = new ExecResult();
        res.failure = runtimePrepFailure;
        return res;
    }

    /**
     * Shared exec engine (refactored from the M1 flow): runs
     * cmd[0] = <nativeLibraryDir>/libbox64.so followed by the guest and its
     * args, with redirectErrorStream, HOME/TMPDIR pinned to the app sandbox,
     * BOX64_LOG per the checkbox, a 120 s watchdog thread and a line-by-line
     * logcat mirror (tag "SOSBox64"). Captured lines, detected markers, exit
     * code and failures are returned in an ExecResult; the verdict itself is
     * decided by each flow's caller (M1/M2/M3 success criteria differ).
     */
    private ExecResult execUnderBox64(String[] cmd, boolean debugLog, String[] extraEnv, String... markers) {
        ExecResult res = new ExecResult();
        for (String m : markers) {
            res.markers.put(m, false);
        }

        try {
            StringBuilder cmdLine = new StringBuilder();
            for (String c : cmd) {
                if (cmdLine.length() > 0) {
                    cmdLine.append(' ');
                }
                cmdLine.append(c);
            }
            Log.i(TAG, "Executando: " + cmdLine
                    + " (BOX64_LOG=" + (debugLog ? "DEBUG" : "INFO") + ")");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.environment().put("BOX64_LOG", debugLog ? "DEBUG" : "INFO");
            // Env extra por fluxo (ex.: LD_LIBRARY_PATH/BOX64_LD_LIBRARY_PATH do prepareM3Runtime).
            if (extraEnv != null) {
                for (String kv : extraEnv) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) {
                        pb.environment().put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                }
            }
            // box64 may look for ~/.box64rc; keep HOME/TMPDIR inside the app sandbox.
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", getCacheDir().getAbsolutePath());

            final Process process = pb.start();

            // Watchdog: readLine() below only returns EOF when the process closes
            // stdout, so a hung box64 would NEVER reach the waitFor() check and the
            // 120 s timeout would be dead code. Enforce it from a separate thread.
            Thread watchdog = new Thread(() -> {
                try {
                    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        res.timedOut = true;
                        Log.w(TAG, "Timeout de " + TIMEOUT_SECONDS + " s atingido — box64 encerrado à força.");
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "box64-watchdog");
            watchdog.setDaemon(true);
            watchdog.start();

            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()), 16 * 1024);
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG, line);
                for (String m : markers) {
                    if (line.contains(m)) {
                        res.markers.put(m, true);
                    }
                }
                if (res.captured.size() < MAX_CAPTURED_LINES) {
                    res.captured.add(line);
                }
            }

            reader.close();

            // EOF reached (process exited, or was killed by the watchdog).
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                res.exitCode = process.exitValue();
            } else {
                process.destroyForcibly();
                res.timedOut = true;
            }
        } catch (IOException e) {
            // ProcessBuilder reports a missing/non-executable program as a plain
            // IOException (error=2 ENOENT / error=13 EACCES), NOT as
            // FileNotFoundException/SecurityException — name the file explicitly.
            String missing = firstMissingFile(cmd);
            if (missing != null) {
                res.failure = explainMissingFile(missing);
            } else {
                res.failure = "Erro de E/S ao executar o box64: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? (": " + e.getMessage()) : "");
            }
        } catch (SecurityException e) {
            res.failure = "Permissão negada ao executar o binário: " + e.getMessage();
        } catch (Exception e) {
            res.failure = "Erro de E/S ao executar o box64: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? (": " + e.getMessage()) : "");
        }

        return res;
    }

    /** M1 verdict — texts kept byte-identical to task 1-b (regression flow). */
    private void showM1Verdict(final ExecResult res) {
        runOnUiThread(() -> {
            if (res.failure != null) {
                resultText.setText("FALHA — " + res.failure);
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA: " + res.failure);
            } else if (res.timedOut) {
                resultText.setText("FALHA — box64 não terminou em " + TIMEOUT_SECONDS + " s (processo encerrado à força).");
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA: timeout");
            } else if (res.markerSeen(SUCCESS_MARKER)) {
                resultText.setText("SUCESSO: SOS_POC_STATIC_OK detectado"
                        + (res.exitCode != 0 ? (" (código de saída: " + res.exitCode + ")") : ""));
                resultText.setTextColor(Color.rgb(0x00, 0x70, 0x20));
                Log.i(TAG, "SUCESSO: SOS_POC_STATIC_OK detectado (exit=" + res.exitCode + ")");
            } else {
                resultText.setText("FALHA — ver saída abaixo (código de saída: " + res.exitCode + ")."
                        + explainSignalExit(res.exitCode));
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA: marcador ausente, exit=" + res.exitCode);
            }

            showCapturedOutput(res.captured);

            runButton.setEnabled(true);
            hostButton.setEnabled(true);
            m3Button.setEnabled(true);
        });
    }

    /**
     * M2 verdict: SUCESSO (green) if and only if the host output contains
     * SOS_HOST_MISSING_GAME_FILES AND SOS_HOST_SDL2_OK AND exit code == 1
     * (the clean "missing game files" screen — exit 1 is the EXPECTED result).
     * Anything else is FALHA (red) with the reason decoded.
     */
    private void showM2Verdict(final ExecResult res) {
        runOnUiThread(() -> {
            if (res.failure != null) {
                resultText.setText("FALHA (M2) — " + res.failure);
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA (M2): " + res.failure);
            } else if (res.timedOut) {
                resultText.setText("FALHA (M2) — o host AnyPS5 não terminou em " + TIMEOUT_SECONDS
                        + " s (processo encerrado à força).");
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA (M2): timeout");
            } else if (res.exitCode == 1
                    && res.markerSeen(HOST_MISSING_MARKER)
                    && res.markerSeen(HOST_SDL2_OK_MARKER)) {
                // exit=1 é o RESULTADO ESPERADO do M2 (tela de arquivos ausentes, sem crash).
                String sdl2Line = res.findLineContaining(HOST_SDL2_OK_MARKER);
                String missingLine = res.findLineContaining(HOST_MISSING_MARKER);
                String verdict = "SUCESSO (M2): o host AnyPS5 rodou sob box64 e atingiu a tela de "
                        + "arquivos do jogo ausentes (exit=1 limpo, sem crash). "
                        + "Instale os arquivos do jogo em um marco futuro.";
                if (sdl2Line != null) {
                    verdict += "\nEvidência SDL2: " + sdl2Line;
                }
                if (missingLine != null) {
                    verdict += "\nMarker: " + missingLine;
                }
                resultText.setText(verdict);
                resultText.setTextColor(Color.rgb(0x00, 0x70, 0x20));
                Log.i(TAG, "SUCESSO (M2): missing game files atingido (exit=1"
                        + (sdl2Line != null ? ", " + sdl2Line : "") + ")");
            } else {
                String hint;
                if (res.exitCode == 1) {
                    hint = " exit=1 foi retornado, mas os markers esperados ("
                            + HOST_SDL2_OK_MARKER + " e/ou " + HOST_MISSING_MARKER
                            + ") não apareceram na saída.";
                } else if (res.exitCode == 2) {
                    hint = " exit=2 = erro interno do host (procure SOS_HOST_INTERNAL_ERROR na saída abaixo).";
                } else if (res.exitCode == 0) {
                    hint = " exit=0 sem passar pela validação — inesperado nesta fase.";
                } else {
                    hint = "";
                }
                resultText.setText("FALHA (M2) — resultado inesperado do host (código de saída: "
                        + res.exitCode + ")." + explainSignalExit(res.exitCode) + hint
                        + " Saída completa abaixo.");
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA (M2): resultado inesperado, exit=" + res.exitCode);
            }

            showCapturedOutput(res.captured);

            runButton.setEnabled(true);
            hostButton.setEnabled(true);
            m3Button.setEnabled(true);
        });
    }

    /**
     * M3 verdict: SUCESSO (green) iff the host output contains ALL of
     * SOS_HOST_MISSING_GAME_FILES with reason=no-eboot EXACTLY (contract §4),
     * SOS_HOST_SDL2_OK, SOS_HOST_FFMPEG_OK, SOS_HOST_FREETYPE_OK and
     * exit code == 1 — and none of the failure markers appeared (M3_FAIL,
     * INTERNAL_ERROR, M3_SKIPPED, VALIDATE_OK). Anything else is FALHA (red)
     * with the decisive evidence line shown.
     */
    private void showM3Verdict(final ExecResult res) {
        runOnUiThread(() -> {
            if (res.failure != null) {
                resultText.setText("FALHA (M3) — " + res.failure);
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA (M3): " + res.failure);
            } else if (res.timedOut) {
                resultText.setText("FALHA (M3) — o host AnyPS5 + libs não terminou em "
                        + TIMEOUT_SECONDS + " s (processo encerrado à força).");
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA (M3): timeout");
            } else if (isM3Green(res)) {
                String missingLine = res.findLineContaining(HOST_MISSING_MARKER);
                String sdl2Line = res.findLineContaining(HOST_SDL2_OK_MARKER);
                String freetypeLine = res.findLineContaining(HOST_FREETYPE_OK_MARKER);
                String ffmpegLine = res.findLineContaining(HOST_FFMPEG_OK_MARKER);

                StringBuilder verdict = new StringBuilder(
                        "SUCESSO (M3): host + libs OK sob box64 (SDL2 via wrapper nativa, "
                                + "FFmpeg e freetype carregados, exit=1 limpo).");
                if (missingLine != null) {
                    verdict.append("\nMarker: ").append(missingLine);
                }
                String driver = extractKeyValue(sdl2Line, "driver");
                if (driver != null) {
                    verdict.append("\nSDL2: driver=").append(driver);
                }
                String ftVersion = extractKeyValue(freetypeLine, "version");
                if (ftVersion != null) {
                    verdict.append("\nfreetype: version=").append(ftVersion);
                }
                String codec = extractKeyValue(ffmpegLine, "codec");
                String avf = extractKeyValue(ffmpegLine, "avf");
                if (codec != null || avf != null) {
                    verdict.append("\nFFmpeg:");
                    if (codec != null) {
                        verdict.append(" codec=").append(codec);
                    }
                    if (avf != null) {
                        verdict.append(" avf=").append(avf);
                    }
                }
                resultText.setText(verdict.toString());
                resultText.setTextColor(Color.rgb(0x00, 0x70, 0x20));
                Log.i(TAG, "SUCESSO (M3): host + libs OK (exit=1"
                        + (driver != null ? ", driver=" + driver : "")
                        + (ftVersion != null ? ", freetype=" + ftVersion : "")
                        + (avf != null ? ", avf=" + avf : "") + ")");
            } else {
                String hint;
                String decisive = firstM3FailureEvidence(res);
                if (decisive != null) {
                    hint = " Evidência decisiva: " + decisive;
                } else if (res.exitCode == 1) {
                    hint = " exit=1, mas nem todos os markers M3 esperados apareceram ("
                            + HOST_SDL2_OK_MARKER + ", " + HOST_FFMPEG_OK_MARKER + ", "
                            + HOST_FREETYPE_OK_MARKER + " e " + HOST_MISSING_MARKER
                            + " com " + HOST_MISSING_REASON_OK + ").";
                } else if (res.exitCode == 2) {
                    hint = " exit=2 = erro interno do host (procure " + HOST_INTERNAL_ERROR_MARKER
                            + " na saída abaixo).";
                } else if (res.exitCode == 0) {
                    hint = " exit=0 sem falha de arquivos — inesperado com a pasta de jogo vazia.";
                } else {
                    hint = "";
                }
                resultText.setText("FALHA (M3) — resultado inesperado do host (código de saída: "
                        + res.exitCode + ")." + explainSignalExit(res.exitCode) + hint
                        + " Saída completa abaixo.");
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA (M3): resultado inesperado, exit=" + res.exitCode);
            }

            showCapturedOutput(res.captured);

            runButton.setEnabled(true);
            hostButton.setEnabled(true);
            m3Button.setEnabled(true);
        });
    }

    /** Verde do M3 (contrato §3/§4) — todas as condições têm que valer juntas. */
    private boolean isM3Green(ExecResult res) {
        return res.exitCode == 1
                && res.markerSeen(HOST_MISSING_MARKER)
                && res.markerSeen(HOST_SDL2_OK_MARKER)
                && res.markerSeen(HOST_FFMPEG_OK_MARKER)
                && res.markerSeen(HOST_FREETYPE_OK_MARKER)
                && missingReasonIsNoEboot(res)
                && !res.markerSeen(HOST_M3_FAIL_MARKER)
                && !res.markerSeen(HOST_INTERNAL_ERROR_MARKER)
                && !res.markerSeen(HOST_M3_SKIPPED_MARKER)
                && !res.markerSeen(HOST_VALIDATE_OK_MARKER);
    }

    /**
     * Gating do contrato (§4): na pasta de jogo vazia por construção, o ÚNICO
     * reason aceito no marker de arquivos ausentes é exatamente
     * "reason=no-eboot" (sufixo exato na linha do marker).
     */
    private boolean missingReasonIsNoEboot(ExecResult res) {
        String line = res.findLineContaining(HOST_MISSING_MARKER);
        return line != null && line.trim().endsWith(HOST_MISSING_REASON_OK);
    }

    /**
     * Primeira evidência decisiva de falha do M3, na ordem do contrato:
     * falha de sonda (M3_FAIL) > erro interno > binário sem sondas (M3_SKIPPED)
     * > VALIDATE_OK em pasta vazia > reason diferente de no-eboot. Null se
     * nenhuma se aplica (aí o hint cai no caso genérico de markers ausentes).
     */
    private String firstM3FailureEvidence(ExecResult res) {
        if (res.markerSeen(HOST_M3_FAIL_MARKER)) {
            return res.findLineContaining(HOST_M3_FAIL_MARKER);
        }
        if (res.markerSeen(HOST_INTERNAL_ERROR_MARKER)) {
            return res.findLineContaining(HOST_INTERNAL_ERROR_MARKER);
        }
        if (res.markerSeen(HOST_M3_SKIPPED_MARKER)) {
            return HOST_M3_SKIPPED_MARKER
                    + " — binário host sem as sondas M3 (APK desatualizado?).";
        }
        if (res.markerSeen(HOST_VALIDATE_OK_MARKER)) {
            return HOST_VALIDATE_OK_MARKER
                    + " — validação passou com a pasta de jogo vazia (contrato §4: falha hard).";
        }
        String missingLine = res.findLineContaining(HOST_MISSING_MARKER);
        if (missingLine != null && !missingReasonIsNoEboot(res)) {
            return missingLine + " — reason diferente de no-eboot é falha hard (contrato §4).";
        }
        return null;
    }

    /** Extrai "key=valor" (até o próximo espaço/fim da linha) de uma linha de marker. */
    private String extractKeyValue(String line, String key) {
        if (line == null) {
            return null;
        }
        int i = line.indexOf(key + "=");
        if (i < 0) {
            return null;
        }
        int start = i + key.length() + 1;
        int end = start;
        while (end < line.length() && !Character.isWhitespace(line.charAt(end))) {
            end++;
        }
        return line.substring(start, end);
    }

    /** Dumps the captured process output into the on-screen log and scrolls down. */
    private void showCapturedOutput(List<String> captured) {
        if (captured.isEmpty()) {
            outputText.setText("(nenhuma saída capturada)");
        } else {
            StringBuilder out = new StringBuilder();
            for (String l : captured) {
                out.append(l).append('\n');
            }
            outputText.setText(out.toString());
        }
        // Página inteira rolável (fix UI 2026-10-05): volta ao topo para o
        // veredito; o log completo fica logo abaixo e rola com o dedo — não há
        // mais viewport aninhado para auto-scroll.
        pageScroll.post(() -> pageScroll.scrollTo(0, 0));
    }

    /**
     * Exit codes >= 128 mean the process was killed by signal (exit - 128).
     * Translating them to plain PT-BR makes on-device reports actionable
     * (e.g. 159 = 128 + 31 = SIGSYS = seccomp-blocked syscall on Android).
     */
    private String explainSignalExit(int exit) {
        if (exit < 128) {
            return "";
        }
        int sig = exit - 128;
        String name;
        String hint;
        switch (sig) {
            case 4:  name = "SIGILL";  hint = "instrução ilegal (CPU não suportou o código)"; break;
            case 6:  name = "SIGABRT"; hint = "abort() — assertion/erro interno"; break;
            case 7:  name = "SIGBUS";  hint = "acesso inválido à memória (bus error)"; break;
            case 9:  name = "SIGKILL"; hint = "processo morto pelo sistema (ex.: falta de memória)"; break;
            case 11: name = "SIGSEGV"; hint = "falha de segmentação (acesso inválido à memória)"; break;
            case 13: name = "SIGPIPE"; hint = "pipe fechado"; break;
            case 31: name = "SIGSYS";  hint = "syscall bloqueado pelo seccomp do Android — confira se o box64 do APK contém o patch seccomp-safe (patches/0001)"; break;
            default: return " O processo foi morto pelo sinal " + sig + ".";
        }
        return " O processo foi morto pelo sinal " + sig + " (" + name + ") — " + hint + ".";
    }

    /** First absolute-path argument that does not exist on disk (or null). */
    private String firstMissingFile(String[] cmd) {
        for (String arg : cmd) {
            if (arg.startsWith("/") && !new File(arg).exists()) {
                return arg;
            }
        }
        return null;
    }

    /** Returns a clear PT-BR message naming the missing packaged binary. */
    private String explainMissingFile(String path) {
        String name = new File(path).getName();
        String msg;
        if (name.equals("libbox64.so")) {
            msg = "libbox64.so não encontrado em " + nativeLibDir
                    + ". O APK foi gerado sem o binário do box64 (ver scripts/ci-build-box64.sh no CI).";
        } else if (name.equals("libpayload64.so")) {
            msg = "libpayload64.so não encontrado em " + nativeLibDir
                    + ". O APK foi gerado sem o payload x86-64 (ver scripts/ci-build-box64.sh no CI).";
        } else if (name.equals("libanyhost64.so")) {
            msg = "libanyhost64.so não encontrado em " + nativeLibDir
                    + ". O APK foi gerado sem o host AnyPS5 do M2 (ver o job apk do CI / docs/M2-LINUX-HOST.md).";
        } else {
            msg = "Arquivo não encontrado: " + path;
        }
        Log.e(TAG, msg);
        return msg;
    }

    private int dp(int v) {
        float scale = getResources().getDisplayMetrics().density;
        return Math.round(v * scale);
    }

    /** Outcome of one execUnderBox64 run, filled on the worker thread, read on the UI thread. */
    private static final class ExecResult {
        final List<String> captured = new ArrayList<>();
        final Map<String, Boolean> markers = new LinkedHashMap<>();
        int exitCode = Integer.MIN_VALUE;
        boolean timedOut = false;
        String failure = null;

        boolean markerSeen(String name) {
            Boolean seen = markers.get(name);
            return seen != null && seen;
        }

        /** First captured line containing the given needle (or null). */
        String findLineContaining(String needle) {
            for (String l : captured) {
                if (l.contains(needle)) {
                    return l;
                }
            }
            return null;
        }
    }
}
