package com.example.loomoagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a chisel reverse tunnel process on the Loomo device.
 * Extracts the chisel binary from assets, starts a reverse tunnel
 * to a remote server, and monitors/restarts the process on failure.
 */
public class TunnelManager {

    private static final String TAG = "TunnelManager";
    private static final String PREFS_NAME = "tunnel_config";
    private static final long RESTART_DELAY_MS = 5000;

    private final Context context;
    private Process chiselProcess;
    private Thread monitorThread;
    private volatile boolean enabled = false;
    private volatile String tunnelStatus = "stopped";
    private volatile String lastError = "";

    // Default config
    private String serverHost = "tunnel.cphs.mylab.th-luebeck.de";
    private int remotePort = 8280;
    private int localPort = LoomoHttpServer.PORT; // 8080

    public TunnelManager(Context context) {
        this.context = context;
        loadConfig();
    }

    private void loadConfig() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        serverHost = prefs.getString("serverHost", serverHost);
        remotePort = prefs.getInt("remotePort", remotePort);
        localPort = prefs.getInt("localPort", localPort);
        enabled = prefs.getBoolean("enabled", false);
    }

    public void saveConfig(String serverHost, int remotePort, int localPort, boolean enabled) {
        this.serverHost = serverHost;
        this.remotePort = remotePort;
        this.localPort = localPort;
        this.enabled = enabled;

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString("serverHost", serverHost)
                .putInt("remotePort", remotePort)
                .putInt("localPort", localPort)
                .putBoolean("enabled", enabled)
                .apply();
    }

    /**
     * Extract the appropriate chisel binary from assets to the app's files directory.
     */
    private File extractBinary() {
        String arch = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "";
        String assetName;
        if (arch.contains("x86_64") || arch.contains("amd64")) {
            assetName = "chisel_amd64";
        } else if (arch.contains("x86")) {
            assetName = "chisel_amd64"; // x86 can often run amd64 on modern devices
        } else {
            assetName = "chisel_arm64"; // ARM64 default (Loomo Snapdragon)
        }

        File binary = new File(context.getFilesDir(), "chisel");
        // Only extract if not already present or size differs
        try {
            InputStream is = context.getAssets().open(assetName);
            long assetSize = is.available(); // approximate
            if (binary.exists() && binary.length() > 0 && Math.abs(binary.length() - assetSize) < 1024) {
                is.close();
                Log.i(TAG, "Chisel binary already extracted: " + binary.getAbsolutePath());
                return binary;
            }

            Log.i(TAG, "Extracting chisel binary from asset: " + assetName + " (arch=" + arch + ")");
            FileOutputStream fos = new FileOutputStream(binary);
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                fos.write(buf, 0, len);
            }
            fos.close();
            is.close();

            binary.setExecutable(true, false);
            Log.i(TAG, "Chisel binary extracted: " + binary.getAbsolutePath() + " (" + binary.length() + " bytes)");
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract chisel binary", e);
            return null;
        }

        return binary;
    }

    /**
     * Start the tunnel if enabled in config.
     */
    public void startIfEnabled() {
        if (enabled) {
            start();
        } else {
            Log.i(TAG, "Tunnel not enabled - skipping start");
        }
    }

    /**
     * Start the chisel reverse tunnel.
     */
    public synchronized void start() {
        if (chiselProcess != null) {
            Log.w(TAG, "Tunnel already running");
            return;
        }

        File binary = extractBinary();
        if (binary == null || !binary.exists()) {
            tunnelStatus = "error";
            lastError = "chisel binary not found";
            Log.e(TAG, lastError);
            return;
        }

        enabled = true;
        tunnelStatus = "connecting";
        lastError = "";

        // Save enabled state
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", true)
                .apply();

        monitorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (enabled) {
                    runChisel(binary);
                    if (!enabled) break;
                    tunnelStatus = "reconnecting";
                    Log.w(TAG, "Chisel exited, restarting in " + RESTART_DELAY_MS + "ms...");
                    try {
                        Thread.sleep(RESTART_DELAY_MS);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                tunnelStatus = "stopped";
            }
        }, "chisel-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    /**
     * Resolve hostname to IP using Android's DNS (Java InetAddress),
     * bypassing chisel's own DNS which fails on some Android devices.
     */
    private String resolveServerHost() {
        String host = serverHost;
        // Extract hostname from URL-style input (e.g. "https://host" -> "host")
        String scheme = "";
        if (host.startsWith("https://")) {
            scheme = "https://";
            host = host.substring(8);
        } else if (host.startsWith("http://")) {
            scheme = "http://";
            host = host.substring(7);
        }
        // Remove trailing slash
        if (host.endsWith("/")) host = host.substring(0, host.length() - 1);

        // Check if it's already an IP or contains a port
        String hostOnly = host.contains(":") ? host.substring(0, host.indexOf(":")) : host;
        String portPart = host.contains(":") ? ":" + host.substring(host.indexOf(":") + 1) : "";

        // Try to resolve DNS via Java
        try {
            InetAddress addr = InetAddress.getByName(hostOnly);
            String ip = addr.getHostAddress();
            if (!ip.equals(hostOnly)) {
                Log.i(TAG, "Resolved " + hostOnly + " -> " + ip);
                return scheme + ip + portPart;
            }
        } catch (Exception e) {
            Log.w(TAG, "DNS resolution failed for " + hostOnly + ", using as-is: " + e.getMessage());
        }
        return scheme + host;
    }

    private void runChisel(File binary) {
        try {
            String tunnelSpec = "R:" + remotePort + ":localhost:" + localPort;
            String resolvedHost = resolveServerHost();

            List<String> cmdList = new ArrayList<>();
            cmdList.add(binary.getAbsolutePath());
            cmdList.add("client");
            // Skip TLS verification for HTTPS (self-signed or renegotiation issues)
            if (resolvedHost.startsWith("https://")) {
                cmdList.add("--tls-skip-verify");
            }
            cmdList.add(resolvedHost);
            cmdList.add(tunnelSpec);

            String[] cmd = cmdList.toArray(new String[0]);
            Log.i(TAG, "Starting chisel: " + String.join(" ", cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            chiselProcess = pb.start();
            tunnelStatus = "connecting";

            // Read output for logging
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(chiselProcess.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.i(TAG, "chisel: " + line);
                if (line.contains("Connected")) {
                    tunnelStatus = "connected";
                } else if (line.contains("error") || line.contains("Error")) {
                    lastError = line;
                    tunnelStatus = "error";
                }
            }

            int exitCode = chiselProcess.waitFor();
            Log.w(TAG, "Chisel exited with code " + exitCode);
            if (exitCode != 0) {
                lastError = "exit code " + exitCode;
                tunnelStatus = "error";
            }
        } catch (Exception e) {
            Log.e(TAG, "Chisel process error", e);
            lastError = e.getMessage();
            tunnelStatus = "error";
        } finally {
            chiselProcess = null;
        }
    }

    /**
     * Stop the tunnel.
     */
    public synchronized void stop() {
        enabled = false;
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", false)
                .apply();

        if (chiselProcess != null) {
            chiselProcess.destroy();
            chiselProcess = null;
        }
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
        tunnelStatus = "stopped";
        Log.i(TAG, "Tunnel stopped");
    }

    // --- Getters for status ---

    public String getStatus() {
        return tunnelStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public String getServerHost() {
        return serverHost;
    }

    public int getRemotePort() {
        return remotePort;
    }

    public int getLocalPort() {
        return localPort;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
