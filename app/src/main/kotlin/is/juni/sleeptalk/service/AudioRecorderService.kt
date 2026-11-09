package `is`.juni.sleeptalk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import `is`.juni.sleeptalk.MainActivity
import `is`.juni.sleeptalk.R
import `is`.juni.sleeptalk.util.WavEncoder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class AudioRecorderService : Service() {

    companion object {
        const val ACTION_START = "is.juni.sleeptalk.START"
        const val ACTION_STOP  = "is.juni.sleeptalk.STOP"

        const val BROADCAST_CLIP_SAVED    = "is.juni.sleeptalk.CLIP_SAVED"
        const val BROADCAST_STATE_CHANGED = "is.juni.sleeptalk.STATE_CHANGED"
        const val BROADCAST_SESSION_ENDED = "is.juni.sleeptalk.SESSION_ENDED"
        const val BROADCAST_AMPLITUDE     = "is.juni.sleeptalk.AMPLITUDE"

        const val EXTRA_CLIP_PATH   = "clip_path"
        const val EXTRA_IS_RUNNING  = "is_running"
        const val EXTRA_CLIP_COUNT  = "clip_count"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_AMPLITUDE   = "amplitude"      // 0-100 normalised
        const val EXTRA_CAPTURING   = "capturing"      // true while saving a clip

        const val PREF_THRESHOLD             = "threshold"
        const val PREF_PRE_ROLL_SECS         = "pre_roll_secs"
        const val PREF_POST_SILENCE_SECS     = "post_silence_secs"
        const val PREF_ACTIVATION_DELAY_MINS = "activation_delay_mins"
        const val PREF_AUTO_CALIBRATE        = "auto_calibrate"
        const val PREF_AUTO_DELETE_DAYS      = "auto_delete_days"

        const val SAMPLE_RATE          = 16000
        private const val CHANNEL_CONFIG   = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT     = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        private const val NOTIF_CHANNEL_ID      = "sleeptalk_service"
        private const val NOTIF_CHANNEL_SUMMARY = "sleeptalk_summary"
        private const val NOTIF_ID              = 1
        private const val NOTIF_SUMMARY_ID      = 2
        private const val WAKE_LOCK_TAG         = "sleeptalk:recording"

        @Volatile var isRunning = false
    }

    private var audioRecord:   AudioRecord?           = null
    private var captureThread: Thread?                 = null
    private var wakeLock:      PowerManager.WakeLock?  = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sessionStartMs = 0L
    private var clipsSaved     = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP  -> scheduleStop()   // never blocks the main thread
        }
        return START_NOT_STICKY
    }

    // ── Start ─────────────────────────────────────────────────────────────────

    private fun startCapture() {
        if (isRunning) return
        isRunning      = true
        sessionStartMs = System.currentTimeMillis()
        clipsSaved     = 0

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .also { it.acquire() }

        val prefs             = getSharedPreferences("settings", MODE_PRIVATE)
        val userThreshold     = prefs.getInt(PREF_THRESHOLD, 600)
        val preRollSecs       = prefs.getInt(PREF_PRE_ROLL_SECS, 5)
        val postSilenceSecs   = prefs.getInt(PREF_POST_SILENCE_SECS, 4)
        val activationDelayMs = prefs.getInt(PREF_ACTIVATION_DELAY_MINS, 0) * 60_000L
        val autoCalibrate     = prefs.getBoolean(PREF_AUTO_CALIBRATE, true)
        val autoDeleteDays    = prefs.getInt(PREF_AUTO_DELETE_DAYS, 0)

        if (autoDeleteDays > 0)
            `is`.juni.sleeptalk.data.ClipRepository.deleteOlderThan(this, autoDeleteDays)

        // startForeground MUST come before AudioRecord on Android 14+
        startForeground(NOTIF_ID, buildNotification(if (autoCalibrate) "Calibrating…" else "Listening…"))
        broadcastState(true)

        val minBuf   = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val readSize = minBuf * 2

        val ar = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
            minBuf * 8
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            ar.release()
            isRunning = false
            updateNotification("Mic unavailable — check permissions")
            broadcastState(false)
            return
        }
        ar.startRecording()
        audioRecord = ar

        val preRollBytes        = SAMPLE_RATE * BYTES_PER_SAMPLE * preRollSecs
        val preRoll             = CircularByteBuffer(preRollBytes)
        val silenceChunksNeeded = (postSilenceSecs.toFloat() * SAMPLE_RATE * BYTES_PER_SAMPLE / readSize)
            .toInt().coerceAtLeast(1)

        // Throttle amplitude broadcasts to ~4 per second
        var lastAmplitudeBroadcast = 0L

        captureThread = Thread {
            val buf        = ByteArray(readSize)
            val clipChunks = ArrayDeque<ByteArray>()
            var silenceCount  = 0
            var capturing     = false

            // Phase 1 – calibrate
            var effectiveThreshold = userThreshold
            if (autoCalibrate) {
                val calibEnd  = System.currentTimeMillis() + 5_000L
                var peakSum   = 0L
                var peakCount = 0
                while (System.currentTimeMillis() < calibEnd && isRunning) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: break
                    if (read > 0) { peakSum += peakAmplitude(buf.copyOf(read)); peakCount++ }
                }
                if (peakCount > 0) {
                    val ambient = peakSum / peakCount
                    effectiveThreshold = maxOf(userThreshold, (ambient * 3).coerceIn(200, 8000).toInt())
                }
                updateNotification("Listening… (sensitivity $effectiveThreshold)")
            }

            // Phase 2 – activation delay
            if (activationDelayMs > 0 && isRunning) {
                val delayEnd = System.currentTimeMillis() + activationDelayMs
                updateNotification("Starting in ${activationDelayMs / 60_000}m…")
                while (System.currentTimeMillis() < delayEnd && isRunning)
                    audioRecord?.read(buf, 0, buf.size)
                if (isRunning) updateNotification("Listening…")
            }

            // Phase 3 – main detection loop
            while (isRunning) {
                val read = audioRecord?.read(buf, 0, buf.size) ?: break
                if (read <= 0) continue

                val chunk     = buf.copyOf(read)
                val amplitude = peakAmplitude(chunk)

                // Broadcast amplitude for the live indicator (~4 Hz) — must be on main thread
                val now = System.currentTimeMillis()
                if (now - lastAmplitudeBroadcast > 250) {
                    lastAmplitudeBroadcast = now
                    val normalised = (amplitude * 100 / effectiveThreshold).coerceIn(0, 100)
                    val isCapturing = capturing
                    mainHandler.post {
                        sendBroadcast(Intent(BROADCAST_AMPLITUDE)
                            .putExtra(EXTRA_AMPLITUDE, normalised)
                            .putExtra(EXTRA_CAPTURING, isCapturing))
                    }
                }

                when {
                    amplitude >= effectiveThreshold -> {
                        if (!capturing) {
                            capturing = true
                            clipChunks.addLast(preRoll.toByteArray())
                        }
                        silenceCount = 0
                        clipChunks.addLast(chunk)
                    }
                    capturing -> {
                        clipChunks.addLast(chunk)
                        silenceCount++
                        if (silenceCount >= silenceChunksNeeded) {
                            saveClip(clipChunks)
                            clipChunks.clear()
                            capturing    = false
                            silenceCount = 0
                            preRoll.clear()
                        }
                    }
                    else -> preRoll.write(chunk)
                }
            }

            if (capturing && clipChunks.isNotEmpty()) saveClip(clipChunks)

            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Session finished — post back to main thread for broadcasts/cleanup
            val durationMs = System.currentTimeMillis() - sessionStartMs
            val savedCount = clipsSaved
            mainHandler.post {
                wakeLock?.release(); wakeLock = null
                @Suppress("DEPRECATION") stopForeground(true)
                stopSelf()
                broadcastState(false)
                sendBroadcast(Intent(BROADCAST_SESSION_ENDED)
                    .putExtra(EXTRA_CLIP_COUNT, savedCount)
                    .putExtra(EXTRA_DURATION_MS, durationMs))
                showSessionSummary(savedCount, durationMs)
            }
        }.also { it.start() }
    }

    // ── Stop ──────────────────────────────────────────────────────────────────

    /** Sets the flag and returns immediately — the capture thread notices and finishes itself. */
    private fun scheduleStop() {
        isRunning = false
        // captureThread will exit its loop, then post cleanup to mainHandler above
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun saveClip(chunks: ArrayDeque<ByteArray>) {
        val pcm  = chunks.fold(ByteArray(0)) { acc, c -> acc + c }
        val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getExternalFilesDir("clips"), "sleep_$ts.wav")
        WavEncoder.write(file, pcm, SAMPLE_RATE, 1, 16)
        clipsSaved++
        sendBroadcast(Intent(BROADCAST_CLIP_SAVED).putExtra(EXTRA_CLIP_PATH, file.absolutePath))
    }

    private fun showSessionSummary(count: Int, durationMs: Long) {
        val hours   = durationMs / 3_600_000
        val mins    = (durationMs % 3_600_000) / 60_000
        val durStr  = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
        val text    = "Recorded $count ${if (count == 1) "clip" else "clips"} over $durStr"
        val tap     = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        getSystemService(NotificationManager::class.java).notify(NOTIF_SUMMARY_ID,
            NotificationCompat.Builder(this, NOTIF_CHANNEL_SUMMARY)
                .setContentTitle("Session ended").setContentText(text)
                .setSmallIcon(R.drawable.ic_mic).setContentIntent(tap).setAutoCancel(true)
                .build())
    }

    private fun peakAmplitude(bytes: ByteArray): Int {
        var max = 0; var i = 0
        while (i < bytes.size - 1) {
            val s = abs(((bytes[i + 1].toInt() shl 8) or (bytes[i].toInt() and 0xFF)).toShort().toInt())
            if (s > max) max = s; i += 2
        }
        return max
    }

    private fun broadcastState(running: Boolean) =
        sendBroadcast(Intent(BROADCAST_STATE_CHANGED).putExtra(EXTRA_IS_RUNNING, running))

    private fun updateNotification(text: String) =
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))

    private fun buildNotification(text: String): Notification {
        val tap  = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 0,
            Intent(this, AudioRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name)).setContentText(text)
            .setSmallIcon(R.drawable.ic_mic).setContentIntent(tap)
            .addAction(R.drawable.ic_stop, "Stop", stop).setOngoing(true).build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(
            NOTIF_CHANNEL_ID, "Recording service", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            NOTIF_CHANNEL_SUMMARY, "Session summary", NotificationManager.IMPORTANCE_DEFAULT))
    }
}
