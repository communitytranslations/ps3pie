package com.wishsalad.wishimu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class UdpSenderService extends Service implements SensorEventListener {
    /** True while the service is running. Read by MainActivity.onResume() to sync UI state. */
    public static volatile boolean started = false;

    /** Latest sensor snapshot for debug display. Written under synchronized(instance), read by MainActivity Handler. */
    public static final float[] debugAcc = new float[3];
    public static final float[] debugGyr = new float[3];
    public static final float[] debugMag = new float[3];
    public static final float[] debugImu = new float[3];

    /** Last worker error, readable by the Activity without binding. Null when OK. */
    public static volatile String debugError = null;

    /** Ack-based connectivity: epoch ms of last ack received from pc, 0 if none yet. */
    private volatile long lastAckTime = 0;
    /** Epoch ms when the current connection attempt started (for the initial timeout). */
    private volatile long connectionStartTime = 0;
    private static final long ACK_TIMEOUT_MS = 5000;

    private final IBinder mBinder = new MyBinder();
    private PowerManager mPowerManager;
    private WifiManager mWifiManager;

    public static final String ACTION_STOP = "ACTION_STOP";

    private static final byte SEND_RAW = 0x01;
    private static final byte SEND_ORIENTATION = 0x02;
    private static final byte SEND_BUTTONS = 0x04;
    private static final byte SEND_NONE = 0x00;

    /** Button bitmask written by MainActivity and read by the worker thread. Bit 0 = fire. */
    public static final AtomicInteger buttonState = new AtomicInteger(0);

    /** True when volume buttons should be intercepted as mouse clicks. Updated by MainActivity. */
    public static volatile boolean volumeButtonsEnabled = false;
    private static final String TAG_WAKE_LOCK = "FreePIE:WakeLock";
    private static final String TAG_WIFI_LOCK = "FreePIE:WifiLock";

    private final float[] acc = new float[]{0, 0, 0};
    private final float[] mag = new float[]{0, 0, 0};
    private final float[] gyr = new float[]{0, 0, 0};
    private final float[] imu = new float[]{0, 0, 0};

    private final float[] rotationVector = new float[3];
    private final float[] rotationMatrix = new float[16];

    private final float[] R_ = new float[9];
    private final float[] I = new float[9];

    private DatagramSocket socket;
    private byte deviceIndex;
    private boolean sendOrientation;
    private boolean sendRaw;
    private int sampleRate;
    private SensorManager sensorManager;

    private MediaSession mediaSession;
    // Schedules button-release events when no key-up is available (VolumeProvider only gets press)
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable releaseVolUp   = () -> buttonState.updateAndGet(b -> b & ~0x01);
    private final Runnable releaseVolDown = () -> buttonState.updateAndGet(b -> b & ~0x02);

    private Thread worker;
    private volatile boolean running;
    private boolean hasGyro;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private final DatagramPacket p = new DatagramPacket(new byte[]{}, 0);
    private final byte[] buf = new byte[52]; // 50 sensor bytes + 1 buttons byte + 1 spare

    private String lastError;

    private final BroadcastReceiver screen_off_receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Compare constant first to avoid NullPointerException
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                register_sensors();
            }
        }
    };

    public void register_sensors() {
        sensorManager.unregisterListener(this);
        if (sendRaw) {
            sensorManager.registerListener(this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                    sampleRate);
            if (hasGyro)
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                        sampleRate);

            sensorManager.registerListener(this,
                    sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                    sampleRate);
        }
        if (sendOrientation) {
            if (hasGyro)
                sensorManager.registerListener(this,
                        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
                        sampleRate);
            else {
                if (!sendRaw) {
                    sensorManager.registerListener(this,
                            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                            sampleRate);
                    sensorManager.registerListener(this,
                            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                            sampleRate);
                }
            }
        }
    }

    @SuppressWarnings("unused") // Public methods called by the Activity are marked this way
    public String getLastError() {
        synchronized (this) {
            return lastError;
        }
    }

    private void setLastError(String e) {
        synchronized (this) { lastError = e; }
        debugError = e;
        updateNotification("⚠ Connection error – retrying...", android.R.drawable.ic_dialog_alert);
    }

    @SuppressWarnings("unused")
    public String debug(float[] acc_, float[] mag_, float[] gyr_, float[] imu_) {
        synchronized (this) {
            System.arraycopy(acc, 0, acc_, 0, 3);
            System.arraycopy(mag, 0, mag_, 0, 3);
            System.arraycopy(gyr, 0, gyr_, 0, 3);
            System.arraycopy(imu, 0, imu_, 0, 3);

            String err = lastError;
            lastError = null;
            return err;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class MyBinder extends Binder {
        @SuppressWarnings("unused")
        UdpSenderService getService() {
            return UdpSenderService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        serviceChannel.setSound(null, null); // Show status bar icon without making a sound
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    public void stop() {
        started = false;
        handler.removeCallbacks(releaseVolUp);
        handler.removeCallbacks(releaseVolDown);
        buttonState.set(0);
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        try {
            unregisterReceiver(screen_off_receiver);
        } catch (Exception e) {
            Log.w("UdpService", "Receiver already unregistered");
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
            wifiLock = null;
        }
        if (sensorManager != null)
            sensorManager.unregisterListener(this);
        running = false;
        synchronized (this) {
            notifyAll();
        }
        if (worker != null) {
            try {
                worker.join(500); // Timeout to prevent deadlocks
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public static final String CHANNEL_ID = "ForegroundServiceChannel";

    private Notification buildNotification(String text, int iconRes) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this,
                0, openIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, UdpSenderService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this,
                0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("WishIMU")
                .setContentText(text)
                .setSmallIcon(iconRes)
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "Stop", stopPending).build())
                .build();
    }

    private void updateNotification(String text, int iconRes) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(1, buildNotification(text, iconRes));
    }

    private void startForegroundWithNotification(String ip, int port) {
        Notification notification = buildNotification(
                "→ " + ip + ":" + port, R.drawable.ic_notify);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, notification);
        }
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stop();
            stopSelf();
            return START_NOT_STICKY;
        }

        // START_STICKY can deliver a null intent when the service is restarted after being killed.
        // Without valid connection parameters there is nothing useful to do.
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        stop();
        PowerManager.WakeLock wl = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_WAKE_LOCK);
        // WIFI_MODE_FULL_HIGH_PERF deprecated in API 31; LOW_LATENCY requires API 29.
        // minSdk = 28 so the fallback path is needed for API 28-only; suppress is the only option.
        @SuppressWarnings("deprecation")
        WifiManager.WifiLock nl = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG_WIFI_LOCK)
                : mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG_WIFI_LOCK);

        // registerReceiver without flags deprecated in API 33; ContextCompat handles it transparently
        ContextCompat.registerReceiver(this, screen_off_receiver,
                new IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED);

        deviceIndex = intent.getByteExtra("deviceIndex", (byte) 0);
        final int port = intent.getIntExtra("port", 5555);
        final String ip = intent.getStringExtra("toIp");

        sendRaw = intent.getBooleanExtra("sendRaw", true);
        sendOrientation = intent.getBooleanExtra("sendOrientation", true);
        sampleRate = intent.getIntExtra("sampleRate", SensorManager.SENSOR_DELAY_FASTEST);

        // Call startForeground early to satisfy Android's 5-second foreground requirement
        startForegroundWithNotification(ip, port);

        debugError = null;
        running = true;

        worker = new Thread(() -> {
            while (running) {
                try {
                    socket = new DatagramSocket();
                    socket.setSoTimeout(1000); // lets the receiver thread wake up to check timeout
                    final InetAddress targetAddr = InetAddress.getByName(ip);
                    p.setAddress(targetAddr);
                    p.setPort(port);
                    connectionStartTime = System.currentTimeMillis();
                    lastAckTime = 0;
                    debugError = null;
                    updateNotification("→ " + ip + ":" + port, R.drawable.ic_notify);

                    startAckReceiver(targetAddr, ip, port);

                    while (running) {
                        synchronized (this) {
                            this.wait();
                            if (running) Send();
                        }
                    }
                } catch (InterruptedException e) {
                    break;                      // stop() notified: clean exit
                } catch (IOException e) {
                    Log.e("UDP", "Worker error, retrying in 2s", e);
                    setLastError(e.getMessage());
                    if (socket != null) { socket.close(); socket = null; }
                    if (!running) break;
                    try {
                        //noinspection BusyWait
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) { break; }
                } finally {
                    if (socket != null) { socket.close(); socket = null; }
                }
            }
        });

        worker.start();
        hasGyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null;
        register_sensors();

        wakeLock = wl;
        wifiLock = nl;

        wifiLock.acquire();
        // 4-hour safety timeout. In normal use stop() releases the lock well before this.
        // The timeout only fires if the process dies without calling onDestroy() (crash, OOM).
        wakeLock.acquire(4 * 60 * 60 * 1000L);

        // MediaSession intercepts volume keys via VolumeProvider even on the lock screen.
        // onKeyDown in MainActivity handles the screen-on case; this handles screen-off.
        // VolumeProvider receives key-press events but NOT key-up, so each event schedules
        // an auto-release 600 ms later; key-repeat events reset the timer, keeping the
        // button held for as long as the physical key is held.
        //
        // For the lock-screen path, the session MUST be in STATE_PLAYING so that Android's
        // MediaSessionStack ranks it as the active session for volume key routing. A session
        // in STATE_STOPPED is excluded from the priority queue and never receives volume events.
        volumeButtonsEnabled = intent.getBooleanExtra("volumeButtons", false);
        mediaSession = new MediaSession(this, "WishIMU");
        configureMediaSessionFlags();   // deprecated API scoped to its own method
        mediaSession.setPlaybackToRemote(new VolumeProvider(
                VolumeProvider.VOLUME_CONTROL_RELATIVE, 15, 7) {
            @Override
            public void onAdjustVolume(int direction) {
                if (volumeButtonsEnabled && direction != 0) {
                    // Auto-release delay must exceed Android's key-repeat initial delay (~500 ms)
                    // so the first key-repeat resets the timer before it fires.
                    // 600 ms keeps the button held continuously while the key is held.
                    if (direction > 0) {  // VOLUME_ADJUST_RAISE = 1
                        buttonState.updateAndGet(b -> b | 0x01);
                        handler.removeCallbacks(releaseVolUp);
                        handler.postDelayed(releaseVolUp, 600);
                    } else {              // VOLUME_ADJUST_LOWER = -1
                        buttonState.updateAndGet(b -> b | 0x02);
                        handler.removeCallbacks(releaseVolDown);
                        handler.postDelayed(releaseVolDown, 600);
                    }
                } else {
                    // Volume buttons mode is off — let the system adjust volume normally
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (am != null) {
                        am.adjustSuggestedStreamVolume(direction,
                                AudioManager.USE_DEFAULT_STREAM_TYPE, AudioManager.FLAG_SHOW_UI);
                    }
                }
            }
        });
        mediaSession.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 0)
                .build());
        mediaSession.setActive(true);

        started = true;
        return START_STICKY;
    }

    /**
     * Sets FLAG_HANDLES_MEDIA_BUTTONS and an empty Callback on the MediaSession.
     * Both are deprecated since API 31 ("has no effect") but in practice some OEM firmware
     * and older Android builds (API 28-30) still check them when deciding which session
     * receives volume key events on the lock screen.  Keeping them avoids a regression on
     * those devices.  The @SuppressWarnings is scoped to this method so the rest of the
     * class is not affected.
     */
    @SuppressWarnings("deprecation")
    private void configureMediaSessionFlags() {
        mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS);
        mediaSession.setCallback(new MediaSession.Callback() {});
    }

    /**
     * Starts a daemon thread that listens for 1-byte ack packets sent back by ps3pie after
     * each received IMU packet. On each ack, resets the connection-lost timer and clears any
     * error. On SocketTimeoutException (every 1 s), checks whether ACK_TIMEOUT_MS has elapsed
     * without an ack and sets a "No response from host" error if so.
     * Compatible with the original FreePIE app which sends no acks; in that case the error
     * appears after 5 s but data delivery is unaffected.
     */
    private void startAckReceiver(InetAddress targetAddr, String ip, int port) {
        Thread receiver = new Thread(() -> {
            byte[] buf = new byte[4];
            DatagramPacket ackPkt = new DatagramPacket(buf, buf.length);
            while (running) {
                try {
                    ackPkt.setLength(buf.length);
                    socket.receive(ackPkt);
                    if (targetAddr.equals(ackPkt.getAddress())) {
                        lastAckTime = System.currentTimeMillis();
                        if (debugError != null) {
                            debugError = null;
                            updateNotification("→ " + ip + ":" + port, R.drawable.ic_notify);
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    long now = System.currentTimeMillis();
                    boolean noAckYet = lastAckTime == 0 && now - connectionStartTime > ACK_TIMEOUT_MS;
                    boolean ackLost  = lastAckTime > 0  && now - lastAckTime       > ACK_TIMEOUT_MS;
                    if ((noAckYet || ackLost) && debugError == null) {
                        setLastError("No response from host");
                    }
                } catch (IOException e) {
                    break; // socket closed or real error — exit cleanly
                }
            }
        });
        receiver.setDaemon(true);
        receiver.start();
    }

    private byte getFlagByte(boolean raw, boolean orientation) {
        return (byte) ((raw ? SEND_RAW : SEND_NONE) |
                (orientation ? SEND_ORIENTATION : SEND_NONE) |
                SEND_BUTTONS);
    }

    private int put_float(float f, int pos, byte[] buf) {
        int tmp = Float.floatToIntBits(f);
        buf[pos++] = (byte) (tmp); // low byte (tmp >> 0 is redundant)
        buf[pos++] = (byte) (tmp >> 8);
        buf[pos++] = (byte) (tmp >> 16);
        buf[pos++] = (byte) (tmp >> 24);
        return pos;
    }

    private void Send() throws IOException {
        int pos = 0;
        buf[pos++] = deviceIndex;
        buf[pos++] = getFlagByte(sendRaw, sendOrientation);

        if (sendRaw) {
            for (int i = 0; i < 3; i++) pos = put_float(acc[i], pos, buf);
            for (int i = 0; i < 3; i++) pos = put_float(gyr[i], pos, buf);
            for (int i = 0; i < 3; i++) pos = put_float(mag[i], pos, buf);
        }

        if (sendOrientation) {
            for (int i = 0; i < 3; i++) pos = put_float(imu[i], pos, buf);
        }

        buf[pos++] = (byte) buttonState.get();

        p.setData(buf, 0, pos);
        if (socket != null) socket.send(p);
    }

    @SuppressWarnings("unused")
    public boolean isRunning() {
        return running;
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        synchronized (this) {
            switch (sensorEvent.sensor.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    System.arraycopy(sensorEvent.values, 0, acc, 0, 3);
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    System.arraycopy(sensorEvent.values, 0, mag, 0, 3);
                    break;
                case Sensor.TYPE_GYROSCOPE:
                    System.arraycopy(sensorEvent.values, 0, gyr, 0, 3);
                    break;
                case Sensor.TYPE_ROTATION_VECTOR:
                    System.arraycopy(sensorEvent.values, 0, rotationVector, 0, 3);
                    break;
            }

            if (sendOrientation) {
                if (!hasGyro) {
                    if (SensorManager.getRotationMatrix(R_, I, acc, mag)) {
                        SensorManager.getOrientation(R_, imu);
                    }
                } else {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector);
                    SensorManager.getOrientation(rotationMatrix, imu);
                }
            }

            System.arraycopy(acc, 0, debugAcc, 0, 3);
            System.arraycopy(gyr, 0, debugGyr, 0, 3);
            System.arraycopy(mag, 0, debugMag, 0, 3);
            System.arraycopy(imu, 0, debugImu, 0, 3);

            notifyAll();
        }
    }
}