# LoomoAgent API

Basis-URL:

`http://<LOOMO-IP>:8080`

Beispiel:

`http://172.30.9.52:8080`

## Endpunkte

### `GET /`

Liefert den aktuellen Gesamtstatus als JSON.

Beispielantwort:

```json
{
  "status": "ok",
  "port": 8080,
  "vision": true,
  "preview": false,
  "base": true,
  "head": true,
  "recognizer": false,
  "listening": false,
  "streamClients": 0,
  "frameCallbacks": 24,
  "frameListener": true,
  "legacyCamera": false,
  "cameraSource": "vision",
  "lastFrameAt": 1773936087220,
  "lastFrameStatus": "ok",
  "cameraRecoveryAttempts": 0,
  "colorWidth": 640,
  "colorHeight": 480,
  "lastRecognition": "",
  "lastRecognitionAt": 0,
  "lastWakeup": "",
  "lastWakeupAt": 0,
  "lastAudioSource": "none",
  "lastAudioPeak": 0,
  "lastAudioRms": 0
}
```

Wichtige Felder:

- `vision`, `base`, `head`: SDK-Bindings aktiv
- `frameListener`: Vision-Frame-Listener aktiv
- `legacyCamera`: Android-Camera-Fallback aktiv
- `cameraSource`: aktueller Kamerapfad, z. B. `vision` oder `camera1`
- `lastFrameStatus`: Kamera-Status
- `cameraRecoveryAttempts`: Auto-Recovery-Versuche seit Start

### `GET /snapshot`

Liefert ein einzelnes JPEG-Bild.

Erfolg:

- `200 OK`
- `Content-Type: image/jpeg`

Fehler:

- `503 Service Unavailable`
- Text enthält Diagnose wie `vision`, `preview`, `frameStatus`, `size`

### `GET /stream`

MJPEG-Stream.

Erfolg:

- `200 OK`
- `Content-Type: multipart/x-mixed-replace; boundary=loomoboundary`

Hinweis:

- Nutzt intern wiederholt denselben Snapshot-/Frame-Pfad

### `GET /audio`

Liefert Audio-/Recognizer-Status als JSON.

Beispiel:

```json
{
  "recognizer": false,
  "listening": false,
  "lastRecognition": "",
  "lastRecognitionAt": 0,
  "lastWakeup": "",
  "lastWakeupAt": 0,
  "lastAudioSource": "VOICE_RECOGNITION",
  "lastAudioPeak": 9216,
  "lastAudioRms": 1049.94
}
```

### `GET /audio.wav`

Liefert eine kurze WAV-Aufnahme vom Loomo-Mikrofon.

Eigenschaften:

- mono
- 16 kHz
- 16-bit PCM
- ca. 2 Sekunden

Erfolg:

- `200 OK`
- `Content-Type: audio/wav`

### `POST /cmd`

Nimmt JSON-Kommandos entgegen.

Erfolg:

```json
{"ok":true}
```

## Kommandos für `POST /cmd`

### Bewegung

```json
{
  "cmd": "move",
  "linear": 0.4,
  "angular": 0.0
}
```

Felder:

- `linear`: Vor/Zurück-Geschwindigkeit
- `angular`: Drehgeschwindigkeit

### Stop

```json
{
  "cmd": "stop"
}
```

### Kopf bewegen

```json
{
  "cmd": "head",
  "yaw": 0.3,
  "pitch": -0.1
}
```

Felder:

- `yaw`: links/rechts
- `pitch`: hoch/runter

### TTS sprechen

```json
{
  "cmd": "speak",
  "text": "Hallo vom Loomo"
}
```

### Speech-/Recognizer starten

```json
{
  "cmd": "listen",
  "action": "start"
}
```

oder

```json
{
  "cmd": "audio",
  "action": "start"
}
```

Stop:

```json
{
  "cmd": "listen",
  "action": "stop"
}
```

### Vision umschalten

```json
{
  "cmd": "vision",
  "action": "start"
}
```

oder

```json
{
  "cmd": "vision",
  "action": "stop"
}
```

Hinweis:

- Die On-Device-Preview ist aktuell im Code deaktiviert, der API-Pfad bleibt aber erhalten.

## Beispielaufrufe

### PowerShell

Status:

```powershell
Invoke-WebRequest -UseBasicParsing "http://172.30.9.52:8080/"
```

Snapshot:

```powershell
Invoke-WebRequest -UseBasicParsing "http://172.30.9.52:8080/snapshot" -OutFile snapshot.jpg
```

Audio:

```powershell
Invoke-WebRequest -UseBasicParsing "http://172.30.9.52:8080/audio.wav" -OutFile audio.wav
```

Sprechen:

```powershell
$body = @{ cmd = "speak"; text = "Hallo" } | ConvertTo-Json
Invoke-WebRequest -UseBasicParsing "http://172.30.9.52:8080/cmd" -Method POST -ContentType "application/json" -Body $body
```

Fahren:

```powershell
$body = @{ cmd = "move"; linear = 0.4; angular = 0.0 } | ConvertTo-Json
Invoke-WebRequest -UseBasicParsing "http://172.30.9.52:8080/cmd" -Method POST -ContentType "application/json" -Body $body
```

Stop:

```powershell
$body = @{ cmd = "stop" } | ConvertTo-Json
Invoke-WebRequest -UseBasicParsing "http://172.30.9.52:8080/cmd" -Method POST -ContentType "application/json" -Body $body
```

## Kamera-Hinweis

Der Loomo hat aktuell einen instabilen Segway-Host-/Vision-Dienst. Deshalb gibt es zwei Kamerapfade:

- `vision`: Segway Vision SDK
- `camera1`: Android Camera Fallback

Wenn der Segway-Dienst hängt, kann ein externer Neustart nötig sein. Dafür liegt im Projekt:

[`restart_loomo_camera.ps1`](C:\Users\rs280\LoomoAgent\restart_loomo_camera.ps1)

## Relevante Dateien im Projekt

- [`LoomoHttpServer.java`](C:\Users\rs280\LoomoAgent\app\src\main\java\com\example\loomoagent\LoomoHttpServer.java)
- [`MainActivity.java`](C:\Users\rs280\LoomoAgent\app\src\main\java\com\example\loomoagent\MainActivity.java)
- [`dashboard.html`](C:\Users\rs280\LoomoAgent\dashboard.html)
