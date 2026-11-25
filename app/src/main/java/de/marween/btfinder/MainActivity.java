package de.marween.btfinder;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity implements BtScanner.Listener {

    static final int BG = 0xFF121218, CARD = 0xFF1F1F2A, ACCENT = 0xFFFF5A36, MUTED = 0xFF9A9AAD;

    static class Dev {
        String address, name;
        int rssi = -127;
        double smooth = -127;
        long lastSeen;
        boolean audio, bonded;
        String sig = "";
    }

    private final Map<String, Dev> devices = new HashMap<>();
    private final List<Dev> shown = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BtScanner scanner;
    private TextView status;
    private Button scanBtn;
    private CheckBox onlyNamed;
    private DevAdapter listAdapter;
    private boolean searching;
    private boolean learning;
    private final Map<String, double[]> learnSeen = new HashMap<>();
    private final Map<String, String[]> learnInfo = new HashMap<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        scanner = BtScanner.get(this);
        buildUi();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = ins.getSystemWindowInsetTop(), bottom = ins.getSystemWindowInsetBottom();
            v.setPadding(p, p + top, p, p + bottom);
            return ins;
        });

        TextView title = new TextView(this);
        title.setText("🎧 Kopfhörer-Finder");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Starte die Suche und tippe auf deinen Kopfhörer. Dann zeigt dir die App mit „heiß / kalt“, ob du näher kommst.");
        hint.setTextColor(MUTED);
        hint.setTextSize(14);
        hint.setPadding(0, dp(6), 0, dp(12));
        root.addView(hint);

        scanBtn = new Button(this);
        scanBtn.setText("Suche starten");
        scanBtn.setTextColor(Color.WHITE);
        scanBtn.setTextSize(18);
        scanBtn.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(dp(14));
        scanBtn.setBackground(bg);
        scanBtn.setOnClickListener(v -> toggleScan());
        root.addView(scanBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        Button learn = new Button(this);
        learn.setText("🎯 Mit dem anderen Hörer anlernen");
        learn.setTextColor(Color.WHITE);
        learn.setTextSize(16);
        learn.setAllCaps(false);
        GradientDrawable lbg = new GradientDrawable();
        lbg.setColor(0xFF7B2FF7);
        lbg.setCornerRadius(dp(14));
        learn.setBackground(lbg);
        learn.setOnClickListener(v -> startLearning());
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        llp.topMargin = dp(8);
        root.addView(learn, llp);

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(13);
        status.setPadding(0, dp(10), 0, dp(4));
        root.addView(status);

        onlyNamed = new CheckBox(this);
        onlyNamed.setText("Nur Geräte mit Namen anzeigen");
        onlyNamed.setTextColor(Color.WHITE);
        onlyNamed.setChecked(true);
        onlyNamed.setOnCheckedChangeListener((c, x) -> refresh());
        root.addView(onlyNamed);

        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(dp(8));
        listAdapter = new DevAdapter();
        list.setAdapter(listAdapter);
        list.setOnItemClickListener((parent, view, pos, id) -> openTracker(shown.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView foot = new TextView(this);
        foot.setText("© marween");
        foot.setTextColor(MUTED);
        foot.setTextSize(11);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot);

        setContentView(root);
        loadBonded();
        refresh();
    }

    /** Gekoppelte Audio-Geräte immer anzeigen, auch bevor sie gefunden wurden. */
    private void loadBonded() {
        BluetoothAdapter a = scanner.adapter();
        if (a == null || !hasPermissions()) return;
        try {
            for (BluetoothDevice d : a.getBondedDevices()) {
                if (!BtScanner.isAudio(d)) continue;
                Dev dev = devices.get(d.getAddress());
                if (dev == null) {
                    dev = new Dev();
                    dev.address = d.getAddress();
                    devices.put(dev.address, dev);
                }
                dev.name = BtScanner.safeName(d);
                dev.audio = true;
                dev.bonded = true;
            }
        } catch (SecurityException ignored) { }
    }

    // ---------- Suche ----------

    private void toggleScan() {
        if (searching) { stopScan(); return; }
        if (!ensureReady()) return;
        loadBonded();
        scanner.addListener(this);
        if (scanner.acquire()) {
            searching = true;
            scanner.setClassic(true);
            scanBtn.setText("Suche stoppen");
            ui.removeCallbacks(ticker);
            ui.post(ticker);
        } else {
            scanner.release();
            status.setText(scanner.error != null ? scanner.error : "Bluetooth konnte nicht gestartet werden.");
        }
    }

    private void stopScan() {
        if (searching) { scanner.release(); searching = false; }
        scanner.removeListener(this);
        scanBtn.setText("Suche starten");
        ui.removeCallbacks(ticker);
        refresh();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() { refresh(); ui.postDelayed(this, 700); }
    };

    @Override
    public void onSeen(String address, String name, int rssi, boolean audio, boolean bonded, String sig) {
        ui.post(() -> {
            if (learning) {
                double[] v = learnSeen.get(address);
                if (v == null) learnSeen.put(address, new double[]{rssi, 1});
                else { v[0] = Math.max(v[0], rssi); v[1]++; }
                String[] info = learnInfo.get(address);
                String nm = name != null && !name.isEmpty() ? name : (info != null ? info[0] : null);
                learnInfo.put(address, new String[]{nm, sig});
            }
            Dev d = devices.get(address);
            if (d == null) { d = new Dev(); d.address = address; devices.put(address, d); d.smooth = rssi; }
            if (name != null && !name.isEmpty()) d.name = name;
            d.rssi = rssi;
            d.smooth = d.lastSeen == 0 ? rssi : d.smooth * 0.6 + rssi * 0.4;
            d.lastSeen = System.currentTimeMillis();
            d.audio |= audio;
            d.bonded |= bonded;
            if (sig != null && !sig.isEmpty() && !"classic".equals(sig)) d.sig = sig;
        });
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        shown.clear();
        for (Dev d : devices.values()) {
            if (onlyNamed.isChecked() && (d.name == null || d.name.isEmpty()) && !d.bonded) continue;
            shown.add(d);
        }
        Collections.sort(shown, (x, y) -> {
            // Kopfhörer zuerst, dann nach Signalstärke
            int px = (x.audio || x.bonded) ? 1 : 0, py = (y.audio || y.bonded) ? 1 : 0;
            if (px != py) return py - px;
            boolean fx = now - x.lastSeen < 15000 && x.lastSeen > 0, fy = now - y.lastSeen < 15000 && y.lastSeen > 0;
            if (fx != fy) return fy ? 1 : -1;
            return Double.compare(y.smooth, x.smooth);
        });
        int live = 0;
        for (Dev d : devices.values()) if (d.lastSeen > 0 && now - d.lastSeen < 15000) live++;
        String st = searching
                ? "Suche läuft … " + live + " Geräte in Reichweite · " + scanner.received + " Signale empfangen"
                : "Suche gestoppt";
        if (scanner.error != null) st = "⚠ " + scanner.error;
        status.setText(st);
        listAdapter.notifyDataSetChanged();
    }

    private void openTracker(Dev d) {
        Intent i = new Intent(this, TrackActivity.class);
        i.putExtra("address", d.address);
        i.putExtra("name", d.name);
        i.putExtra("sig", d.sig);
        stopScan();
        startActivity(i);
    }

    // ---------- Anlernen ----------

    private void startLearning() {
        if (!ensureReady()) return;
        new AlertDialog.Builder(this)
                .setTitle("Anderen Hörer anlernen")
                .setMessage("1. Nimm den Hörer, den du noch hast, aus dem Case.\n"
                        + "2. Verbindet er sich mit dem Handy, trenne ihn in den Bluetooth-Einstellungen (Gerät antippen → Trennen). Verbundene Hörer senden oft kein Suchsignal.\n"
                        + "3. Leg das Handy direkt neben den Hörer (wenige Zentimeter) und tippe auf „Jetzt anlernen“.\n\n"
                        + "Die App merkt sich sein Funkmuster und sucht danach nach dem verlorenen Hörer, der genauso sendet.")
                .setPositiveButton("Jetzt anlernen", (x, y) -> runLearning())
                .setNegativeButton("Abbrechen", null).show();
    }

    private void runLearning() {
        scanner.addListener(this);
        if (!scanner.acquire()) {
            scanner.release();
            status.setText(scanner.error != null ? "⚠ " + scanner.error : "Bluetooth konnte nicht gestartet werden.");
            return;
        }
        scanner.setClassic(false);
        learnSeen.clear();
        learning = true;
        AlertDialog wait = new AlertDialog.Builder(this).setTitle("Lerne …")
                .setMessage("Handy ruhig neben dem Hörer lassen (5 Sekunden).").setCancelable(false).show();
        ui.postDelayed(() -> {
            learning = false;
            wait.dismiss();
            scanner.release();
            if (!searching) scanner.removeListener(this);
            finishLearning();
        }, 5000);
    }

    private void finishLearning() {
        String best = null;
        double bestRssi = -200;
        for (Map.Entry<String, double[]> e : learnSeen.entrySet()) {
            if (e.getValue()[0] > bestRssi) { bestRssi = e.getValue()[0]; best = e.getKey(); }
        }
        if (best == null || bestRssi < -70) {
            new AlertDialog.Builder(this)
                    .setTitle("Kein Hörer erkannt")
                    .setMessage((learnSeen.isEmpty()
                            ? "Es kam überhaupt kein Bluetooth-Signal an. Prüfe, ob Bluetooth an ist und die App die Berechtigung „Geräte in der Nähe“ hat (App-Info → Berechtigungen)."
                            : "Es kamen " + learnSeen.size() + " Signale an, aber keins direkt neben dem Handy.")
                            + "\n\nDein Hörer sendet vermutlich gerade nicht: Er ist noch mit dem Handy verbunden oder im Case. Trenne ihn in den Bluetooth-Einstellungen und versuch es noch mal.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] info = learnInfo.get(best);
        final String addr = best, nm = info != null ? info[0] : null, sig = info != null ? info[1] : "";
        new AlertDialog.Builder(this)
                .setTitle("Hörer erkannt ✅")
                .setMessage((nm != null ? nm : "Gerät ohne Namen") + "\nSignal: " + Math.round(bestRssi) + " dBm\n\n"
                        + "Leg diesen Hörer jetzt zurück ins Case und schließ den Deckel. Danach sucht die App nach dem verlorenen Hörer mit dem gleichen Funkmuster.")
                .setPositiveButton("Suche starten", (x, y) -> {
                    Intent i = new Intent(this, TrackActivity.class);
                    i.putExtra("address", "");
                    i.putExtra("ignore", addr);
                    i.putExtra("name", nm);
                    i.putExtra("sig", sig);
                    i.putExtra("learned", true);
                    stopScan();
                    startActivity(i);
                })
                .setNegativeButton("Abbrechen", null).show();
    }

    // ---------- Berechtigungen ----------

    private String[] neededPermissions() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String p : neededPermissions())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private boolean ensureReady() {
        if (scanner.adapter() == null) {
            status.setText("Dieses Handy hat kein Bluetooth.");
            return false;
        }
        if (!hasPermissions()) {
            requestPermissions(neededPermissions(), 1);
            return false;
        }
        if (!scanner.adapter().isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2);
            return false;
        }
        if (Build.VERSION.SDK_INT < 31) {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null && !lm.isLocationEnabled()) {
                new AlertDialog.Builder(this)
                        .setTitle("Standort einschalten")
                        .setMessage("Auf deiner Android-Version funktioniert die Bluetooth-Suche nur mit eingeschaltetem Standort.")
                        .setPositiveButton("Einstellungen", (x, y) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .setNegativeButton("Abbrechen", null).show();
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (hasPermissions()) toggleScan();
        else status.setText("Ohne Bluetooth-Berechtigung kann ich nicht suchen.");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 2 && scanner.adapter() != null && scanner.adapter().isEnabled()) toggleScan();
    }

    @Override
    protected void onPause() { super.onPause(); stopScan(); }

    // ---------- Liste ----------

    private class DevAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View conv, ViewGroup parent) {
            LinearLayout row;
            TextView name, info, bars;
            if (conv == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(14), dp(12), dp(14), dp(12));
                GradientDrawable g = new GradientDrawable();
                g.setColor(CARD);
                g.setCornerRadius(dp(12));
                row.setBackground(g);
                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                name = new TextView(MainActivity.this);
                name.setTextColor(Color.WHITE);
                name.setTextSize(17);
                name.setTypeface(Typeface.DEFAULT_BOLD);
                info = new TextView(MainActivity.this);
                info.setTextColor(MUTED);
                info.setTextSize(12);
                texts.addView(name);
                texts.addView(info);
                row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                bars = new TextView(MainActivity.this);
                bars.setTextSize(16);
                bars.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                row.addView(bars);
                row.setTag(new TextView[]{name, info, bars});
            } else {
                row = (LinearLayout) conv;
            }
            TextView[] t = (TextView[]) row.getTag();
            Dev d = shown.get(i);
            long age = System.currentTimeMillis() - d.lastSeen;
            boolean live = d.lastSeen > 0 && age < 15000;
            String icon = d.audio ? "🎧 " : "📶 ";
            t[0].setText(icon + (d.name != null && !d.name.isEmpty() ? d.name : "Unbekanntes Gerät"));
            String inf = (d.bonded ? "Gekoppelt · " : "") + d.address;
            if (d.lastSeen == 0) inf += " · noch nicht gefunden";
            else if (!live) inf += " · zuletzt vor " + (age / 1000) + " s";
            else inf += " · " + Math.round(d.smooth) + " dBm";
            t[1].setText(inf);
            int level = live ? Signal.percent(d.smooth) : 0;
            int n = (level + 19) / 20;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 5; k++) sb.append(k < n ? '▮' : '▯');
            t[2].setText(sb);
            t[2].setTextColor(live ? Signal.color(level) : MUTED);
            return row;
        }
    }
}
