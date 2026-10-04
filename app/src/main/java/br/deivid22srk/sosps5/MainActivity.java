/*
 * SoS-PS5-Android — PoC box64 (M1 + M2)
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
 * All output is mirrored to logcat (tag "SOSBox64") and shown on screen (PT-BR).
 *
 * Framework-only UI (android.app.Activity, programmatic views, no androidx).
 */
package br.deivid22srk.sosps5;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
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

    private static final int MAX_CAPTURED_LINES = 2000;
    private static final int TIMEOUT_SECONDS = 120;

    private TextView statusText;
    private TextView resultText;
    private TextView outputText;
    private Button runButton;
    private Button hostButton;
    private CheckBox verboseLog;
    private ScrollView outputScroll;

    private String nativeLibDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        nativeLibDir = getApplicationInfo().nativeLibraryDir;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("SoS PS5 - box64 (M1 + M2)");
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

        resultText = new TextView(this);
        resultText.setTextSize(15f);
        resultText.setTypeface(Typeface.DEFAULT_BOLD);
        resultText.setPadding(0, dp(8), 0, dp(4));
        resultText.setText("Teste ainda não executado. Toque em um dos botões acima.");
        root.addView(resultText);

        outputScroll = new ScrollView(this);
        outputScroll.setFillViewport(true);
        outputText = new TextView(this);
        outputText.setTypeface(Typeface.MONOSPACE);
        outputText.setTextSize(12f);
        outputText.setTextIsSelectable(true);
        outputText.setText("(a saída do box64 aparecerá aqui)");
        outputScroll.addView(outputText);
        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f);
        outputScroll.setLayoutParams(scrollParams);
        root.addView(outputScroll);

        setContentView(root);

        // Mirror the device diagnosis to logcat as well.
        Log.i(TAG, "=== App iniciado (M1 + M2) ===");
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

        if (Build.VERSION.SDK_INT < 33) {
            sb.append("Aviso: este app é destinado a Android 13+; continuando mesmo assim...");
        }
        return sb.toString();
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

    /** Shared pre-run UI state: gray "running" verdict, empty output, both buttons off. */
    private void setBusyState(String runningText) {
        runButton.setEnabled(false);
        hostButton.setEnabled(false);
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
     * result of this phase, not a failure.
     */
    private void runM2Test(String libPath, String hostPath, boolean debugLog) {
        File gameDir = new File(getFilesDir(), "game");
        gameDir.mkdirs(); // idempotent; empty dir = host must hit reason=no-eboot

        // BOX64_DYNAREC_SAFEFLAGS=2: root-caused via the debug-m2 CI matrix
        // (runs 37228217771 / 37228988961) — the static SDL2 init SIGSEGVs
        // under the ARM dynarec with default (fast) EFLAGS handling;
        // SAFEFLAGS=2 fixes it with dynarec ON. Same workaround as CI.
        ExecResult res = execUnderBox64(
                new String[]{libPath, hostPath, "--game-dir", gameDir.getAbsolutePath()},
                debugLog,
                new String[]{"BOX64_DYNAREC_SAFEFLAGS=2"},
                HOST_STARTED_MARKER, HOST_SDL2_OK_MARKER, HOST_MISSING_MARKER);
        showM2Verdict(res);
    }

    /**
     * Shared exec engine (refactored from the M1 flow): runs
     * cmd[0] = <nativeLibraryDir>/libbox64.so followed by the guest and its
     * args, with redirectErrorStream, HOME/TMPDIR pinned to the app sandbox,
     * BOX64_LOG per the checkbox, a 120 s watchdog thread and a line-by-line
     * logcat mirror (tag "SOSBox64"). Captured lines, detected markers, exit
     * code and failures are returned in an ExecResult; the verdict itself is
     * decided by each flow's caller (M1 vs M2 success criteria differ).
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
            // Optional per-flow box64 tuning (e.g. BOX64_DYNAREC_SAFEFLAGS=2 for the M2 host).
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
        });
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
        outputScroll.post(() -> outputScroll.fullScroll(View.FOCUS_DOWN));
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
