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
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Eine einzige, durchgehend laufende Suche für die ganze App.
 * Android blockiert stillschweigend, wenn eine App die BLE-Suche öfter als 5× in 30 s startet –
 * deshalb wird hier nicht bei jedem Bildschirmwechsel neu gestartet.
 */
@SuppressLint("MissingPermission")
public class BtScanner {

    public interface Listener {
        void onSeen(String address, String name, int rssi, boolean audio, boolean bonded, String signature);
    }

    private static BtScanner instance;

    public static synchronized BtScanner get(Context c) {
        if (instance == null) instance = new BtScanner(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final BluetoothAdapter adapter;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner le;
    private boolean leRunning, classicWanted, receiverOn;
    private int users;
    public volatile int received;
    public volatile String error;

    private BtScanner(Context ctx) {
        this.ctx = ctx;
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
    }

    public BluetoothAdapter adapter() { return adapter; }

    public boolean isRunning() { return leRunning; }

    private final ScanCallback leCallback = new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult r) { handle(r); }
        @Override public void onBatchScanResults(List<ScanResult> list) { for (ScanResult r : list) handle(r); }
        @Override public void onScanFailed(int code) {
            leRunning = false;
            error = code == 6 /* SCAN_FAILED_SCANNING_TOO_FREQUENTLY */
                    ? "Android hat die Suche kurz gesperrt (zu oft gestartet). Bitte 30 Sekunden warten."
                    : "Bluetooth-Suche fehlgeschlagen (Code " + code + "). Bluetooth einmal aus- und einschalten.";
        }
    };

    private void handle(ScanResult r) {
        received++;
        error = null;
        BluetoothDevice d = r.getDevice();
        ScanRecord rec = r.getScanRecord();
        String name = rec != null ? rec.getDeviceName() : null;
        if (name == null) name = safeName(d);
        String sig = signature(rec);
        for (Listener l : listeners) l.onSeen(d.getAddress(), name, r.getRssi(), isAudio(d), isBonded(d), sig);
    }

    /** „Funk-Fingerabdruck“: Hersteller-IDs + Dienste – bleibt gleich, auch wenn die Adresse wechselt. */
    static String signature(ScanRecord rec) {
        if (rec == null) return "";
        List<String> parts = new ArrayList<>();
        SparseArray<byte[]> m = rec.getManufacturerSpecificData();
        if (m != null) for (int i = 0; i < m.size(); i++) {
            byte[] v = m.valueAt(i);
            parts.add("m" + Integer.toHexString(m.keyAt(i)) + ":" + (v == null ? 0 : v.length));
        }
        List<ParcelUuid> u = rec.getServiceUuids();
        if (u != null) for (ParcelUuid p : u) parts.add("s" + p.getUuid().toString().substring(4, 8));
        Map<ParcelUuid, byte[]> sd = rec.getServiceData();
        if (sd != null) for (ParcelUuid p : sd.keySet()) parts.add("d" + p.getUuid().toString().substring(4, 8));
        Collections.sort(parts);
        return String.join(",", parts);
    }

    private final BroadcastReceiver classic = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(a)) {
                BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                int rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                if (rssi == Short.MIN_VALUE) return;
                received++;
                String name = i.getStringExtra(BluetoothDevice.EXTRA_NAME);
                if (name == null) name = safeName(d);
                BluetoothClass bc = i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS);
                boolean audio = (bc != null && bc.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) || isAudio(d);
                for (Listener l : listeners) l.onSeen(d.getAddress(), name, rssi, audio, isBonded(d), "classic");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(a)) {
                if (classicWanted && adapter != null) {
                    try { adapter.startDiscovery(); } catch (SecurityException ignored) { }
                }
            }
        }
    };

    public void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }

    public void removeListener(Listener l) { listeners.remove(l); }

    /** Ein Bildschirm braucht die Suche. */
    public synchronized boolean acquire() {
        main.removeCallbacks(stopLater);
        users++;
        return ensureLe();
    }

    /** Ein Bildschirm braucht sie nicht mehr – erst nach 20 s wirklich stoppen. */
    public synchronized void release() {
        users = Math.max(0, users - 1);
        if (users == 0) { setClassic(false); main.postDelayed(stopLater, 20000); }
    }

    private final Runnable stopLater = () -> {
        synchronized (BtScanner.this) { if (users == 0) stopAll(); }
    };

    private boolean ensureLe() {
        if (adapter == null || !adapter.isEnabled()) return false;
        if (leRunning) return true;
        try {
            le = adapter.getBluetoothLeScanner();
            if (le == null) return false;
            ScanSettings s = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                    .setReportDelay(0)
                    .build();
            le.startScan(null, s, leCallback);
            leRunning = true;
            return true;
        } catch (SecurityException | IllegalStateException e) {
            error = "Bluetooth-Suche nicht möglich: " + e.getMessage();
            return false;
        }
    }

    /** Klassische Suche findet Kopfhörer im Kopplungsmodus, bremst aber BLE aus. */
    public void setClassic(boolean on) {
        classicWanted = on;
        if (adapter == null) return;
        if (on) {
            if (!receiverOn) {
                IntentFilter f = new IntentFilter();
                f.addAction(BluetoothDevice.ACTION_FOUND);
                f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(classic, f, Context.RECEIVER_EXPORTED);
                else ctx.registerReceiver(classic, f);
                receiverOn = true;
            }
            try { if (!adapter.isDiscovering()) adapter.startDiscovery(); } catch (SecurityException ignored) { }
        } else {
            try { adapter.cancelDiscovery(); } catch (SecurityException ignored) { }
        }
    }

    private void stopAll() {
        setClassic(false);
        if (receiverOn) {
            try { ctx.unregisterReceiver(classic); } catch (IllegalArgumentException ignored) { }
            receiverOn = false;
        }
        try { if (le != null && leRunning) le.stopScan(leCallback); } catch (SecurityException | IllegalStateException ignored) { }
        leRunning = false;
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
