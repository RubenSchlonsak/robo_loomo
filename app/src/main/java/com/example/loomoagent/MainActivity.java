package com.example.loomoagent;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiEnterpriseConfig;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int MEDIA_PERMISSION_REQUEST = 1001;
    private static final long UI_REFRESH_MS = 1000L;
    private static final String WIFI_PREFS = "wifi_enterprise";
    private static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
    };

    private LoomoHttpServer httpServer;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiRefresh = new Runnable() {
        @Override
        public void run() {
            updateScreenInfo();
            uiHandler.postDelayed(this, UI_REFRESH_MS);
        }
    };

    private TextView statusText;
    private TextView ipText;
    private TextView detailsText;
    private SurfaceView previewSurface;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        ipText = findViewById(R.id.ip_text);
        detailsText = findViewById(R.id.details_text);
        previewSurface = findViewById(R.id.preview_surface);

        updateScreenInfo();

        connectEnterpriseWifi();

        if (hasRequiredPermissions()) {
            startServer();
        } else {
            statusText.setText("Camera/mic permission required");
            ActivityCompat.requestPermissions(
                    this,
                    REQUIRED_PERMISSIONS,
                    MEDIA_PERMISSION_REQUEST
            );
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(uiRefresh);
        if (httpServer != null) httpServer.stop();
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiHandler.post(uiRefresh);
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(uiRefresh);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MEDIA_PERMISSION_REQUEST) return;

        boolean granted = true;
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        if (granted) {
            startServer();
        } else {
            statusText.setText("Camera/mic permission denied");
        }
    }

    private boolean hasRequiredPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private void startServer() {
        if (httpServer != null) return;
        httpServer = new LoomoHttpServer(this, previewSurface);
        httpServer.start();
        updateScreenInfo();
    }

    private String getWifiIp() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            int ip = wm.getConnectionInfo().getIpAddress();
            return (ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "."
                    + ((ip >> 16) & 0xFF) + "." + (ip >> 24 & 0xFF);
        }
        return "unknown";
    }

    private void updateScreenInfo() {
        String ip = getWifiIp();
        String baseUrl = "http://" + ip + ":" + LoomoHttpServer.PORT;
        ipText.setText(baseUrl);

        if (httpServer == null) {
            detailsText.setText(
                    "GET " + baseUrl + "/\n" +
                    "GET " + baseUrl + "/snapshot\n" +
                    "GET " + baseUrl + "/audio\n" +
                    "GET " + baseUrl + "/audio.wav\n" +
                    "POST " + baseUrl + "/cmd"
            );
            return;
        }

        try {
            org.json.JSONObject status = httpServer.getStatusSnapshot();
            boolean vision = status.optBoolean("vision");
            boolean preview = status.optBoolean("preview");
            boolean base = status.optBoolean("base");
            boolean head = status.optBoolean("head");
            boolean recognizer = status.optBoolean("recognizer");
            String audioSource = status.optString("lastAudioSource", "none");
            int audioPeak = status.optInt("lastAudioPeak", 0);
            int width = status.optInt("colorWidth", 0);
            int height = status.optInt("colorHeight", 0);

            statusText.setText("LoomoAgent ready");
            detailsText.setText(
                    "GET " + baseUrl + "/\n" +
                    "GET " + baseUrl + "/snapshot\n" +
                    "GET " + baseUrl + "/audio\n" +
                    "GET " + baseUrl + "/audio.wav\n" +
                    "POST " + baseUrl + "/cmd\n\n" +
                    "vision=" + vision +
                    " preview=" + preview +
                    " size=" + width + "x" + height + "\n" +
                    "base=" + base +
                    " head=" + head +
                    " recognizer=" + recognizer + "\n" +
                    "audioSource=" + audioSource +
                    " peak=" + audioPeak
            );
        } catch (Exception e) {
            statusText.setText("LoomoAgent running");
            detailsText.setText(
                    "GET " + baseUrl + "/\n" +
                    "GET " + baseUrl + "/snapshot\n" +
                    "GET " + baseUrl + "/audio\n" +
                    "GET " + baseUrl + "/audio.wav\n" +
                    "POST " + baseUrl + "/cmd"
            );
        }
    }

    @SuppressWarnings("deprecation")
    private void connectEnterpriseWifi() {
        SharedPreferences prefs = getSharedPreferences(WIFI_PREFS, MODE_PRIVATE);
        String ssid = prefs.getString("ssid", null);
        String identity = prefs.getString("identity", null);
        String password = prefs.getString("password", null);
        if (ssid == null || identity == null || password == null) {
            Log.i(TAG, "No enterprise WiFi credentials saved - skipping auto-connect");
            return;
        }

        String eap = prefs.getString("eap", "PEAP");
        String phase2 = prefs.getString("phase2", "MSCHAPV2");

        Log.i(TAG, "Auto-connecting to enterprise WiFi: " + ssid);

        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) return;

            if (!wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(true);
            }

            // Remove existing config for this SSID
            for (WifiConfiguration existing : wifiManager.getConfiguredNetworks()) {
                if (existing.SSID != null && existing.SSID.equals("\"" + ssid + "\"")) {
                    wifiManager.removeNetwork(existing.networkId);
                }
            }

            WifiConfiguration config = new WifiConfiguration();
            config.SSID = "\"" + ssid + "\"";
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_EAP);
            config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.IEEE8021X);

            WifiEnterpriseConfig enterpriseConfig = new WifiEnterpriseConfig();
            enterpriseConfig.setIdentity(identity);
            enterpriseConfig.setPassword(password);

            if ("PEAP".equalsIgnoreCase(eap)) {
                enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.PEAP);
            } else if ("TTLS".equalsIgnoreCase(eap)) {
                enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TTLS);
            } else if ("TLS".equalsIgnoreCase(eap)) {
                enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.TLS);
            } else if ("PWD".equalsIgnoreCase(eap)) {
                enterpriseConfig.setEapMethod(WifiEnterpriseConfig.Eap.PWD);
            }

            if ("MSCHAPV2".equalsIgnoreCase(phase2)) {
                enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.MSCHAPV2);
            } else if ("GTC".equalsIgnoreCase(phase2)) {
                enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.GTC);
            } else if ("PAP".equalsIgnoreCase(phase2)) {
                enterpriseConfig.setPhase2Method(WifiEnterpriseConfig.Phase2.PAP);
            }

            config.enterpriseConfig = enterpriseConfig;

            int netId = wifiManager.addNetwork(config);
            if (netId == -1) {
                Log.e(TAG, "addNetwork failed for " + ssid);
                return;
            }

            wifiManager.disconnect();
            wifiManager.enableNetwork(netId, true);
            wifiManager.reconnect();
            Log.i(TAG, "Enterprise WiFi connect initiated for " + ssid);
        } catch (Exception e) {
            Log.e(TAG, "Enterprise WiFi auto-connect failed", e);
        }
    }
}
