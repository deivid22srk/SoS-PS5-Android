/*
 * SoS-PS5-Android — PoC M1 (task 1-b)
 *
 * Proves the exec model: box64 (built with the Android NDK, packaged as
 * jniLibs/arm64-v8a/libbox64.so) is launched via ProcessBuilder with a STATIC
 * x86-64 ELF (packaged as jniLibs/arm64-v8a/libpayload64.so) as its argument.
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
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String TAG = "SOSBox64";
    private static final String SUCCESS_MARKER = "SOS_POC_STATIC_OK";
    private static final int MAX_CAPTURED_LINES = 2000;
    private static final int TIMEOUT_SECONDS = 120;

    private TextView statusText;
    private TextView resultText;
    private TextView outputText;
    private Button runButton;
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
        title.setText("SoS PS5 - PoC box64 (M1)");
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

        resultText = new TextView(this);
        resultText.setTextSize(15f);
        resultText.setTypeface(Typeface.DEFAULT_BOLD);
        resultText.setPadding(0, dp(8), 0, dp(4));
        resultText.setText("Teste ainda não executado. Toque no botão acima.");
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
        Log.i(TAG, "=== PoC M1 iniciado ===");
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
        sb.append("libbox64.so: ").append(hasBox64 ? "presente" : "AUSENTE (APK sem o binário!)").append('\n');
        sb.append("libpayload64.so: ").append(hasPayload ? "presente" : "AUSENTE (APK sem o payload!)").append('\n');

        if (Build.VERSION.SDK_INT < 33) {
            sb.append("Aviso: este app é destinado a Android 13+; continuando mesmo assim...");
        }
        return sb.toString();
    }

    private void startBox64Test() {
        runButton.setEnabled(false);
        resultText.setText("Executando box64 + payload... aguarde.");
        resultText.setTextColor(Color.GRAY);
        outputText.setText("");

        final String libPath = new File(nativeLibDir, "libbox64.so").getAbsolutePath();
        final String payloadPath = new File(nativeLibDir, "libpayload64.so").getAbsolutePath();
        final boolean debugLog = verboseLog.isChecked();

        Thread t = new Thread(() -> runBox64(libPath, payloadPath, debugLog));
        t.setName("box64-test");
        t.start();
    }

    private void runBox64(String libPath, String payloadPath, boolean debugLog) {
        final List<String> captured = new ArrayList<>();
        final boolean[] markerFound = {false};
        final boolean[] timedOut = {false};
        int exitCode = Integer.MIN_VALUE;
        String failure = null;

        try {
            Log.i(TAG, "Executando: " + libPath + " " + payloadPath
                    + " (BOX64_LOG=" + (debugLog ? "DEBUG" : "INFO") + ")");

            ProcessBuilder pb = new ProcessBuilder(libPath, payloadPath);
            pb.redirectErrorStream(true);
            pb.environment().put("BOX64_LOG", debugLog ? "DEBUG" : "INFO");
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
                        timedOut[0] = true;
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
                if (line.contains(SUCCESS_MARKER)) {
                    markerFound[0] = true;
                }
                if (captured.size() < MAX_CAPTURED_LINES) {
                    captured.add(line);
                }
            }

            reader.close();

            // EOF reached (process exited, or was killed by the watchdog).
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                exitCode = process.exitValue();
            } else {
                process.destroyForcibly();
                timedOut[0] = true;
            }
        } catch (IOException e) {
            // ProcessBuilder reports a missing/non-executable program as a plain
            // IOException (error=2 ENOENT / error=13 EACCES), NOT as
            // FileNotFoundException/SecurityException — name the file explicitly.
            if (!new File(libPath).exists() || !new File(payloadPath).exists()) {
                failure = explainMissingFile(libPath, payloadPath);
            } else {
                failure = "Erro de E/S ao executar o box64: " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? (": " + e.getMessage()) : "");
            }
        } catch (SecurityException e) {
            failure = "Permissão negada ao executar o binário: " + e.getMessage();
        } catch (Exception e) {
            failure = "Erro de E/S ao executar o box64: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? (": " + e.getMessage()) : "");
        }

        final int exit = exitCode;
        final boolean timeout = timedOut[0];
        final String failMsg = failure;

        runOnUiThread(() -> {
            if (failMsg != null) {
                resultText.setText("FALHA — " + failMsg);
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA: " + failMsg);
            } else if (timeout) {
                resultText.setText("FALHA — box64 não terminou em " + TIMEOUT_SECONDS + " s (processo encerrado à força).");
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA: timeout");
            } else if (markerFound[0]) {
                resultText.setText("SUCESSO: SOS_POC_STATIC_OK detectado"
                        + (exit != 0 ? (" (código de saída: " + exit + ")") : ""));
                resultText.setTextColor(Color.rgb(0x00, 0x70, 0x20));
                Log.i(TAG, "SUCESSO: SOS_POC_STATIC_OK detectado (exit=" + exit + ")");
            } else {
                resultText.setText("FALHA — ver saída abaixo (código de saída: " + exit + ")."
                        + explainSignalExit(exit));
                resultText.setTextColor(Color.rgb(0xB0, 0x00, 0x00));
                Log.e(TAG, "FALHA: marcador ausente, exit=" + exit);
            }

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

            runButton.setEnabled(true);
        });
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

    /** Returns a clear PT-BR message naming the missing file. */
    private String explainMissingFile(String libPath, String payloadPath) {
        if (!new File(libPath).exists()) {
            String msg = "libbox64.so não encontrado em " + nativeLibDir
                    + ". O APK foi gerado sem o binário do box64 (ver scripts/ci-build-box64.sh no CI).";
            Log.e(TAG, msg);
            return msg;
        }
        if (!new File(payloadPath).exists()) {
            String msg = "libpayload64.so não encontrado em " + nativeLibDir
                    + ". O APK foi gerado sem o payload x86-64 (ver scripts/ci-build-box64.sh no CI).";
            Log.e(TAG, msg);
            return msg;
        }
        return "Arquivo não encontrado: " + libPath;
    }

    private int dp(int v) {
        float scale = getResources().getDisplayMetrics().density;
        return Math.round(v * scale);
    }
}
