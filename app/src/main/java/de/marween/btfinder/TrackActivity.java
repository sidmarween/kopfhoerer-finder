package de.marween.btfinder;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;

@SuppressLint("MissingPermission")
public class TrackActivity extends Activity implements BtScanner.Listener {

    private String address, name;
    private BtScanner scanner;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private double smooth = Double.NaN;
    private long lastSeen;
    private int best;
    private final ArrayDeque<double[]> history = new ArrayDeque<>();

    private Radar radar;
    private TextView label, detail, trend, bestTv, audioState;
    private CheckBox soundBox, vibBox;
    private ToneGenerator tone;
    private Vibrator vibrator;
    private AudioTrack alarm;

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        address = getIntent().getStringExtra("address");
        name = getIntent().getStringExtra("name");
        scanner = new BtScanner(this, this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (RuntimeException ignored) { }
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.BG);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(16);
        root.setPadding(p, p, p, p);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(p, p + ins.getSystemWindowInsetTop(), p, p + ins.getSystemWindowInsetBottom());
            return ins;
        });

        TextView title = new TextView(this);
        title.setText("🎧 " + (name != null && !name.isEmpty() ? name : address));
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        radar = new Radar(this);
        root.addView(radar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        label = text(30, Color.WHITE, true);
        trend = text(18, Color.WHITE, false);
        detail = text(14, MainActivity.MUTED, false);
        bestTv = text(13, MainActivity.MUTED, false);
        root.addView(label);
        root.addView(trend);
        root.addView(detail);
        root.addView(bestTv);

        LinearLayout opts = new LinearLayout(this);
        opts.setGravity(Gravity.CENTER);
        soundBox = new CheckBox(this);
        soundBox.setText("Piepton");
        soundBox.setTextColor(Color.WHITE);
        soundBox.setChecked(true);
        vibBox = new CheckBox(this);
        vibBox.setText("Vibration");
        vibBox.setTextColor(Color.WHITE);
        vibBox.setChecked(true);
        opts.addView(soundBox);
        opts.addView(vibBox);
        root.addView(opts);

        audioState = text(13, MainActivity.MUTED, false);
        root.addView(audioState);

        Button ring = new Button(this);
        ring.setText("🔊 Ton auf dem Kopfhörer abspielen");
        ring.setAllCaps(false);
        ring.setTextColor(Color.WHITE);
        ring.setTextSize(16);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF7B2FF7);
        g.setCornerRadius(dp(14));
        ring.setBackground(g);
        ring.setOnClickListener(v -> ringHeadphones());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        lp.topMargin = dp(8);
        root.addView(ring, lp);

        TextView tip = text(12, MainActivity.MUTED, false);
        tip.setText("Tipp: Langsam drehen und gehen. Das Signal schwankt – warte nach jedem Schritt 2–3 Sekunden. Wände, Sofa-Kissen und Körper dämpfen stark.");
        tip.setPadding(0, dp(10), 0, 0);
        root.addView(tip);

        setContentView(root);
        update();
    }

    private TextView text(int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        scanner.start();
        ui.post(loop);
        ui.post(beeper);
    }

    @Override
    protected void onPause() {
        super.onPause();
        scanner.stop();
        ui.removeCallbacks(loop);
        ui.removeCallbacks(beeper);
        stopAlarm();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tone != null) tone.release();
    }

    @Override
    public void onSeen(String addr, String n, int rssi, boolean audio, boolean bonded) {
        boolean match = addr.equalsIgnoreCase(address)
                || (name != null && !name.isEmpty() && name.equals(n));
        if (!match) return;
        ui.post(() -> {
            smooth = Double.isNaN(smooth) ? rssi : smooth * 0.7 + rssi * 0.3;
            lastSeen = System.currentTimeMillis();
            history.addLast(new double[]{lastSeen, smooth});
            while (!history.isEmpty() && lastSeen - history.peekFirst()[0] > 6000) history.removeFirst();
            best = Math.max(best, Signal.percent(smooth));
        });
    }

    private boolean live() { return lastSeen > 0 && System.currentTimeMillis() - lastSeen < 6000; }

    private int percent() { return live() ? Signal.percent(smooth) : 0; }

    private final Runnable loop = new Runnable() {
        @Override public void run() { update(); ui.postDelayed(this, 300); }
    };

    private void update() {
        long age = System.currentTimeMillis() - lastSeen;
        if (lastSeen == 0) {
            label.setText("Suche …");
            trend.setText("");
            detail.setText("Noch kein Signal. Kopfhörer muss eingeschaltet sein (aus dem Case nehmen).");
            radar.set(0, false);
        } else if (!live()) {
            label.setText("Signal verloren");
            trend.setText("Geh zurück, wo es zuletzt stärker war");
            detail.setText("Zuletzt gesehen vor " + (age / 1000) + " s");
            radar.set(0, false);
        } else {
            int pc = percent();
            label.setText(Signal.label(pc));
            label.setTextColor(Signal.color(pc));
            double m = Signal.meters(smooth);
            detail.setText(Math.round(smooth) + " dBm · ca. " + (m < 1 ? "< 1 m" : (m < 10 ? String.format(java.util.Locale.GERMANY, "%.1f m", m) : Math.round(m) + " m")) + " (grobe Schätzung)");
            trend.setText(trendText());
            radar.set(pc, true);
        }
        bestTv.setText(best > 0 ? "Bisher bestes Signal: " + best + " %" : "");
        audioState.setText(btAudioConnected()
                ? "✅ Ein Bluetooth-Kopfhörer ist gerade mit dem Handy verbunden"
                : "Kopfhörer ist nicht als Audiogerät verbunden");
    }

    private String trendText() {
        if (history.size() < 4) return "";
        double old = history.peekFirst()[1], now = history.peekLast()[1];
        double diff = now - old;
        if (diff > 3) return "⬆ Du kommst näher";
        if (diff < -3) return "⬇ Du entfernst dich";
        return "↔ gleich geblieben";
    }

    // ---------- Piepen: schneller, je näher ----------

    private final Runnable beeper = new Runnable() {
        @Override public void run() {
            long delay = 1200;
            if (live()) {
                int pc = percent();
                delay = 1500 - pc * 13L;           // 1,5 s (kalt) … 0,2 s (heiß)
                if (soundBox.isChecked() && tone != null && alarm == null)
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80);
                if (vibBox.isChecked() && vibrator != null && pc >= 45)
                    vibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
            }
            ui.postDelayed(this, Math.max(180, delay));
        }
    };

    // ---------- Ton auf den Kopfhörer schicken ----------

    private boolean btAudioConnected() {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (am == null) return false;
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int t = d.getType();
            if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return true;
            if (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER)) return true;
        }
        return false;
    }

    private void ringHeadphones() {
        if (alarm != null) { stopAlarm(); return; }
        if (!btAudioConnected()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Kopfhörer nicht verbunden")
                    .setMessage("Damit der Ton aus dem Kopfhörer kommt, muss er mit dem Handy verbunden sein. Öffne die Bluetooth-Einstellungen und tippe deinen Kopfhörer an – sobald er verbunden ist, komm zurück und tippe nochmal auf den Knopf.\n\nVorsicht: Der Ton ist laut. Nicht abspielen, während jemand den Kopfhörer trägt.")
                    .setPositiveButton("Bluetooth-Einstellungen", (x, y) -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                    .setNegativeButton("Abbrechen", null).show();
            return;
        }
        int rate = 44100, secs = 15;
        short[] buf = new short[rate * secs];
        for (int i = 0; i < buf.length; i++) {
            double t = (double) i / rate;
            double phase = t % 0.5;                       // 0,25 s Ton, 0,25 s Pause
            if (phase > 0.25) continue;
            double f = ((int) (t / 0.5)) % 2 == 0 ? 2200 : 2900;
            buf[i] = (short) (Math.sin(2 * Math.PI * f * t) * 30000);
        }
        alarm = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        alarm.write(buf, 0, buf.length);
        alarm.play();
        ui.postDelayed(this::stopAlarm, secs * 1000L);
        android.widget.Toast.makeText(this, "Ton läuft 15 s – nochmal tippen zum Stoppen", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void stopAlarm() {
        if (alarm == null) return;
        try { alarm.stop(); } catch (IllegalStateException ignored) { }
        alarm.release();
        alarm = null;
    }

    // ---------- Große Anzeige ----------

    static class Radar extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
                txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pc;
        private boolean live;
        private float pulse;

        Radar(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            txt.setColor(Color.WHITE);
            txt.setTextAlign(Paint.Align.CENTER);
            txt.setTypeface(Typeface.DEFAULT_BOLD);
        }

        void set(int pc, boolean live) { this.pc = pc; this.live = live; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float max = Math.min(cx, cy) * 0.92f;
            int col = live ? Signal.color(pc) : 0xFF44445A;
            ring.setStrokeWidth(max * 0.02f);
            ring.setColor(0x33FFFFFF);
            for (int i = 1; i <= 4; i++) c.drawCircle(cx, cy, max * i / 4f, ring);
            float r = max * (0.25f + 0.75f * pc / 100f);
            if (live) {
                pulse = (pulse + 0.04f + pc / 1500f) % 1f;
                ring.setColor((col & 0x00FFFFFF) | ((int) ((1 - pulse) * 160) << 24));
                c.drawCircle(cx, cy, r + max * 0.3f * pulse, ring);
            }
            fill.setColor(col);
            c.drawCircle(cx, cy, r, fill);
            txt.setTextSize(max * 0.32f);
            c.drawText(live ? pc + "%" : "–", cx, cy + max * 0.11f, txt);
            if (live) postInvalidateOnAnimation();
        }
    }
}
