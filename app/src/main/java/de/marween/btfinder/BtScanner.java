package de.marween.btfinder;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/** Sucht gleichzeitig per Bluetooth Low Energy und klassischem Bluetooth. */
@SuppressLint("MissingPermission")
public class BtScanner {

    public interface Listener {
        void onSeen(String address, String name, int rssi, boolean audio, boolean bonded);
    }

    private final Context ctx;
    private final Listener listener;
    private final BluetoothAdapter adapter;
    private BluetoothLeScanner le;
    private boolean running;

    public BtScanner(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    public BluetoothAdapter adapter() { return adapter; }

    public boolean isRunning() { return running; }

    private final ScanCallback leCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { handle(r); }
        @Override public void onBatchScanResults(java.util.List<ScanResult> list) {
            for (ScanResult r : list) handle(r);
        }
    };

    private void handle(ScanResult r) {
        BluetoothDevice d = r.getDevice();
        String name = null;
        ScanRecord rec = r.getScanRecord();
        if (rec != null) name = rec.getDeviceName();
        if (name == null) name = safeName(d);
        listener.onSeen(d.getAddress(), name, r.getRssi(), isAudio(d), isBonded(d));
    }

    private final BroadcastReceiver classic = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                int rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                if (rssi == Short.MIN_VALUE) return;
                String name = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (name == null) name = safeName(d);
                BluetoothClass bc = i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS);
                boolean audio = (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) || isAudio(d);
                listener.onSeen(d.getAddress(), name, rssi, audio, isBonded(d));
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                // Klassische Suche dauert ~12 s – danach sofort neu starten
                if (running && adapter != null) {
                    try { adapter.startDiscovery(); } catch (SecurityException ignored) { }
                }
            }
        }
    };

    public boolean start() {
        if (running) return true;
        if (adapter == null || !adapter.isEnabled()) return false;
        running = true;
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(classic, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(classic, f);
        try {
            le = adapter.getBluetoothLeScanner();
            if (le != null) {
                ScanSettings s = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .setReportDelay(0)
                        .build();
                le.startScan(null, s, leCallback);
            }
        } catch (SecurityException | IllegalStateException ignored) { }
        try { adapter.startDiscovery(); } catch (SecurityException ignored) { }
        return true;
    }

    public void stop() {
        if (!running) return;
        running = false;
        try { ctx.unregisterReceiver(classic); } catch (IllegalArgumentException ignored) { }
        try { if (le != null) le.stopScan(leCallback); } catch (SecurityException | IllegalStateException ignored) { }
        try { if (adapter != null) adapter.cancelDiscovery(); } catch (SecurityException ignored) { }
    }

    static String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch (SecurityException e) { return null; }
    }

    static boolean isBonded(BluetoothDevice d) {
        try { return d.getBondState() == BluetoothDevice.BOND_BONDED; } catch (SecurityException e) { return false; }
    }

    static boolean isAudio(BluetoothDevice d) {
        try {
            BluetoothClass bc = d.getBluetoothClass();
            return bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (SecurityException e) { return false; }
    }
}
