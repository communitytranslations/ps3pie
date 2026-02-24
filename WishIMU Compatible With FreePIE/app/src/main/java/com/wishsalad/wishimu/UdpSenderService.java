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
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class UdpSenderService extends Service implements SensorEventListener {
    /** True while the service is running. Read by MainActivity.onResume() to sync UI state. */
    public static volatile boolean started = false;

    /** Latest sensor snapshot for debug display. Written under synchronized(instance), read by MainActivity Handler. */
    public static final float[] debugAcc = new float[3];
    public static final float[] debugGyr = new float[3];
    public static final float[] debugMag = new float[3];
    public static final float[] debugImu = new float[3];

    private final IBinder mBinder = new MyBinder();
    private PowerManager mPowerManager;
    private WifiManager mWifiManager;

    private static final byte SEND_RAW = 0x01;
    private static final byte SEND_ORIENTATION = 0x02;
    private static final byte SEND_NONE = 0x00;
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

    private Thread worker;
    private volatile boolean running;
    private boolean hasGyro;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;
    private final DatagramPacket p = new DatagramPacket(new byte[]{}, 0);
    private final byte[] buf = new byte[50];

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
        synchronized (this) {
            lastError = e;
        }
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
        enableNotification();
    }

    @Override
    public void onDestroy() {
        stop();
        super.onDestroy();
    }

    public void stop() {
        started = false;
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

    private void enableNotification() {
        NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_ID,
                "Foreground Service Channel",
                NotificationManager.IMPORTANCE_HIGH
        );

        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(serviceChannel);
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("WishIMU Running")
                .setContentText("Sending sensor data via UDP")
                .setContentIntent(pendingIntent)
                .build();

        // Check API level at runtime to include the foreground service type where required
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10 (API 29) and above: supply the service type
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            // Android 9 (API 28) and below: use the classic overload
            startForeground(1, notification);
        }
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stop();
        PowerManager.WakeLock wl = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG_WAKE_LOCK);
        // WIFI_MODE_FULL_HIGH_PERF deprecated in API 29; use LOW_LATENCY on newer devices
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

        running = true;

        worker = new Thread(() -> {
            try {
                socket = new DatagramSocket();
                p.setAddress(InetAddress.getByName(ip));
                p.setPort(port);

                while (running) {
                    synchronized (this) {
                        this.wait();
                        if (running) Send();
                    }
                }
            } catch (InterruptedException | IOException e) {
                Log.e("UDP", "Worker thread error", e);
                setLastError("Communication error: " + e.getMessage());
            } finally {
                if (socket != null) {
                    socket.close();
                    socket = null;
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

        started = true;
        return START_STICKY;
    }

    private byte getFlagByte(boolean raw, boolean orientation) {
        return (byte) ((raw ? SEND_RAW : SEND_NONE) |
                (orientation ? SEND_ORIENTATION : SEND_NONE));
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