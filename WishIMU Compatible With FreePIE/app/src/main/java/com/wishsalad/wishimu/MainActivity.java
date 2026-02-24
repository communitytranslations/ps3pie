package com.wishsalad.wishimu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.Locale;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.ToggleButton;

public class MainActivity extends Activity {

    private static final String IP = "ip";
    private static final String PORT = "port";
    private static final String INDEX = "index";
    private static final String SEND_ORIENTATION = "send_orientation";
    private static final String SEND_RAW = "send_raw";
    private static final String SAMPLE_RATE = "sample_rate";

    private static final String DEBUG_FORMAT = "%.2f  %.2f  %.2f";

    private ToggleButton start;
    private EditText txtIp;
    private EditText txtPort;
    private Spinner spnIndex;
    private CheckBox chkSendOrientation;
    private CheckBox chkSendRaw;
    private Spinner spnSampleRate;
    private CheckBox chkDebug;
    private LinearLayout debugView;

    private TextView tvAcc;
    private TextView tvGyr;
    private TextView tvMag;
    private TextView tvImu;

    private boolean isServiceRunning;

    private final Handler debugHandler = new Handler(Looper.getMainLooper());
    private final Runnable debugRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isServiceRunning || !chkDebug.isChecked()) return;
            tvAcc.setText(String.format(Locale.ROOT, DEBUG_FORMAT,
                    UdpSenderService.debugAcc[0], UdpSenderService.debugAcc[1], UdpSenderService.debugAcc[2]));
            tvGyr.setText(String.format(Locale.ROOT, DEBUG_FORMAT,
                    UdpSenderService.debugGyr[0], UdpSenderService.debugGyr[1], UdpSenderService.debugGyr[2]));
            tvMag.setText(String.format(Locale.ROOT, DEBUG_FORMAT,
                    UdpSenderService.debugMag[0], UdpSenderService.debugMag[1], UdpSenderService.debugMag[2]));
            tvImu.setText(String.format(Locale.ROOT, DEBUG_FORMAT,
                    UdpSenderService.debugImu[0], UdpSenderService.debugImu[1], UdpSenderService.debugImu[2]));
            debugHandler.postDelayed(this, 100);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final SharedPreferences preferences = getPreferences(MODE_PRIVATE);

        LinearLayout emptyLayout = findViewById(R.id.empty_layout);

        txtIp = findViewById(R.id.ip);
        txtPort = findViewById(R.id.port);
        spnIndex = findViewById(R.id.index);
        chkSendOrientation = findViewById(R.id.sendOrientation);
        chkSendRaw = findViewById(R.id.sendRaw);
        spnSampleRate = findViewById(R.id.sampleRate);
        start = findViewById(R.id.start);
        debugView = findViewById(R.id.debugView);
        chkDebug = findViewById(R.id.debug);

        tvAcc = findViewById(R.id.acc);
        tvGyr = findViewById(R.id.gyr);
        tvMag = findViewById(R.id.mag);
        tvImu = findViewById(R.id.imu);

        txtIp.setText(preferences.getString(IP, "192.168.1.1"));
        txtPort.setText(preferences.getString(PORT, "5555"));
        chkSendOrientation.setChecked(preferences.getBoolean(SEND_ORIENTATION, true));
        chkSendRaw.setChecked(preferences.getBoolean(SEND_RAW, true));
        populateSampleRates(preferences.getInt(SAMPLE_RATE, 0));
        populateIndex(preferences.getInt(INDEX, 0));

        chkDebug.setOnClickListener(view -> {
            setDebugVisibility(chkDebug.isChecked());
            if (chkDebug.isChecked() && isServiceRunning) {
                debugHandler.post(debugRunnable);
            } else {
                debugHandler.removeCallbacks(debugRunnable);
            }
        });

        isServiceRunning = false;

        start.setOnClickListener(view -> {
            save();
            if (!isServiceRunning) {
                String ip = txtIp.getText().toString();
                int port = Integer.parseInt(txtPort.getText().toString());
                boolean sendOrientation = chkSendOrientation.isChecked();
                boolean sendRaw = chkSendRaw.isChecked();
                startUdpSenderService(ip, port, getSelectedDeviceIndex(), sendOrientation, sendRaw, getSelectedSampleRateId());
                isServiceRunning = true;
                if (chkDebug.isChecked()) debugHandler.post(debugRunnable);
            } else {
                debugHandler.removeCallbacks(debugRunnable);
                stopUdpSenderService();
                isServiceRunning = false;
            }

            start.setChecked(isServiceRunning);
            txtIp.setEnabled(!isServiceRunning);
            txtPort.setEnabled(!isServiceRunning);
            chkSendOrientation.setEnabled(!isServiceRunning);
            chkSendRaw.setEnabled(!isServiceRunning);
            emptyLayout.requestFocus();
        });

        emptyLayout.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Sync toggle state with actual service state (survives rotation / app re-open)
        isServiceRunning = UdpSenderService.started;
        start.setChecked(isServiceRunning);
        txtIp.setEnabled(!isServiceRunning);
        txtPort.setEnabled(!isServiceRunning);
        chkSendOrientation.setEnabled(!isServiceRunning);
        chkSendRaw.setEnabled(!isServiceRunning);
        setDebugVisibility(chkDebug.isChecked());
        if (isServiceRunning && chkDebug.isChecked()) {
            debugHandler.post(debugRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        debugHandler.removeCallbacks(debugRunnable);
    }

    private void startUdpSenderService(String toIp, int port, byte deviceIndex, boolean sendOrientation, boolean sendRaw, int sampleRate) {
        Intent serviceIntent = new Intent(this, UdpSenderService.class);
        serviceIntent.putExtra("toIp", toIp);
        serviceIntent.putExtra("port", port);
        serviceIntent.putExtra("deviceIndex", deviceIndex);
        serviceIntent.putExtra("sendOrientation", sendOrientation);
        serviceIntent.putExtra("sendRaw", sendRaw);
        serviceIntent.putExtra("sampleRate", sampleRate);
        startForegroundService(serviceIntent);
    }

    private void stopUdpSenderService() {
        Intent serviceIntent = new Intent(this, UdpSenderService.class);
        stopService(serviceIntent);
    }

    private void setDebugVisibility(boolean show) {
        debugView.setVisibility(show ? LinearLayout.VISIBLE : LinearLayout.INVISIBLE);
    }

    private void populateIndex(int defaultIndex) {
        List<DeviceIndex> deviceIndexes = new ArrayList<>();
        for (byte index = 0; index < 16; index++) {
            deviceIndexes.add(new DeviceIndex(index));
        }

        DeviceIndex selectedDeviceIndex = null;
        for (DeviceIndex deviceIndex : deviceIndexes) {
            if (deviceIndex.getIndex() == defaultIndex) {
                selectedDeviceIndex = deviceIndex;
                break;
            }
        }

        populateSpinner(spnIndex, deviceIndexes, selectedDeviceIndex);
    }

    private void populateSampleRates(int defaultSampleRate) {
        // delay information from http://developer.android.com/guide/topics/sensors/sensors_overview.html#sensors-monitor
        List<SampleRate> sampleRates = Arrays.asList(
                new SampleRate(SensorManager.SENSOR_DELAY_NORMAL, "Slowest - 5 FPS"),
                new SampleRate(SensorManager.SENSOR_DELAY_UI, "Average - 16 FPS"),
                new SampleRate(SensorManager.SENSOR_DELAY_GAME, "Fast - 50 FPS"),
                new SampleRate(SensorManager.SENSOR_DELAY_FASTEST, "Fastest - no delay")
        );

        SampleRate selectedSampleRate = null;
        for (SampleRate sampleRate : sampleRates) {
            if (sampleRate.getId() == defaultSampleRate) {
                selectedSampleRate = sampleRate;
                break;
            }
        }

        populateSpinner(spnSampleRate, sampleRates, selectedSampleRate);
    }

    private <T> void populateSpinner(Spinner spinner, List<T> items, T selectedItem) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
        spinner.setAdapter(adapter);
        spinner.setSelection(items.indexOf(selectedItem), false);
    }

    private int getSelectedSampleRateId() {
        return ((SampleRate) spnSampleRate.getSelectedItem()).getId();
    }

    private byte getSelectedDeviceIndex() {
        return ((DeviceIndex) spnIndex.getSelectedItem()).getIndex();
    }

    private void save() {
        final SharedPreferences preferences = getPreferences(MODE_PRIVATE);

        preferences.edit()
                .putString(IP, txtIp.getText().toString())
                .putString(PORT, txtPort.getText().toString())
                .putInt(INDEX, getSelectedDeviceIndex())
                .putBoolean(SEND_ORIENTATION, chkSendOrientation.isChecked())
                .putBoolean(SEND_RAW, chkSendRaw.isChecked())
                .putInt(SAMPLE_RATE, getSelectedSampleRateId())
                .apply();
    }
}
