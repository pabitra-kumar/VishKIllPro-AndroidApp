# VishKillPro: Internal Audio Chunk Recorder and Uploader

This Android app records internal audio from your device (like music, video, or games), splits it into 10-second chunks, and uploads each chunk to a local server for analysis.

---

## 🎯 Features

* Records internal (media) audio, not microphone or call audio
* Runs in the background using a foreground service
* Saves audio in 10-second `.wav` chunks
* Automatically uploads each chunk to your local server
* Deletes the chunk after successful upload and response
* Logs server response for every chunk

---

## 📦 Requirements

* Android 10+ (API 29+)
* Permission: `RECORD_AUDIO`
* Screen capture permission (requested at runtime)
* Local server running on the same Wi-Fi network

---

## 📲 How to Use the App

### On Your Android Device:

1. Install and run the app.
2. Grant the `RECORD_AUDIO` permission.
3. Accept the screen capture permission dialog.
4. App will start recording internal audio immediately.
5. Every 10 seconds:

   * A `.wav` file is saved
   * It's uploaded to the server
   * The server responds with JSON
   * Logcat logs the result
6. Close the app to stop recording

### On Your PC (Server Side):

1. Start your server.
2. Server must be accessible to your phone (e.g. `http://192.168.x.x:800/analyze`)
3. Ensure server accepts `multipart/form-data` with a field named `file`
4. Server should respond with a JSON object including `chunks[]`

---

## 🔧 Configurable Settings

In `RecordingService.kt`:

```kotlin
private val SERVER_URL = "http://192.168.x.x:8000/analyze" // Change IP and path as needed
```

Update to your local server’s IP and port.

---

## 🧪 Test with cURL (Optional)

```bash
curl -X POST http://<YOUR_SERVER_IP>:<port>/<path> \
  -F "file=@chunk_0.wav"
```

---

## 🔍 Logs & Output

* Logcat output shows:

  * Start/stop logs
  * Chunk recording info
  * Server responses
* WAV files are temporarily saved in `externalCacheDir`, then deleted

---

## ⚠️ Notes

* This app only works on devices running Android 10 or higher
* Requires screen capture permission for internal audio
* Tested with local network servers only
* Ensure your server is CORS-enabled and reachable via Wi-Fi

---

## 📄 License

This app is for educational and testing purposes. Use responsibly.

---

For questions or contributions, feel free to reach out!
