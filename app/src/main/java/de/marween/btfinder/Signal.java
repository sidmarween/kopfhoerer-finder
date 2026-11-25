package de.marween.btfinder;

/** Rechnet Signalstärke (dBm) in „heiß / kalt“ um. */
final class Signal {
    private Signal() { }

    /** -95 dBm (sehr weit) … -45 dBm (direkt daneben) → 0 … 100 */
    static int percent(double rssi) {
        double p = (rssi + 95) / 50.0 * 100;
        return (int) Math.max(0, Math.min(100, Math.round(p)));
    }

    static String label(int p) {
        if (p >= 85) return "🔥 Glühend heiß!";
        if (p >= 65) return "Heiß";
        if (p >= 45) return "Warm";
        if (p >= 25) return "Kühl";
        return "Kalt";
    }

    /** Blau (kalt) → Gelb → Rot (heiß) */
    static int color(int p) {
        float t = p / 100f;
        int r, g, b;
        if (t < 0.5f) {
            float k = t / 0.5f;
            r = (int) (40 + k * (255 - 40));
            g = (int) (120 + k * (200 - 120));
            b = (int) (255 - k * 255);
        } else {
            float k = (t - 0.5f) / 0.5f;
            r = 255;
            g = (int) (200 - k * 160);
            b = (int) (k * 40);
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Sehr grobe Entfernungsschätzung in Metern. */
    static double meters(double rssi) {
        return Math.pow(10, (-59 - rssi) / (10 * 2.5));
    }
}
