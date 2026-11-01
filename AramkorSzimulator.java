import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Áramkör-szimulátor: elektronikai áramkörök építése és szimulációja.
 *
 * A megoldó módosított csomóponti analízist (MNA) használ időléptetéssel:
 * a kondenzátorok és tekercsek trapéz- vagy implicit Euler-módszerrel, a diódák
 * Newton–Raphson-iterációval (Shockley-modell, Zener-letörés) számolódnak.
 */
public class AramkorSzimulator extends JFrame {

    static final String PROGRAM = "Áramkör-szimulátor";
    static final int RACS = 16;

    // ================================================================ formázás és értelmezés

    private static final String[] PREFIXUMOK = {"f", "p", "n", "µ", "m", "", "k", "M", "G", "T"};
    /** Magyar számformátum (tizedesvessző), a gép területi beállításától függetlenül. */
    static final Locale HU = new Locale("hu", "HU");

    /** A tizedesjel nyelvenként: angolban pont, a többi (de/fr/es/pl) nálunk is vessző-konvenciójú. */
    static Locale szamLocale() { return I18n.nyelv == Lang.EN ? Locale.US : HU; }

    /** Érték SI-prefixummal, 3 értékes jeggyel (pl. 0,0123 A → „12,3 mA”). */
    static String si(double v, String egyseg) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "–";
        double a = Math.abs(v);
        if (a < 1e-15) return "0 " + egyseg;
        int e = (int) Math.floor(Math.log10(a) / 3 + 1e-9);
        e = Math.max(-5, Math.min(4, e));
        double m = v / Math.pow(1000, e);
        if (Math.abs(m) >= 999.5 && e < 4) { e++; m /= 1000; }
        Locale loc = szamLocale();
        String s = Math.abs(m) >= 99.95 ? String.format(loc, "%.0f", m)
                : Math.abs(m) >= 9.995 ? String.format(loc, "%.1f", m) : String.format(loc, "%.2f", m);
        return s + " " + PREFIXUMOK[e + 5] + egyseg;
    }

    /** Mint az si(), de a felesleges nullák nélkül (paraméterfeliratokhoz: „4,7 kΩ”). */
    static String siRovid(double v, String egyseg) {
        String s = si(v, egyseg);
        return s.replaceAll("([,.]\\d*?)0+ ", "$1 ").replaceAll("[,.] ", " ");
    }

    private static final Pattern SZAM = Pattern.compile("^([-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)([pnuµmkKMG]?)$");

    /** Értelmezi a „4,7k”, „10u”, „100 nF”, „2.2M”, „1e-3” alakú értékeket. */
    static double ertelmez(String s) {
        String t = s.trim().replace(',', '.').replace(" ", "");
        t = t.replaceAll("(?i)(hz|ohm)$", "").replaceAll("(Ω|V|A|F|H|W|s|°|%)$", "");
        Matcher m = SZAM.matcher(t);
        if (!m.matches()) throw new NumberFormatException(s);
        double v = Double.parseDouble(m.group(1));
        switch (m.group(2)) {
            case "p": return v * 1e-12;
            case "n": return v * 1e-9;
            case "u": case "µ": return v * 1e-6;
            case "m": return v * 1e-3;
            case "k": case "K": return v * 1e3;
            case "M": return v * 1e6;
            case "G": return v * 1e9;
            default: return v;
        }
    }

    // ================================================================ paraméterek

    /** Egy alkatrész szerkeszthető paramétere (szám vagy választás). */
    static class Param {
        final String kulcs, nev, egyseg;
        final String[] valasztek;
        final DoubleSupplier olvas;
        final DoubleConsumer ir;
        final double min, max;

        private Param(String kulcs, String nev, String egyseg, String[] valasztek,
                      DoubleSupplier olvas, DoubleConsumer ir, double min, double max) {
            this.kulcs = kulcs;
            this.nev = nev;
            this.egyseg = egyseg;
            this.valasztek = valasztek;
            this.olvas = olvas;
            this.ir = ir;
            this.min = min;
            this.max = max;
        }

        static Param szam(String kulcs, String nev, String egyseg, DoubleSupplier olvas, DoubleConsumer ir, double min, double max) {
            return new Param(kulcs, nev, egyseg, null, olvas, ir, min, max);
        }

        static Param valaszto(String kulcs, String nev, String[] opciok, IntSupplier olvas, IntConsumer ir) {
            return new Param(kulcs, nev, "", opciok, olvas::getAsInt, v -> ir.accept((int) Math.round(v)), 0, opciok.length - 1);
        }
    }

    // ================================================================ rajzolási beállítások

    static final Color TEST = new Color(40, 40, 40);
    static final Color VEZETEK = new Color(70, 70, 70);
    static final Color SEMLEGES = new Color(140, 140, 140);
    static final Color POZITIV = new Color(0, 175, 0);
    static final Color NEGATIV = new Color(225, 0, 0);
    static final Stroke VASTAG = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    /** Rajzolási beállítások és a feszültség → szín leképezés. */
    static class Rajz {
        boolean szimulalt;
        boolean feszSzinezes = true;
        double maxFesz = 1;

        Color szin(double v) {
            if (!szimulalt || !feszSzinezes || Double.isNaN(v)) return VEZETEK;
            double t = Math.max(-1, Math.min(1, v / maxFesz));
            return t >= 0 ? kever(SEMLEGES, POZITIV, t) : kever(SEMLEGES, NEGATIV, -t);
        }

        static Color kever(Color a, Color b, double t) {
            return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                    (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
        }
    }

    // ================================================================ alkatrészek

    /** Minden alkatrész közös őse: két kivezetés (a, b) a rácson. Az áram iránya: A → B az alkatrészen át. */
    abstract static class Elem {
        Point a, b;
        String azon = "";
        int na, nb, ia, ib;
        int fi = -1;
        double ua, ub, aram;
        double pottyPoz;
        boolean ervenyes;
        private double szog;

        Elem(Point a, Point b) {
            this.a = new Point(a);
            this.b = new Point(b);
        }

        abstract String kod();
        abstract String nev();
        abstract String elotag();
        abstract void rajzLokal(Graphics2D g, double hossz, Rajz r);

        List<Param> parameterek() { return new ArrayList<>(); }
        List<Point> kivezetesek() { return Arrays.asList(a, b); }
        boolean forras() { return false; }
        boolean nemlinearis() { return false; }
        void alaphelyzet() { ervenyes = false; aram = 0; ua = ub = 0; }
        void stamp(Megoldo m, double dt, double t, boolean trapez) { }
        boolean iteracio(double[] x, Megoldo m) { return true; }
        double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) { return 0; }
        void lepesUtan() { }
        double u() { return ua - ub; }
        String cimke() { return ""; }
        String figyelmeztetes() { return null; }

        /** Egy mérési sor "Label\tÉrték" alakban; a tabot a panel igazítja oszloppá az aktuális nyelven. */
        static String sor(String label, String ertek) { return label + ":\t" + ertek; }

        List<String> meresek() {
            List<String> l = new ArrayList<>();
            l.add(sor(I18n.t("m.voltage"), si(u(), "V")));
            l.add(sor(I18n.t("m.current"), si(aram, "A")));
            l.add(sor(I18n.t("m.power"), si(u() * aram, "W")));
            return l;
        }

        /** A csomópontok feszültségéből és a megoldásból kiszámolja az eredményeket. */
        void eredmeny(double[] x, Megoldo m, double dt, boolean trapez) {
            ua = m.fesz(x, na);
            ub = m.fesz(x, nb);
            aram = aramSzamol(x, m, dt, trapez);
            ervenyes = true;
        }

        void rajzol(Graphics2D g, Rajz r, boolean kijelolt, boolean hover, boolean hibas) {
            double ax = a.x * RACS, ay = a.y * RACS, bx = b.x * RACS, by = b.y * RACS;
            double hossz = Math.hypot(bx - ax, by - ay);
            if (kijelolt || hover || hibas) {
                g.setColor(hibas ? new Color(255, 0, 0, 110) : kijelolt ? new Color(60, 140, 255, 100) : new Color(60, 140, 255, 45));
                g.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(ax, ay, bx, by));
            }
            szog = Math.atan2(by - ay, bx - ax);
            Graphics2D l = (Graphics2D) g.create();
            l.translate(ax, ay);
            l.rotate(szog);
            l.setStroke(VASTAG);
            rajzLokal(l, hossz, r);
            l.dispose();
        }

        /** A két kivezetés vonala, feszültség szerint színezve; a test (2·fel hosszú) középen marad ki. */
        void kivezetesek(Graphics2D g, double hossz, double fel, Rajz r) {
            double c = hossz / 2, t = Math.min(fel, c);
            g.setStroke(VASTAG);
            g.setColor(r.szin(ua));
            g.draw(new Line2D.Double(0, 0, c - t, 0));
            g.setColor(r.szin(ub));
            g.draw(new Line2D.Double(c + t, 0, hossz, 0));
        }

        /** Mindig vízszintesen olvasható felirat a helyi koordinátarendszerben. */
        void felirat(Graphics2D g, String s, double x, double y, Font f) {
            AffineTransform eredeti = g.getTransform();
            g.translate(x, y);
            g.rotate(-szog);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(s, -fm.stringWidth(s) / 2f, fm.getAscent() / 2f - 1);
            g.setTransform(eredeti);
        }

        static void korLap(Graphics2D g, double cx, double r, Color kitoltes) {
            Ellipse2D e = new Ellipse2D.Double(cx - r, -r, 2 * r, 2 * r);
            g.setColor(kitoltes);
            g.fill(e);
            g.setColor(TEST);
            g.setStroke(VASTAG);
            g.draw(e);
        }
    }

    static class Vezetek extends Elem {
        Vezetek(Point a, Point b) { super(a, b); }
        @Override String kod() { return "W"; }
        @Override String nev() { return I18n.t("part.wire"); }
        @Override String elotag() { return "W"; }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            g.setColor(r.szin(ua));
            g.draw(new Line2D.Double(0, 0, h, 0));
        }
    }

    static class Ellenallas extends Elem {
        double r = 1000, pmax = 0.25;
        Ellenallas(Point a, Point b) { super(a, b); }
        @Override String kod() { return "R"; }
        @Override String nev() { return I18n.t("part.resistor"); }
        @Override String elotag() { return "R"; }

        @Override
        List<Param> parameterek() {
            return Arrays.asList(
                    Param.szam("R", I18n.t("n.resistance"), "Ω", () -> r, v -> r = v, 1e-6, 1e12),
                    Param.szam("P", I18n.t("n.power_rating"), "W", () -> pmax, v -> pmax = v, 1e-6, 1e6));
        }

        @Override void stamp(Megoldo m, double dt, double t, boolean trapez) { m.vezetes(na, nb, 1 / r); }
        @Override double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) { return (ua - ub) / r; }
        @Override String cimke() { return siRovid(r, "Ω"); }

        @Override
        String figyelmeztetes() {
            double p = Math.abs(u() * aram);
            return ervenyes && p > pmax ? I18n.f("warn.resistor", si(p, "W"), siRovid(pmax, "W")) : null;
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz rj) {
            kivezetesek(g, h, 16, rj);
            double c = h / 2;
            Rectangle2D test = new Rectangle2D.Double(c - 16, -6, 32, 12);
            double p = ervenyes ? Math.abs(u() * aram) / pmax : 0;
            g.setColor(p > 1 ? Rajz.kever(new Color(255, 200, 80), new Color(255, 40, 0), Math.min(1, (p - 1) / 3)) : Color.WHITE);
            g.fill(test);
            g.setColor(TEST);
            g.draw(test);
        }
    }

    static class Kondenzator extends Elem {
        double c = 10e-6, u0 = 0, umax = 50;
        private double uElozo, iElozo;
        Kondenzator(Point a, Point b) { super(a, b); alaphelyzet(); }
        @Override String kod() { return "C"; }
        @Override String nev() { return I18n.t("part.capacitor"); }
        @Override String elotag() { return "C"; }

        @Override
        List<Param> parameterek() {
            return Arrays.asList(
                    Param.szam("C", I18n.t("n.capacitance"), "F", () -> c, v -> c = v, 1e-15, 1e3),
                    Param.szam("U0", I18n.t("n.initial_voltage"), "V", () -> u0, v -> u0 = v, -1e6, 1e6),
                    Param.szam("Umax", I18n.t("n.voltage_rating"), "V", () -> umax, v -> umax = v, 1e-3, 1e6));
        }

        @Override void alaphelyzet() { super.alaphelyzet(); uElozo = u0; iElozo = 0; }

        @Override
        void stamp(Megoldo m, double dt, double t, boolean trapez) {
            double g = (trapez ? 2 : 1) * c / dt;
            m.vezetes(na, nb, g);
            m.aram(nb, na, trapez ? g * uElozo + iElozo : g * uElozo);
        }

        @Override
        double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) {
            double g = (trapez ? 2 : 1) * c / dt;
            return trapez ? g * (u() - uElozo) - iElozo : g * (u() - uElozo);
        }

        @Override void lepesUtan() { uElozo = u(); iElozo = aram; }
        @Override String cimke() { return siRovid(c, "F"); }

        @Override
        List<String> meresek() {
            List<String> l = super.meresek();
            l.add(sor(I18n.t("m.charge"), si(c * u(), "C")));
            l.add(sor(I18n.t("m.energy"), si(0.5 * c * u() * u(), "J")));
            return l;
        }

        @Override
        String figyelmeztetes() {
            return ervenyes && Math.abs(u()) > umax ? I18n.f("warn.capacitor", si(Math.abs(u()), "V"), siRovid(umax, "V")) : null;
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 4, r);
            double k = h / 2;
            g.setStroke(new BasicStroke(3f));
            g.setColor(Math.abs(u()) > umax && ervenyes ? NEGATIV : r.szin(ua));
            g.draw(new Line2D.Double(k - 4, -11, k - 4, 11));
            g.setColor(Math.abs(u()) > umax && ervenyes ? NEGATIV : r.szin(ub));
            g.draw(new Line2D.Double(k + 4, -11, k + 4, 11));
        }
    }

    static class Tekercs extends Elem {
        double l = 10e-3, i0 = 0;
        private double uElozo, iElozo;
        Tekercs(Point a, Point b) { super(a, b); alaphelyzet(); }
        @Override String kod() { return "L"; }
        @Override String nev() { return I18n.t("part.inductor_full"); }
        @Override String elotag() { return "L"; }

        @Override
        List<Param> parameterek() {
            return Arrays.asList(
                    Param.szam("L", I18n.t("n.inductance"), "H", () -> l, v -> l = v, 1e-12, 1e6),
                    Param.szam("I0", I18n.t("n.initial_current"), "A", () -> i0, v -> i0 = v, -1e6, 1e6));
        }

        @Override void alaphelyzet() { super.alaphelyzet(); uElozo = 0; iElozo = i0; aram = i0; }

        @Override
        void stamp(Megoldo m, double dt, double t, boolean trapez) {
            double g = dt / ((trapez ? 2 : 1) * l);
            m.vezetes(na, nb, g);
            m.aram(na, nb, trapez ? iElozo + g * uElozo : iElozo);
        }

        @Override
        double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) {
            double g = dt / ((trapez ? 2 : 1) * l);
            return trapez ? g * u() + iElozo + g * uElozo : iElozo + g * u();
        }

        @Override void lepesUtan() { uElozo = u(); iElozo = aram; }
        @Override String cimke() { return siRovid(l, "H"); }

        @Override
        List<String> meresek() {
            List<String> lst = super.meresek();
            lst.add(sor(I18n.t("m.magenergy"), si(0.5 * l * aram * aram, "J")));
            return lst;
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 16, r);
            double c = h / 2;
            g.setColor(TEST);
            for (int k = 0; k < 4; k++) g.draw(new Arc2D.Double(c - 16 + 8 * k, -4, 8, 8, 0, 180, Arc2D.OPEN));
        }
    }

    /** Egyenfeszültségű telep: a B kivezetés a pozitív pólus. */
    static class Telep extends Elem {
        double e = 9, rb = 0;
        Telep(Point a, Point b) { super(a, b); }
        @Override String kod() { return "V"; }
        @Override String nev() { return I18n.t("part.battery"); }
        @Override String elotag() { return "U"; }

        @Override
        List<Param> parameterek() {
            return Arrays.asList(
                    Param.szam("U", I18n.t("n.source_voltage"), "V", () -> e, v -> e = v, -1e6, 1e6),
                    Param.szam("Rb", I18n.t("n.internal_resistance"), "Ω", () -> rb, v -> rb = v, 0, 1e9));
        }

        double pillanatnyi(double t) { return e; }
        @Override boolean forras() { return rb <= 0; }

        @Override
        void stamp(Megoldo m, double dt, double t, boolean trapez) {
            double uf = pillanatnyi(t);
            if (rb <= 0) {
                m.feszForras(fi, nb, na, uf);
            } else {
                m.vezetes(na, nb, 1 / rb);
                m.aram(na, nb, uf / rb);
            }
        }

        @Override
        double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) {
            return rb <= 0 ? -m.forrasAram(x, fi) : pillanatnyi(0) / rb - (ub - ua) / rb;
        }

        @Override String cimke() { return siRovid(e, "V"); }

        @Override
        List<String> meresek() {
            List<String> l = new ArrayList<>();
            l.add(sor(I18n.t("m.terminalvoltage"), si(ub - ua, "V")));
            l.add(sor(I18n.t("m.currentoutward"), si(aram, "A")));
            l.add(sor(I18n.t("m.deliveredpower"), si(-u() * aram, "W")));
            return l;
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 4, r);
            double c = h / 2;
            g.setColor(TEST);
            g.setStroke(new BasicStroke(4f));
            g.draw(new Line2D.Double(c - 4, -6, c - 4, 6));
            g.setStroke(VASTAG);
            g.draw(new Line2D.Double(c + 4, -13, c + 4, 13));
            g.setColor(POZITIV.darker());
            felirat(g, "+", c + 13, -10, new Font(Font.SANS_SERIF, Font.BOLD, 12));
        }
    }

    /** Váltakozó feszültségű forrás; a B kivezetés a pozitív irány. */
    static class AcForras extends Telep {
        static String[] jelalakok() {
            return new String[]{I18n.t("wave.sine"), I18n.t("wave.square"), I18n.t("wave.triangle"), I18n.t("wave.saw")};
        }
        double amplitudo = 10, frekvencia = 50, fazis = 0, eltolas = 0;
        int jelalak = 0;
        private double utolsoT;
        AcForras(Point a, Point b) { super(a, b); }
        @Override String kod() { return "AC"; }
        @Override String nev() { return I18n.t("part.acsource"); }
        @Override String elotag() { return "G"; }

        @Override
        List<Param> parameterek() {
            return Arrays.asList(
                    Param.valaszto("jel", I18n.t("n.waveform"), jelalakok(), () -> jelalak, v -> jelalak = v),
                    Param.szam("A", I18n.t("n.amplitude"), "V", () -> amplitudo, v -> amplitudo = v, 0, 1e6),
                    Param.szam("f", I18n.t("n.frequency"), "Hz", () -> frekvencia, v -> frekvencia = v, 1e-6, 1e9),
                    Param.szam("fi", I18n.t("n.phase"), "°", () -> fazis, v -> fazis = v, -360, 360),
                    Param.szam("U0", I18n.t("n.dc_offset"), "V", () -> eltolas, v -> eltolas = v, -1e6, 1e6),
                    Param.szam("Rb", I18n.t("n.internal_resistance"), "Ω", () -> rb, v -> rb = v, 0, 1e9));
        }

        @Override
        double pillanatnyi(double t) {
            if (t > 0) utolsoT = t;
            double fazisSzog = frekvencia * t + fazis / 360.0;
            double tort = fazisSzog - Math.floor(fazisSzog);
            double jel;
            switch (jelalak) {
                case 1: jel = tort < 0.5 ? 1 : -1; break;
                case 2: jel = tort < 0.5 ? 4 * tort - 1 : 3 - 4 * tort; break;
                case 3: jel = 2 * tort - 1; break;
                default: jel = Math.sin(2 * Math.PI * fazisSzog);
            }
            return eltolas + amplitudo * jel;
        }

        @Override
        double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) {
            return rb <= 0 ? -m.forrasAram(x, fi) : pillanatnyi(utolsoT) / rb - (ub - ua) / rb;
        }

        @Override void alaphelyzet() { super.alaphelyzet(); utolsoT = 0; }
        @Override String cimke() { return siRovid(amplitudo, "V") + " " + siRovid(frekvencia, "Hz"); }

        @Override
        List<String> meresek() {
            List<String> l = super.meresek();
            l.add(0, sor(I18n.t("m.instantvoltage"), si(pillanatnyi(utolsoT), "V")));
            if (jelalak == 0) l.add(sor(I18n.t("m.rms"), si(amplitudo / Math.sqrt(2), "V")));
            return l;
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 14, r);
            double c = h / 2;
            korLap(g, c, 14, Color.WHITE);
            Path2D p = new Path2D.Double();
            for (int i = 0; i <= 20; i++) {
                double xx = c - 9 + 18 * i / 20.0, yy = 0;
                double f = i / 20.0;
                switch (jelalak) {
                    case 1: yy = f < 0.5 ? -5 : 5; break;
                    case 2: yy = f < 0.25 ? -20 * f : f < 0.75 ? -5 + 20 * (f - 0.25) : 5 - 20 * (f - 0.75); break;
                    case 3: yy = 5 - 10 * f; break;
                    default: yy = -5 * Math.sin(2 * Math.PI * f);
                }
                if (i == 0) p.moveTo(xx, yy); else p.lineTo(xx, yy);
            }
            g.draw(p);
            g.setColor(POZITIV.darker());
            felirat(g, "+", c + 20, -12, new Font(Font.SANS_SERIF, Font.BOLD, 12));
        }
    }

    /** Ideális áramforrás: az áram A-ból B felé folyik az alkatrészen át. */
    static class AramForras extends Elem {
        double i = 0.01;
        AramForras(Point a, Point b) { super(a, b); }
        @Override String kod() { return "I"; }
        @Override String nev() { return I18n.t("part.currentsource"); }
        @Override String elotag() { return "I"; }

        @Override
        List<Param> parameterek() {
            return Collections.singletonList(Param.szam("I", I18n.t("n.source_current"), "A", () -> i, v -> i = v, -1e6, 1e6));
        }

        @Override void stamp(Megoldo m, double dt, double t, boolean trapez) { m.aram(na, nb, i); }
        @Override double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) { return i; }
        @Override String cimke() { return siRovid(i, "A"); }

        @Override
        List<String> meresek() {
            List<String> l = super.meresek();
            l.set(2, sor(I18n.t("m.deliveredpower"), si(-u() * aram, "W")));
            return l;
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 14, r);
            double c = h / 2;
            korLap(g, c, 14, Color.WHITE);
            g.draw(new Line2D.Double(c - 8, 0, c + 8, 0));
            Path2D nyil = new Path2D.Double();
            nyil.moveTo(c + 9, 0);
            nyil.lineTo(c + 3, -4);
            nyil.lineTo(c + 3, 4);
            nyil.closePath();
            g.fill(nyil);
        }
    }

    /**
     * Dióda Shockley-modellel; anód = A, katód = B. Uz > 0 esetén Zener-dióda: a letörési ág
     * meredek exponenciális, amely 5 mA záróirányú áramnál éppen Uz feszültséget ad.
     */
    static class Dioda extends Elem {
        static final double VT = 0.025852;
        static final double GMIN = 1e-12;
        static final double IZ_REF = 5e-3;
        static final double VKRIT_Z = VT * Math.log(VT / (Math.sqrt(2) * IZ_REF));
        double is = 1e-9, n = 1.7, uz = 0, imax = 1;
        double vd;

        Dioda(Point a, Point b) { super(a, b); }
        @Override String kod() { return "D"; }
        @Override String nev() { return uz > 0 ? I18n.t("part.zener") : I18n.t("part.diode"); }
        @Override String elotag() { return "D"; }
        @Override boolean nemlinearis() { return true; }

        @Override
        List<Param> parameterek() {
            return Arrays.asList(
                    Param.szam("Is", I18n.t("n.sat_current"), "A", () -> is, v -> is = v, 1e-20, 1),
                    Param.szam("n", I18n.t("n.emission_coeff"), "", () -> n, v -> n = v, 0.5, 10),
                    Param.szam("Uz", I18n.t("n.zener_voltage"), "V", () -> uz, v -> uz = v, 0, 1e4),
                    Param.szam("Imax", I18n.t("n.max_current"), "A", () -> imax, v -> imax = v, 1e-6, 1e4));
        }

        double telitesi() { return is; }
        double nvt() { return n * VT; }
        @Override void alaphelyzet() { super.alaphelyzet(); vd = 0; }

        /** {áram, differenciális vezetés} adott feszültségnél. */
        double[] jelleggorbe(double v) {
            double is0 = telitesi(), nvt = nvt();
            double e = Math.exp(Math.min(v / nvt, 80));
            double i = is0 * (e - 1), g = is0 * e / nvt;
            if (uz > 0) {
                double er = Math.exp(Math.min((-v - uz) / VT, 80));
                i -= IZ_REF * er;
                g += IZ_REF * er / VT;
            }
            return new double[]{i + GMIN * v, g + GMIN};
        }

        @Override
        void stamp(Megoldo m, double dt, double t, boolean trapez) {
            double[] ig = jelleggorbe(vd);
            m.vezetes(na, nb, ig[1]);
            m.aram(na, nb, ig[0] - ig[1] * vd);
        }

        @Override
        boolean iteracio(double[] x, Megoldo m) {
            double vuj = m.fesz(x, na) - m.fesz(x, nb);
            double nvt = nvt(), vkrit = nvt * Math.log(nvt / (Math.sqrt(2) * telitesi()));
            double vl;
            if (uz > 0 && vuj < 0) vl = -korlatoz(-vuj - uz, -vd - uz, VT, VKRIT_Z) - uz;
            else vl = korlatoz(vuj, vd, nvt, vkrit);
            // SPICE-szerű konvergencia: a feszültség VAGY az áram változása elég kicsi
            // (zárt diódáknál a lebegő csomópontok miatt a feszültség kerekítési hibája nagyobb lehet 1 µV-nál)
            double dv = Math.abs(vl - vd);
            double iRegi = jelleggorbe(vd)[0], iUj = jelleggorbe(vl)[0];
            boolean konv = dv < 1e-6 + 1e-4 * Math.max(Math.abs(vl), Math.abs(vd))
                    || Math.abs(iUj - iRegi) < 1e-12 + 1e-4 * Math.max(Math.abs(iUj), Math.abs(iRegi));
            vd = vl;
            return konv;
        }

        /** A SPICE „pnjlim” feszültségkorlátozása a Newton-iteráció stabilitásáért. */
        static double korlatoz(double vuj, double vregi, double nvt, double vkrit) {
            if (vuj > vkrit && Math.abs(vuj - vregi) > 2 * nvt) {
                if (vregi > 0) {
                    double arg = 1 + (vuj - vregi) / nvt;
                    return arg > 0 ? vregi + nvt * Math.log(arg) : vkrit;
                }
                return nvt * Math.log(vuj / nvt);
            }
            return vuj;
        }

        @Override double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) { return jelleggorbe(u())[0]; }
        @Override String cimke() { return uz > 0 ? "Uz=" + siRovid(uz, "V") : ""; }

        @Override
        String figyelmeztetes() {
            return ervenyes && Math.abs(aram) > imax ? I18n.f("warn.diode", si(Math.abs(aram), "A"), siRovid(imax, "A")) : null;
        }

        @Override
        List<String> meresek() {
            List<String> l = super.meresek();
            String allapot = aram > 1e-6 ? I18n.t("m.state_conducting")
                    : uz > 0 && aram < -1e-6 ? I18n.t("m.state_zener") : I18n.t("m.state_blocking");
            l.add(sor(I18n.t("m.state"), allapot));
            return l;
        }

        Color kitoltes() { return Color.WHITE; }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 8, r);
            double c = h / 2;
            Path2D har = new Path2D.Double();
            har.moveTo(c - 8, -8);
            har.lineTo(c - 8, 8);
            har.lineTo(c + 8, 0);
            har.closePath();
            g.setColor(kitoltes());
            g.fill(har);
            g.setColor(figyelmeztetes() != null ? NEGATIV : TEST);
            g.draw(har);
            g.draw(new Line2D.Double(c + 8, -8, c + 8, 8));
            if (uz > 0) {
                g.draw(new Line2D.Double(c + 8, -8, c + 4, -8));
                g.draw(new Line2D.Double(c + 8, 8, c + 12, 8));
            }
        }
    }

    static class Led extends Dioda {
        static String[] szinek() {
            return new String[]{I18n.t("led.red"), I18n.t("led.yellow"), I18n.t("led.green"), I18n.t("led.blue"), I18n.t("led.white")};
        }
        static final double[] NYITO = {1.9, 2.0, 2.2, 3.0, 3.1};
        static final Color[] SZIN = {new Color(255, 30, 30), new Color(255, 210, 0), new Color(30, 220, 40),
                new Color(40, 110, 255), new Color(250, 250, 250)};
        int szin = 0;
        double uf = 1.9;

        Led(Point a, Point b) { super(a, b); n = 2; imax = 0.02; }
        @Override String kod() { return "LED"; }
        @Override String nev() { return I18n.f("part.led_format", szinek()[szin]); }
        @Override String elotag() { return "LED"; }

        @Override
        List<Param> parameterek() {
            return Arrays.asList(
                    Param.valaszto("szin", I18n.t("n.color"), szinek(), () -> szin, v -> { szin = v; uf = NYITO[v]; }),
                    Param.szam("Uf", I18n.t("n.forward_voltage"), "V", () -> uf, v -> uf = v, 0.5, 10),
                    Param.szam("Imax", I18n.t("n.max_current"), "A", () -> imax, v -> imax = v, 1e-6, 10));
        }

        /** A telítési áramot úgy választjuk, hogy 20 mA-nél éppen Uf legyen a feszültség. */
        @Override double telitesi() { return 0.02 / (Math.exp(uf / nvt()) - 1); }
        double fenyero() { return ervenyes ? Math.max(0, Math.min(1, aram / imax)) : 0; }
        @Override String cimke() { return szinek()[szin]; }

        @Override
        List<String> meresek() {
            List<String> l = super.meresek();
            l.add(sor(I18n.t("m.brightness"), String.format("%.0f %%", fenyero() * 100)));
            return l;
        }

        @Override
        Color kitoltes() {
            double f = fenyero();
            return f > 0.01 ? Rajz.kever(new Color(235, 235, 235), SZIN[szin], Math.sqrt(f)) : new Color(235, 235, 235);
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            double f = fenyero(), c = h / 2;
            if (f > 0.01) {
                Color sz = SZIN[szin];
                float[] tav = {0f, 1f};
                Color[] szinek = {new Color(sz.getRed(), sz.getGreen(), sz.getBlue(), (int) (200 * Math.sqrt(f))),
                        new Color(sz.getRed(), sz.getGreen(), sz.getBlue(), 0)};
                g.setPaint(new RadialGradientPaint(new Point2D.Double(c, 0), 30, tav, szinek));
                g.fill(new Ellipse2D.Double(c - 30, -30, 60, 60));
            }
            super.rajzLokal(g, h, r);
            g.setColor(TEST);
            g.setStroke(new BasicStroke(1.5f));
            for (int k = 0; k < 2; k++) {
                double x0 = c - 2 + 6 * k;
                g.draw(new Line2D.Double(x0, -11, x0 + 6, -19));
                g.draw(new Line2D.Double(x0 + 6, -19, x0 + 2, -18));
                g.draw(new Line2D.Double(x0 + 6, -19, x0 + 5, -15));
            }
        }
    }

    static class Kapcsolo extends Elem {
        boolean zart = true;
        Kapcsolo(Point a, Point b) { super(a, b); }
        @Override String kod() { return "S"; }
        @Override String nev() { return I18n.t("part.switch"); }
        @Override String elotag() { return "S"; }

        @Override
        List<Param> parameterek() {
            return Collections.singletonList(Param.valaszto("zart", I18n.t("n.state"),
                    new String[]{I18n.t("switch.open"), I18n.t("switch.closed")}, () -> zart ? 1 : 0, v -> zart = v == 1));
        }

        @Override String cimke() { return zart ? I18n.t("switch.closed") : I18n.t("switch.open"); }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 12, r);
            double c = h / 2;
            g.setColor(TEST);
            g.fill(new Ellipse2D.Double(c - 14.5, -2.5, 5, 5));
            g.fill(new Ellipse2D.Double(c + 9.5, -2.5, 5, 5));
            g.setColor(zart ? r.szin(ua) : TEST);
            if (zart) g.draw(new Line2D.Double(c - 12, 0, c + 12, 0));
            else g.draw(new Line2D.Double(c - 12, 0, c + 10, -12));
        }
    }

    static class Izzo extends Elem {
        double un = 12, pn = 5;
        Izzo(Point a, Point b) { super(a, b); }
        @Override String kod() { return "LAMP"; }
        @Override String nev() { return I18n.t("part.lamp"); }
        @Override String elotag() { return "H"; }

        @Override
        List<Param> parameterek() {
            return Arrays.asList(
                    Param.szam("Un", I18n.t("n.rated_voltage"), "V", () -> un, v -> un = v, 1e-3, 1e5),
                    Param.szam("Pn", I18n.t("n.rated_power"), "W", () -> pn, v -> pn = v, 1e-6, 1e6));
        }

        double ellenallas() { return un * un / pn; }
        double fenyero() { return ervenyes ? Math.min(1.5, Math.abs(u() * aram) / pn) : 0; }
        @Override void stamp(Megoldo m, double dt, double t, boolean trapez) { m.vezetes(na, nb, 1 / ellenallas()); }
        @Override double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) { return (ua - ub) / ellenallas(); }
        @Override String cimke() { return siRovid(un, "V") + " " + siRovid(pn, "W"); }

        @Override
        String figyelmeztetes() {
            return fenyero() > 1.2 ? I18n.f("warn.lamp", si(Math.abs(u()), "V"), siRovid(un, "V")) : null;
        }

        @Override
        List<String> meresek() {
            List<String> l = super.meresek();
            l.add(sor(I18n.t("m.resistance_calc"), si(ellenallas(), "Ω")));
            l.add(sor(I18n.t("m.brightness"), String.format("%.0f %%", Math.min(1, fenyero()) * 100)));
            return l;
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 12, r);
            double c = h / 2, f = Math.min(1, fenyero());
            if (f > 0.01) {
                float[] tav = {0f, 1f};
                Color[] szinek = {new Color(255, 220, 60, (int) (220 * Math.sqrt(f))), new Color(255, 220, 60, 0)};
                g.setPaint(new RadialGradientPaint(new Point2D.Double(c, 0), 34, tav, szinek));
                g.fill(new Ellipse2D.Double(c - 34, -34, 68, 68));
            }
            korLap(g, c, 12, f > 0.01 ? Rajz.kever(Color.WHITE, new Color(255, 230, 80), f) : Color.WHITE);
            double d = 12 / Math.sqrt(2);
            g.draw(new Line2D.Double(c - d, -d, c + d, d));
            g.draw(new Line2D.Double(c - d, d, c + d, -d));
        }
    }

    /** Ideális ampermérő (0 V-os feszültségforrás); az A → B irányú áramot mutatja. */
    static class Amper extends Elem {
        Amper(Point a, Point b) { super(a, b); }
        @Override String kod() { return "AM"; }
        @Override String nev() { return I18n.t("part.ammeter"); }
        @Override String elotag() { return "A"; }
        @Override boolean forras() { return true; }
        @Override void stamp(Megoldo m, double dt, double t, boolean trapez) { m.feszForras(fi, nb, na, 0); }
        @Override double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) { return -m.forrasAram(x, fi); }
        @Override String cimke() { return ervenyes ? si(aram, "A") : "A"; }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            kivezetesek(g, h, 13, r);
            korLap(g, h / 2, 13, new Color(255, 250, 230));
            g.setColor(TEST);
            felirat(g, "A", h / 2, 0, new Font(Font.SANS_SERIF, Font.BOLD, 14));
        }
    }

    /** Voltmérő nagy belső ellenállással; az U(A) − U(B) feszültséget mutatja. */
    static class Volt extends Elem {
        double r = 10e6;
        Volt(Point a, Point b) { super(a, b); }
        @Override String kod() { return "VM"; }
        @Override String nev() { return I18n.t("part.voltmeter"); }
        @Override String elotag() { return "V"; }

        @Override
        List<Param> parameterek() {
            return Collections.singletonList(Param.szam("R", I18n.t("n.internal_resistance"), "Ω", () -> r, v -> r = v, 1, 1e15));
        }

        @Override void stamp(Megoldo m, double dt, double t, boolean trapez) { m.vezetes(na, nb, 1 / r); }
        @Override double aramSzamol(double[] x, Megoldo m, double dt, boolean trapez) { return (ua - ub) / r; }
        @Override String cimke() { return ervenyes ? si(u(), "V") : "V"; }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz rj) {
            kivezetesek(g, h, 13, rj);
            korLap(g, h / 2, 13, new Color(235, 245, 255));
            g.setColor(TEST);
            felirat(g, "V", h / 2, 0, new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g.setColor(POZITIV.darker());
            felirat(g, "+", h / 2 - 18, -10, new Font(Font.SANS_SERIF, Font.BOLD, 11));
        }
    }

    /** Föld (0 V referencia): csak az A kivezetés elektromos, a B a szimbólum iránya. */
    static class Fold extends Elem {
        Fold(Point a, Point b) { super(a, b); }
        @Override String kod() { return "GND"; }
        @Override String nev() { return I18n.t("part.ground_full"); }
        @Override String elotag() { return "GND"; }
        @Override List<Point> kivezetesek() { return Collections.singletonList(a); }

        @Override
        List<String> meresek() {
            return Arrays.asList(I18n.t("m.refpoint"), sor(I18n.t("m.groundcurrent"), si(aram, "A")));
        }

        @Override
        void rajzLokal(Graphics2D g, double h, Rajz r) {
            g.setColor(r.szin(0));
            g.draw(new Line2D.Double(0, 0, h, 0));
            g.setColor(TEST);
            g.draw(new Line2D.Double(h, -10, h, 10));
            g.draw(new Line2D.Double(h + 4, -6, h + 4, 6));
            g.draw(new Line2D.Double(h + 8, -2, h + 8, 2));
        }
    }

    /** Az eszköztár sorrendje: kód, név (az aktuális nyelven, minden hívásnál újraszámolva). */
    static String[][] eszkozok() {
        return new String[][]{
                {"W", I18n.t("part.wire")}, {"R", I18n.t("part.resistor")}, {"C", I18n.t("part.capacitor")},
                {"L", I18n.t("part.inductor_short")}, {"V", I18n.t("part.battery")}, {"AC", I18n.t("part.acsource")},
                {"I", I18n.t("part.currentsource")}, {"D", I18n.t("part.diode_zener_combo")}, {"LED", "LED"},
                {"S", I18n.t("part.switch")}, {"LAMP", I18n.t("part.lamp")}, {"AM", I18n.t("part.ammeter")},
                {"VM", I18n.t("part.voltmeter")}, {"GND", I18n.t("part.ground_short")}
        };
    }

    static Elem letrehoz(String kod, Point a, Point b) {
        switch (kod) {
            case "W": return new Vezetek(a, b);
            case "R": return new Ellenallas(a, b);
            case "C": return new Kondenzator(a, b);
            case "L": return new Tekercs(a, b);
            case "V": return new Telep(a, b);
            case "AC": return new AcForras(a, b);
            case "I": return new AramForras(a, b);
            case "D": return new Dioda(a, b);
            case "LED": return new Led(a, b);
            case "S": return new Kapcsolo(a, b);
            case "LAMP": return new Izzo(a, b);
            case "AM": return new Amper(a, b);
            case "VM": return new Volt(a, b);
            case "GND": return new Fold(a, b);
            default: throw new IllegalArgumentException("Ismeretlen alkatrész: " + kod);
        }
    }

    static boolean drot(Elem e) {
        return e instanceof Vezetek || (e instanceof Kapcsolo && ((Kapcsolo) e).zart);
    }

    // ================================================================ megoldó

    /** MNA mátrix: csomópont-feszültségek (a 0. a föld) és feszültségforrás-áramok. */
    static class Megoldo {
        int csomopontok, forrasok, meret;
        double[][] a = new double[0][0], munka = new double[0][0];
        double[] z = new double[0], y = new double[0], x = new double[0];

        void elokeszit(int csomopontok, int forrasok) {
            this.csomopontok = csomopontok;
            this.forrasok = forrasok;
            meret = csomopontok - 1 + forrasok;
            a = new double[meret][meret];
            munka = new double[meret][meret];
            z = new double[meret];
            y = new double[meret];
            x = new double[meret];
        }

        void torol() {
            for (double[] sor : a) Arrays.fill(sor, 0);
            Arrays.fill(z, 0);
        }

        void vezetes(int n1, int n2, double g) {
            if (n1 > 0) a[n1 - 1][n1 - 1] += g;
            if (n2 > 0) a[n2 - 1][n2 - 1] += g;
            if (n1 > 0 && n2 > 0) {
                a[n1 - 1][n2 - 1] -= g;
                a[n2 - 1][n1 - 1] -= g;
            }
        }

        /** i áram folyik a „honnan” csomópontból az alkatrészen át a „hova” csomópontba. */
        void aram(int honnan, int hova, double i) {
            if (honnan > 0) z[honnan - 1] -= i;
            if (hova > 0) z[hova - 1] += i;
        }

        /** Ideális feszültségforrás: U(plusz) − U(minusz) = e. */
        void feszForras(int k, int plusz, int minusz, double e) {
            int s = csomopontok - 1 + k;
            if (plusz > 0) { a[plusz - 1][s] += 1; a[s][plusz - 1] += 1; }
            if (minusz > 0) { a[minusz - 1][s] -= 1; a[s][minusz - 1] -= 1; }
            z[s] += e;
        }

        double fesz(double[] x, int n) { return n <= 0 ? 0 : x[n - 1]; }
        double forrasAram(double[] x, int k) { return x[csomopontok - 1 + k]; }

        /** Gauss-elimináció részleges főelemkiválasztással. */
        double[] megold() {
            int n = meret;
            for (int i = 0; i < n; i++) System.arraycopy(a[i], 0, munka[i], 0, n);
            System.arraycopy(z, 0, y, 0, n);
            for (int k = 0; k < n; k++) {
                int p = k;
                double max = Math.abs(munka[k][k]);
                for (int i = k + 1; i < n; i++) {
                    double v = Math.abs(munka[i][k]);
                    if (v > max) { max = v; p = i; }
                }
                if (max < 1e-30) throw new ArithmeticException(I18n.t("err.singular_matrix"));
                if (p != k) {
                    double[] t = munka[p]; munka[p] = munka[k]; munka[k] = t;
                    double ty = y[p]; y[p] = y[k]; y[k] = ty;
                }
                double[] mk = munka[k];
                for (int i = k + 1; i < n; i++) {
                    double f = munka[i][k] / mk[k];
                    if (f == 0) continue;
                    double[] mi = munka[i];
                    for (int j = k; j < n; j++) mi[j] -= f * mk[j];
                    y[i] -= f * y[k];
                }
            }
            for (int i = n - 1; i >= 0; i--) {
                double s = y[i];
                for (int j = i + 1; j < n; j++) s -= munka[i][j] * x[j];
                x[i] = s / munka[i][i];
            }
            for (double v : x) if (Double.isNaN(v) || Double.isInfinite(v)) throw new ArithmeticException(I18n.t("err.numeric"));
            return x;
        }
    }

    static class AramkorHiba extends Exception {
        final Elem elem;

        AramkorHiba(String uzenet, Elem elem) {
            super(uzenet);
            this.elem = elem;
        }
    }

    /** Az áramkör időléptetéses szimulációja. */
    static class Szimulator {
        static final double GMIN = 1e-12;
        final List<Elem> elemek;
        final Megoldo m = new Megoldo();
        double dt = 1e-5, ido;
        boolean trapez = true, piszkos = true;
        int csomopontSzam, konvergenciaHibak;
        double[] x;
        private List<Elem> aktiv = new ArrayList<>(), nemlin = new ArrayList<>(), drotok = new ArrayList<>();
        private final Map<Long, Integer> pontIndex = new HashMap<>();
        private int[] pontCsomopont = new int[0];
        private boolean[] foldPont = new boolean[0];

        Szimulator(List<Elem> elemek) { this.elemek = elemek; }

        static long kulcs(Point p) { return ((long) p.x << 32) ^ (p.y & 0xffffffffL); }

        void alaphelyzet() {
            ido = 0;
            x = null;
            konvergenciaHibak = 0;
            for (Elem e : elemek) e.alaphelyzet();
            piszkos = true;
        }

        boolean vanEredmeny() { return x != null; }

        /** Egy rácspont feszültsége a legutóbbi lépés után (NaN, ha nincs ott csomópont). */
        double pontFesz(Point p) {
            Integer i = pontIndex.get(kulcs(p));
            if (i == null || x == null || piszkos) return Double.NaN;
            return m.fesz(x, pontCsomopont[i]);
        }

        private int[] uf;
        private int keres(int i) { while (uf[i] != i) { uf[i] = uf[uf[i]]; i = uf[i]; } return i; }

        /** Csomópontok kiosztása: a vezetékkel összekötött pontok egy csomópontot alkotnak. */
        void felepit() throws AramkorHiba {
            pontIndex.clear();
            for (Elem e : elemek) for (Point p : e.kivezetesek()) pontIndex.putIfAbsent(kulcs(p), pontIndex.size());
            int p = pontIndex.size();
            uf = new int[p + 1];
            for (int i = 0; i <= p; i++) uf[i] = i;
            foldPont = new boolean[p];
            boolean vanFold = false;
            for (Elem e : elemek) {
                e.ia = pontIndex.get(kulcs(e.a));
                e.ib = e instanceof Fold ? e.ia : pontIndex.get(kulcs(e.b));
                if (drot(e)) uf[keres(e.ia)] = keres(e.ib);
                if (e instanceof Fold) { uf[keres(e.ia)] = keres(p); vanFold = true; foldPont[e.ia] = true; }
            }
            Map<Integer, Integer> gyokerCsp = new HashMap<>();
            if (vanFold) gyokerCsp.put(keres(p), 0);
            else if (p > 0) gyokerCsp.put(keres(0), 0);
            int kov = 1;
            pontCsomopont = new int[p];
            for (int i = 0; i < p; i++) {
                int r = keres(i);
                Integer c = gyokerCsp.get(r);
                if (c == null) { c = kov++; gyokerCsp.put(r, c); }
                pontCsomopont[i] = c;
            }
            csomopontSzam = Math.max(1, kov);

            aktiv = new ArrayList<>();
            nemlin = new ArrayList<>();
            drotok = new ArrayList<>();
            int forrasok = 0;
            for (Elem e : elemek) {
                e.na = pontCsomopont[e.ia];
                e.nb = e instanceof Fold ? 0 : pontCsomopont[e.ib];
                e.fi = -1;
                if (drot(e)) { drotok.add(e); continue; }
                if (e instanceof Fold || e instanceof Kapcsolo) continue;
                aktiv.add(e);
                if (e.forras()) e.fi = forrasok++;
                if (e.nemlinearis()) nemlin.add(e);
            }

            // Ideális feszültségforrások hurka vagy rövidzára: a feladat nem oldható meg egyértelműen.
            int[] hurok = new int[csomopontSzam];
            for (int i = 0; i < hurok.length; i++) hurok[i] = i;
            for (Elem e : aktiv) {
                if (!e.forras()) continue;
                int ra = gyoker(hurok, e.na), rb = gyoker(hurok, e.nb);
                if (ra == rb) {
                    throw new AramkorHiba(I18n.f("err.loop", e.azon, e.nev()), e);
                }
                hurok[ra] = rb;
            }
            m.elokeszit(csomopontSzam, forrasok);
            piszkos = false;
        }

        private static int gyoker(int[] t, int i) { while (t[i] != i) i = t[i]; return i; }

        /** Egy időlépés Newton–Raphson-iterációval. */
        void lepes() throws AramkorHiba {
            if (elemek.isEmpty()) return;
            if (piszkos) felepit();
            double t = ido + dt;
            boolean konv = false;
            double[] eredm = null;
            for (int iter = 0; iter < 80; iter++) {
                m.torol();
                for (int i = 0; i < m.csomopontok - 1; i++) m.a[i][i] += GMIN;
                for (Elem e : aktiv) e.stamp(m, dt, t, trapez);
                try {
                    eredm = m.megold();
                } catch (ArithmeticException ex) {
                    throw new AramkorHiba(I18n.f("err.singular", ex.getMessage()), null);
                }
                if (nemlin.isEmpty()) { konv = true; break; }
                boolean mind = true;
                for (Elem e : nemlin) mind &= e.iteracio(eredm, m);
                if (mind) { konv = true; break; }
            }
            if (!konv) konvergenciaHibak++;
            x = eredm;
            ido = t;
            for (Elem e : aktiv) e.eredmeny(x, m, dt, trapez);
            for (Elem e : elemek) {
                if (aktiv.contains(e)) continue;
                e.ua = m.fesz(x, e.na);
                e.ub = e instanceof Fold ? 0 : m.fesz(x, e.nb);
                e.aram = 0;
                e.ervenyes = true;
            }
            drotAramok();
            for (Elem e : aktiv) e.lepesUtan();
        }

        /**
         * A vezetékek árama a csomóponti törvényből: a vezetékgráf feszítőfáján a levelektől
         * a gyökér felé összegezzük a pontokba befolyó áramokat. A gyökér lehetőleg földpont.
         */
        private void drotAramok() {
            int p = pontIndex.size();
            double[] befolyo = new double[p];
            for (Elem e : elemek) {
                if (drot(e) || e instanceof Fold) continue;
                befolyo[e.ia] -= e.aram;
                befolyo[e.ib] += e.aram;
            }
            List<List<int[]>> szomszed = new ArrayList<>();
            for (int i = 0; i < p; i++) szomszed.add(new ArrayList<>());
            for (int k = 0; k < drotok.size(); k++) {
                Elem w = drotok.get(k);
                szomszed.get(w.ia).add(new int[]{k, w.ib});
                szomszed.get(w.ib).add(new int[]{k, w.ia});
            }
            boolean[] latott = new boolean[p];
            int[] szuloDrot = new int[p];
            int[] sorrend = new int[p];
            int db = 0;
            Map<Integer, Elem> foldElem = new HashMap<>();
            for (Elem e : elemek) if (e instanceof Fold) { foldElem.putIfAbsent(e.ia, e); e.aram = 0; }
            List<Integer> gyokerek = new ArrayList<>();
            for (int i = 0; i < p; i++) if (foldPont[i]) gyokerek.add(i);
            for (int i = 0; i < p; i++) if (!foldPont[i]) gyokerek.add(i);
            for (int gy : gyokerek) {
                if (latott[gy]) continue;
                latott[gy] = true;
                szuloDrot[gy] = -1;
                int eleje = db;
                sorrend[db++] = gy;
                for (int q = eleje; q < db; q++) {
                    int u = sorrend[q];
                    for (int[] sz : szomszed.get(u)) {
                        if (latott[sz[1]]) continue;
                        latott[sz[1]] = true;
                        szuloDrot[sz[1]] = sz[0];
                        sorrend[db++] = sz[1];
                    }
                }
                for (int q = db - 1; q > eleje; q--) {
                    int u = sorrend[q];
                    Elem w = drotok.get(szuloDrot[u]);
                    int szulo = w.ia == u ? w.ib : w.ia;
                    w.aram = w.ia == u ? befolyo[u] : -befolyo[u];
                    befolyo[szulo] += befolyo[u];
                }
                Elem f = foldElem.get(gy);
                if (f != null) f.aram = befolyo[gy];
            }
            for (Elem w : drotok) w.ua = w.ub = m.fesz(x, w.na);
        }

        double maxFeszultseg() {
            double max = 0;
            if (x == null) return 1;
            for (int i = 0; i < m.csomopontok - 1; i++) max = Math.max(max, Math.abs(x[i]));
            return max;
        }
    }

    // ================================================================ rajzvászon

    class Vaszon extends JComponent {
        double zoom = 1.4, eltX = 40, eltY = 30;
        String eszkoz;
        Elem kijelolt, hover, hibas;
        Point hoverPont;
        private Point huzasKezdet, elonezetVeg;
        private Elem mozgatott;
        private int mozgatottVeg;
        private Point eredetiA, eredetiB;
        private String visszavonasPillanat;
        private boolean mozdult;
        private Point panKezdet;
        private double panX, panY;

        Vaszon() {
            setFocusable(true);
            setBackground(Color.WHITE);
            setOpaque(true);
            MouseAdapter eger = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { lenyom(e); }
                @Override public void mouseDragged(MouseEvent e) { huz(e); }
                @Override public void mouseReleased(MouseEvent e) { felenged(e); }
                @Override public void mouseMoved(MouseEvent e) { mozog(e); }
                @Override public void mouseClicked(MouseEvent e) { kattint(e); }
                @Override public void mouseWheelMoved(MouseWheelEvent e) { gorget(e); }
                @Override public void mouseExited(MouseEvent e) { hover = null; hoverPont = null; repaint(); }
            };
            addMouseListener(eger);
            addMouseMotionListener(eger);
            addMouseWheelListener(eger);
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_DELETE: case KeyEvent.VK_BACK_SPACE: torolKijelolt(); break;
                        case KeyEvent.VK_ESCAPE: eszkozValasztas(null); break;
                        case KeyEvent.VK_R: if (e.isControlDown()) return; forgat(); break;
                        case KeyEvent.VK_SPACE: inditasLeallitas(); break;
                        case KeyEvent.VK_PERIOD: egyLepes(); break;
                        default: return;
                    }
                    e.consume();
                }
            });
        }

        Point2D vilag(Point p) { return new Point2D.Double((p.x - eltX) / zoom, (p.y - eltY) / zoom); }

        Point racs(Point p) {
            Point2D w = vilag(p);
            return new Point((int) Math.round(w.getX() / RACS), (int) Math.round(w.getY() / RACS));
        }

        Elem talalat(Point p) {
            Point2D w = vilag(p);
            Elem legjobb = null;
            double min = 8;
            for (int i = elemek.size() - 1; i >= 0; i--) {
                Elem e = elemek.get(i);
                double d = Line2D.ptSegDist(e.a.x * RACS, e.a.y * RACS, e.b.x * RACS, e.b.y * RACS, w.getX(), w.getY());
                if (d < min) { min = d; legjobb = e; }
            }
            return legjobb;
        }

        private void lenyom(MouseEvent e) {
            requestFocusInWindow();
            if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) {
                panKezdet = e.getPoint();
                panX = eltX;
                panY = eltY;
                return;
            }
            mozdult = false;
            Point g = racs(e.getPoint());
            if (eszkoz != null) {
                huzasKezdet = g;
                elonezetVeg = g;
                return;
            }
            // végpont húzása a kijelölt elemen
            if (kijelolt != null) {
                Point2D w = vilag(e.getPoint());
                if (w.distance(kijelolt.a.x * RACS, kijelolt.a.y * RACS) < 7) { mozgatasKezd(kijelolt, 1, g); return; }
                if (w.distance(kijelolt.b.x * RACS, kijelolt.b.y * RACS) < 7) { mozgatasKezd(kijelolt, 2, g); return; }
            }
            Elem t = talalat(e.getPoint());
            kijelol(t);
            if (t != null) mozgatasKezd(t, 0, g);
        }

        private void mozgatasKezd(Elem el, int veg, Point g) {
            mozgatott = el;
            mozgatottVeg = veg;
            huzasKezdet = g;
            eredetiA = new Point(el.a);
            eredetiB = new Point(el.b);
            visszavonasPillanat = szerializal();
        }

        private void huz(MouseEvent e) {
            if (panKezdet != null) {
                eltX = panX + e.getX() - panKezdet.x;
                eltY = panY + e.getY() - panKezdet.y;
                repaint();
                return;
            }
            Point g = racs(e.getPoint());
            if (eszkoz != null && huzasKezdet != null) {
                elonezetVeg = g;
                repaint();
                return;
            }
            if (mozgatott != null) {
                int dx = g.x - huzasKezdet.x, dy = g.y - huzasKezdet.y;
                Point ujA = new Point(eredetiA), ujB = new Point(eredetiB);
                if (mozgatottVeg != 2) ujA.translate(dx, dy);
                if (mozgatottVeg != 1) ujB.translate(dx, dy);
                if (ujA.equals(ujB)) return;
                if (!ujA.equals(mozgatott.a) || !ujB.equals(mozgatott.b)) {
                    mozgatott.a = ujA;
                    mozgatott.b = ujB;
                    mozdult = true;
                    aramkorValtozott();
                }
            }
        }

        private void felenged(MouseEvent e) {
            if (panKezdet != null) { panKezdet = null; return; }
            if (eszkoz != null && huzasKezdet != null) {
                Point veg = racs(e.getPoint());
                if (!veg.equals(huzasKezdet)) {
                    visszavonasMentes();
                    Elem uj = letrehoz(eszkoz, huzasKezdet, veg);
                    uj.azon = ujAzonosito(uj);
                    elemek.add(uj);
                    kijelol(uj);
                    aramkorValtozott();
                }
                huzasKezdet = null;
                elonezetVeg = null;
                repaint();
                return;
            }
            if (mozgatott != null && mozdult) {
                visszavonasVerem.push(visszavonasPillanat);
                ujraVerem.clear();
                modositott = true;
            }
            mozgatott = null;
        }

        private void kattint(MouseEvent e) {
            if (eszkoz != null || !SwingUtilities.isLeftMouseButton(e) || mozdult) return;
            Elem t = talalat(e.getPoint());
            if (t instanceof Kapcsolo && e.getClickCount() == 1) {
                visszavonasMentes();
                ((Kapcsolo) t).zart = !((Kapcsolo) t).zart;
                aramkorValtozott();
                tulajdonsagok.frissitParameterek();
            }
        }

        private void mozog(MouseEvent e) {
            Elem h = eszkoz == null ? talalat(e.getPoint()) : null;
            Point p = racs(e.getPoint());
            if (h != hover || !p.equals(hoverPont)) {
                hover = h;
                hoverPont = p;
                setCursor(Cursor.getPredefinedCursor(eszkoz != null ? Cursor.CROSSHAIR_CURSOR
                        : h != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                repaint();
                allapotFrissit();
            }
        }

        private void gorget(MouseWheelEvent e) {
            double regi = zoom;
            zoom = Math.max(0.4, Math.min(5, zoom * (e.getWheelRotation() < 0 ? 1.15 : 1 / 1.15)));
            eltX = e.getX() - (e.getX() - eltX) * zoom / regi;
            eltY = e.getY() - (e.getY() - eltY) * zoom / regi;
            repaint();
        }

        void nagyit(double szorzo) {
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0, regi = zoom;
            zoom = Math.max(0.4, Math.min(5, zoom * szorzo));
            eltX = cx - (cx - eltX) * zoom / regi;
            eltY = cy - (cy - eltY) * zoom / regi;
            repaint();
        }

        /** Az egész áramkört a látható területre igazítja. */
        void illeszt() {
            if (elemek.isEmpty() || getWidth() == 0) return;
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (Elem e : elemek) {
                for (Point p : new Point[]{e.a, e.b}) {
                    minX = Math.min(minX, p.x * RACS); maxX = Math.max(maxX, p.x * RACS);
                    minY = Math.min(minY, p.y * RACS); maxY = Math.max(maxY, p.y * RACS);
                }
            }
            // a feliratoknak is kell hely: a margó rajzegységben értendő, így nagyításkor vele nő
            double margoX = 100, margoY = 55;
            zoom = Math.max(0.4, Math.min(2.5, Math.min(getWidth() / (maxX - minX + 2 * margoX),
                    getHeight() / (maxY - minY + 2 * margoY))));
            eltX = getWidth() / 2.0 - (minX + maxX) / 2 * zoom;
            eltY = getHeight() / 2.0 - (minY + maxY) / 2 * zoom;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, getWidth(), getHeight());

            // rács
            if (zoom * RACS >= 8) {
                g.setColor(new Color(215, 220, 228));
                double lepes = RACS * zoom;
                double x0 = eltX % lepes, y0 = eltY % lepes;
                for (double x = x0; x < getWidth(); x += lepes) {
                    for (double y = y0; y < getHeight(); y += lepes) g.fillRect((int) x, (int) y, 1, 1);
                }
            }

            g.translate(eltX, eltY);
            g.scale(zoom, zoom);
            rajz.szimulalt = sim.vanEredmeny() && !sim.piszkos;
            double cel = Math.max(1, sim.maxFeszultseg());
            rajz.maxFesz = rajz.maxFesz * 0.9 + cel * 0.1;
            if (Math.abs(rajz.maxFesz - cel) > cel) rajz.maxFesz = cel;

            for (Elem e : elemek) e.rajzol(g, rajz, e == kijelolt, e == hover, e == hibas);

            // csatlakozási pontok
            Map<Long, Integer> szam = new HashMap<>();
            for (Elem e : elemek) for (Point p : e.kivezetesek()) szam.merge(Szimulator.kulcs(p), 1, Integer::sum);
            for (Elem e : elemek) {
                for (Point p : e.kivezetesek()) {
                    int db = szam.get(Szimulator.kulcs(p));
                    double px = p.x * RACS, py = p.y * RACS;
                    if (db >= 3) {
                        g.setColor(TEST);
                        g.fill(new Ellipse2D.Double(px - 3.5, py - 3.5, 7, 7));
                    } else if (db == 1 && !(e instanceof Fold)) {
                        g.setColor(new Color(200, 60, 60));
                        g.setStroke(new BasicStroke(1.2f));
                        g.draw(new Ellipse2D.Double(px - 3, py - 3, 6, 6));
                    }
                }
            }

            // áramlás pöttyök
            if (animacio && rajz.szimulalt) {
                for (Elem e : elemek) {
                    if (Math.abs(e.aram) < 1e-9 || e instanceof Fold && Math.abs(e.aram) < 1e-9) continue;
                    double ax = e.a.x * RACS, ay = e.a.y * RACS, bx = e.b.x * RACS, by = e.b.y * RACS;
                    double h = Math.hypot(bx - ax, by - ay);
                    double ux = (bx - ax) / h, uy = (by - ay) / h;
                    g.setColor(new Color(255, 200, 0));
                    double kezd = ((e.pottyPoz % 16) + 16) % 16;
                    for (double s = kezd; s < h; s += 16) {
                        g.fill(new Ellipse2D.Double(ax + ux * s - 2.5, ay + uy * s - 2.5, 5, 5));
                    }
                }
            }

            // feliratok
            Font f1 = new Font(Font.SANS_SERIF, Font.PLAIN, 11), f2 = new Font(Font.SANS_SERIF, Font.BOLD, 11);
            List<Rectangle2D> foglalt = new ArrayList<>();
            for (Elem e : elemek) {
                if (e instanceof Vezetek || e instanceof Fold) continue;
                double ax = e.a.x * RACS, ay = e.a.y * RACS, bx = e.b.x * RACS, by = e.b.y * RACS;
                double h = Math.hypot(bx - ax, by - ay);
                double nx = -(by - ay) / h, ny = (bx - ax) / h;
                double mx = (ax + bx) / 2, my = (ay + by) / 2;
                boolean mero = e instanceof Amper || e instanceof Volt;
                String felso = mero ? e.azon : (e.azon + "  " + e.cimke()).trim();
                String also = null;
                if (mero) also = rajz.szimulalt ? e.cimke() : null;
                else if (aramokMutatasa && rajz.szimulalt && e.ervenyes) also = si(Math.abs(e.aram), "A");
                Font f3 = mero ? f2.deriveFont(13f) : f2;
                Color c3 = mero ? new Color(0, 80, 170) : new Color(150, 90, 0);
                // A két felirat egy csoportban kerül az alkatrész egyik oldalára; azt az oldalt választjuk,
                // ahol nem fed át korábbi felirattal és nem keresztez másik alkatrészt.
                double[] legjobb = null;
                double legjobbPont = Double.MAX_VALUE;
                for (int s : new int[]{-1, 1}) {
                    double[] h2 = elhelyez(g, felso, also, f1, f3, s, nx, ny, mx, my);
                    Rectangle2D doboz = new Rectangle2D.Double(h2[6], h2[7], h2[8], h2[9]);
                    double pont = buntetes(doboz, e, foglalt) - 0.01 * szabadTer(e, mx + s * nx * 34, my + s * ny * 34);
                    if (pont < legjobbPont) { legjobbPont = pont; legjobb = h2; }
                }
                felirat(g, felso, legjobb[0], legjobb[1], f1, TEST, (int) legjobb[2]);
                if (also != null) felirat(g, also, legjobb[3], legjobb[4], f3, c3, (int) legjobb[5]);
                foglalt.add(new Rectangle2D.Double(legjobb[6], legjobb[7], legjobb[8], legjobb[9]));
                if (e.figyelmeztetes() != null && rajz.szimulalt) felirat(g, "⚠", mx + nx * 8 + 14, my + ny * 8 - 14, f2.deriveFont(16f), NEGATIV, 0);
            }

            // kijelölt elem végpontjai
            if (kijelolt != null) {
                g.setColor(new Color(30, 110, 230));
                for (Point p : new Point[]{kijelolt.a, kijelolt.b}) g.fill(new Rectangle2D.Double(p.x * RACS - 4, p.y * RACS - 4, 8, 8));
            }

            // előnézet elhelyezéskor
            if (eszkoz != null && huzasKezdet != null && elonezetVeg != null && !elonezetVeg.equals(huzasKezdet)) {
                Elem el = letrehoz(eszkoz, huzasKezdet, elonezetVeg);
                Composite c = g.getComposite();
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                el.rajzol(g, new Rajz(), false, false, false);
                g.setComposite(c);
            }
            if (eszkoz != null && hoverPont != null) {
                g.setColor(new Color(30, 110, 230));
                g.draw(new Ellipse2D.Double(hoverPont.x * RACS - 4, hoverPont.y * RACS - 4, 8, 8));
            }
            g.dispose();

            if (elemek.isEmpty()) {
                g0.setColor(Color.GRAY);
                g0.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                g0.drawString(I18n.t("canvas.hint1"), 30, 40);
                g0.drawString(I18n.t("canvas.hint2"), 30, 62);
            }
        }

        /**
         * A feliratpár helye az s oldalon: {x1, y1, igazítás1, x2, y2, igazítás2, doboz x, y, szélesség, magasság}.
         * Függőleges alkatrésznél oldalt, egymás alatt, az alkatrésztől kifelé nőve; egyébként a normális mentén.
         */
        private double[] elhelyez(Graphics2D g, String felso, String also, Font f1, Font f3, int s,
                                  double nx, double ny, double mx, double my) {
            double[] r = new double[10];
            if (Math.abs(nx) > 0.6) {
                int ig = (int) Math.signum(s * nx);
                double ox = mx + s * nx * 22;
                r[0] = ox; r[1] = also != null ? my - 8 : my; r[2] = ig;
                r[3] = ox; r[4] = my + 8; r[5] = ig;
            } else {
                r[0] = mx + s * nx * 20; r[1] = my + s * ny * 20; r[2] = 0;
                r[3] = mx + s * nx * 35; r[4] = my + s * ny * 35; r[5] = 0;
            }
            Rectangle2D d = szovegDoboz(g, felso, r[0], r[1], f1, (int) r[2]);
            if (also != null) d = d.createUnion(szovegDoboz(g, also, r[3], r[4], f3, (int) r[5]));
            r[6] = d.getX(); r[7] = d.getY(); r[8] = d.getWidth(); r[9] = d.getHeight();
            return r;
        }

        private Rectangle2D szovegDoboz(Graphics2D g, String s, double x, double y, Font f, int igazitas) {
            FontMetrics fm = g.getFontMetrics(f);
            double w = fm.stringWidth(s == null ? "" : s);
            double bal = igazitas < 0 ? x - w : igazitas > 0 ? x : x - w / 2;
            return new Rectangle2D.Double(bal - 2, y - fm.getAscent() / 2.0 - 1, w + 4, fm.getAscent() + 2);
        }

        /** Átfedés a már elhelyezett feliratokkal + büntetés minden keresztezett másik alkatrészért. */
        private double buntetes(Rectangle2D doboz, Elem sajat, List<Rectangle2D> foglalt) {
            double p = 0;
            for (Rectangle2D f : foglalt) {
                Rectangle2D metszet = doboz.createIntersection(f);
                if (!metszet.isEmpty()) p += metszet.getWidth() * metszet.getHeight();
            }
            for (Elem e : elemek) {
                if (e == sajat) continue;
                if (new Line2D.Double(e.a.x * RACS, e.a.y * RACS, e.b.x * RACS, e.b.y * RACS).intersects(doboz)) p += 300;
            }
            return p;
        }

        /** A ponttól a legközelebbi másik alkatrészig mért távolság (a feliratok elhelyezéséhez). */
        private double szabadTer(Elem sajat, double px, double py) {
            double min = 1e9;
            for (Elem e : elemek) {
                if (e == sajat) continue;
                min = Math.min(min, Line2D.ptSegDist(e.a.x * RACS, e.a.y * RACS, e.b.x * RACS, e.b.y * RACS, px, py));
            }
            return min;
        }

        /** Felirat a (x, y) pontnál; igazítás: −1 = jobbra zárt (a pontnál ér véget), 0 = középre, 1 = balra zárt. */
        private void felirat(Graphics2D g, String s, double x, double y, Font f, Color c, int igazitas) {
            if (s == null || s.isEmpty()) return;
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            float w = fm.stringWidth(s);
            double bal = igazitas < 0 ? x - w : igazitas > 0 ? x : x - w / 2;
            g.setColor(new Color(255, 255, 255, 200));
            g.fill(new Rectangle2D.Double(bal - 2, y - fm.getAscent() / 2.0 - 1, w + 4, fm.getAscent() + 2));
            g.setColor(c);
            g.drawString(s, (float) bal, (float) (y + fm.getAscent() / 2.0 - 2));
        }
    }

    // ================================================================ tulajdonságpanel

    class TulajdonsagPanel extends JPanel {
        private final JLabel cim = new JLabel(" ");
        private final JPanel urlap = new JPanel(new GridBagLayout());
        private final JTextArea meresek = new JTextArea();
        private final JLabel figyelmeztetes = new JLabel(" ");
        private final javax.swing.border.TitledBorder meresekKeret = BorderFactory.createTitledBorder(I18n.t("panel.measured"));
        private final javax.swing.border.TitledBorder paramKeret = BorderFactory.createTitledBorder(I18n.t("panel.params"));
        private Elem elem;

        TulajdonsagPanel() {
            super(new BorderLayout());
            setBorder(new EmptyBorder(8, 8, 8, 8));
            setPreferredSize(new Dimension(330, 400));
            cim.setFont(cim.getFont().deriveFont(Font.BOLD, 15f));
            meresek.setEditable(false);
            meresek.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            meresek.setBackground(new Color(248, 248, 250));
            meresek.setBorder(meresekKeret);
            figyelmeztetes.setForeground(NEGATIV);
            JPanel felso = new JPanel(new BorderLayout(0, 6));
            felso.add(cim, BorderLayout.NORTH);
            JPanel urlapKeret = new JPanel(new BorderLayout());
            urlapKeret.setBorder(paramKeret);
            urlapKeret.add(urlap, BorderLayout.NORTH);
            felso.add(urlapKeret, BorderLayout.CENTER);
            add(felso, BorderLayout.NORTH);
            add(new JScrollPane(meresek), BorderLayout.CENTER);
            add(figyelmeztetes, BorderLayout.SOUTH);
            beallit(null);
        }

        void beallit(Elem e) {
            elem = e;
            frissitParameterek();
            frissitMeresek();
        }

        /** A keretcímeket és a jelenleg látható tartalmat frissíti az aktuális nyelvre. */
        void nyelvValtozott() {
            meresekKeret.setTitle(I18n.t("panel.measured"));
            paramKeret.setTitle(I18n.t("panel.params"));
            frissitParameterek();
            frissitMeresek();
        }

        void frissitParameterek() {
            urlap.removeAll();
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(3, 3, 3, 3);
            c.anchor = GridBagConstraints.WEST;
            if (elem == null) {
                cim.setText(I18n.t("panel.none"));
                c.gridx = 0;
                c.gridy = 0;
                urlap.add(new JLabel(I18n.t("panel.hint")), c);
            } else {
                cim.setText(elem.azon.isEmpty() ? elem.nev() : elem.azon + " – " + elem.nev());
                int sor = 0;
                for (Param p : elem.parameterek()) {
                    c.gridx = 0;
                    c.gridy = sor;
                    c.weightx = 0;
                    c.fill = GridBagConstraints.NONE;
                    urlap.add(new JLabel(p.nev + ":"), c);
                    c.gridx = 1;
                    c.weightx = 1;
                    c.fill = GridBagConstraints.HORIZONTAL;
                    urlap.add(mezo(p), c);
                    sor++;
                }
                if (sor == 0) {
                    c.gridx = 0;
                    c.gridy = 0;
                    urlap.add(new JLabel(I18n.t("panel.noparams")), c);
                }
                if (elem instanceof Kapcsolo) {
                    c.gridx = 0;
                    c.gridy = sor;
                    c.gridwidth = 2;
                    urlap.add(new JLabel(I18n.t("panel.switchtip")), c);
                }
            }
            urlap.revalidate();
            urlap.repaint();
        }

        private JComponent mezo(Param p) {
            if (p.valasztek != null) {
                JComboBox<String> cb = new JComboBox<>(p.valasztek);
                cb.setSelectedIndex((int) Math.round(p.olvas.getAsDouble()));
                cb.addActionListener(e -> {
                    if (cb.getSelectedIndex() == (int) Math.round(p.olvas.getAsDouble())) return;
                    visszavonasMentes();
                    p.ir.accept(cb.getSelectedIndex());
                    parameterValtozott();
                });
                return cb;
            }
            JPanel pnl = new JPanel(new BorderLayout(4, 0));
            JTextField tf = new JTextField(siRovid(p.olvas.getAsDouble(), "").trim(), 9);
            Color alap = tf.getBackground();
            Runnable alkalmaz = () -> {
                try {
                    double v = ertelmez(tf.getText());
                    if (v < p.min || v > p.max) throw new NumberFormatException();
                    tf.setBackground(alap);
                    if (v == p.olvas.getAsDouble()) return;
                    visszavonasMentes();
                    p.ir.accept(v);
                    parameterValtozott();
                    tf.setText(siRovid(v, "").trim());
                } catch (NumberFormatException ex) {
                    tf.setBackground(new Color(255, 210, 210));
                    tf.setToolTipText(I18n.f("field.invalid", si(p.min, p.egyseg), si(p.max, p.egyseg)));
                    Toolkit.getDefaultToolkit().beep();
                }
            };
            tf.addActionListener(e -> alkalmaz.run());
            tf.addFocusListener(new FocusAdapter() {
                @Override public void focusLost(FocusEvent e) { alkalmaz.run(); }
            });
            tf.setToolTipText(I18n.t("field.sitip"));
            pnl.add(tf, BorderLayout.CENTER);
            pnl.add(new JLabel(p.egyseg), BorderLayout.EAST);
            return pnl;
        }

        /** A "Label\tÉrték" sorokat a tényleges (aktuális nyelvű) legszélesebb label szerint igazítja oszloppá. */
        private String igazitSorok(List<String> sorok) {
            int w = 0;
            for (String s : sorok) {
                int t = s.indexOf('\t');
                if (t >= 0) w = Math.max(w, t + 1);
            }
            StringBuilder sb = new StringBuilder();
            for (String s : sorok) {
                int t = s.indexOf('\t');
                if (t < 0) { sb.append(s).append('\n'); continue; }
                sb.append(s, 0, t);
                for (int i = t; i < w + 1; i++) sb.append(' ');
                sb.append(s, t + 1, s.length()).append('\n');
            }
            return sb.toString();
        }

        void frissitMeresek() {
            StringBuilder sb = new StringBuilder();
            String figy = null;
            if (elem != null) {
                if (sim.vanEredmeny() && elem.ervenyes) {
                    sb.append(igazitSorok(elem.meresek()));
                    sb.append('\n').append("U(A) = ").append(si(elem.ua, "V")).append('\n');
                    if (!(elem instanceof Fold)) sb.append("U(B) = ").append(si(elem.ub, "V")).append('\n');
                    figy = elem.figyelmeztetes();
                } else {
                    sb.append(I18n.t("panel.startsim"));
                }
            } else {
                sb.append(I18n.f("panel.partcount", elemek.size())).append('\n');
                if (sim.vanEredmeny()) {
                    double leadott = 0;
                    for (Elem e : elemek) if (e instanceof Telep || e instanceof AramForras) leadott += -e.u() * e.aram;
                    sb.append(I18n.f("panel.nodecount", sim.csomopontSzam)).append('\n');
                    sb.append(I18n.t("panel.simtime")).append(' ').append(si(sim.ido, "s")).append('\n');
                    sb.append(I18n.t("panel.totalpower")).append(' ').append(si(leadott, "W")).append('\n');
                    int db = 0;
                    for (Elem e : elemek) if (e.figyelmeztetes() != null) db++;
                    if (db > 0) figy = I18n.f("panel.overloaded", db);
                }
            }
            if (!sb.toString().equals(meresek.getText())) meresek.setText(sb.toString());
            figyelmeztetes.setText(figy == null ? " " : "<html>⚠ " + figy + "</html>");
        }
    }

    // ================================================================ oszcilloszkóp

    class Oszcilloszkop extends JPanel {
        static final int MERET = 1500;
        final double[] ido = new double[MERET], fesz = new double[MERET], aramok = new double[MERET];
        int db, kezdet;
        double kovetkezo;
        Elem figyelt;
        final double[] ABLAKOK = {1e-4, 1e-3, 5e-3, 10e-3, 20e-3, 50e-3, 0.1, 0.5, 1, 5, 10, 30};
        final JComboBox<String> ablakValaszto = new JComboBox<>();
        double ablak = 50e-3;
        private final JComponent kepernyo;
        private final JLabel cimke = new JLabel();

        Oszcilloszkop() {
            super(new BorderLayout());
            for (double a : ABLAKOK) ablakValaszto.addItem(siRovid(a, "s"));
            ablakValaszto.setSelectedIndex(5);
            ablakValaszto.addActionListener(e -> { ablak = ABLAKOK[ablakValaszto.getSelectedIndex()]; torol(); });
            JPanel fej = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            cimke.setText(I18n.t("osc.title"));
            fej.add(cimke);
            fej.add(ablakValaszto);
            kepernyo = new JComponent() {
                @Override protected void paintComponent(Graphics g) { rajzol((Graphics2D) g, getWidth(), getHeight()); }
            };
            add(fej, BorderLayout.NORTH);
            add(kepernyo, BorderLayout.CENTER);
            setPreferredSize(new Dimension(800, 210));
        }

        void nyelvValtozott() { cimke.setText(I18n.t("osc.title")); kepernyo.repaint(); }

        void beallitAblak(double a) {
            for (int i = 0; i < ABLAKOK.length; i++) if (Math.abs(ABLAKOK[i] - a) < 1e-12) ablakValaszto.setSelectedIndex(i);
        }

        void torol() { db = 0; kezdet = 0; kovetkezo = 0; }

        void rogzit(double t, Elem e) {
            if (e != figyelt) { figyelt = e; torol(); }
            if (e == null || t < kovetkezo) return;
            kovetkezo = t + ablak / MERET;
            int i = (kezdet + db) % MERET;
            if (db == MERET) kezdet = (kezdet + 1) % MERET; else db++;
            ido[i] = t;
            fesz[i] = e.u();
            aramok[i] = e.aram;
        }

        void frissit() { kepernyo.repaint(); }

        private void rajzol(Graphics2D g, int w, int h) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(18, 22, 28));
            g.fillRect(0, 0, w, h);
            int bal = 70, jobb = 70, fent = 8, lent = 20;
            int sz = w - bal - jobb, m = h - fent - lent;
            if (sz < 50 || m < 30) return;
            g.setColor(new Color(50, 58, 70));
            for (int i = 0; i <= 10; i++) g.drawLine(bal + sz * i / 10, fent, bal + sz * i / 10, fent + m);
            for (int i = 0; i <= 8; i++) g.drawLine(bal, fent + m * i / 8, bal + sz, fent + m * i / 8);
            g.setColor(new Color(90, 100, 115));
            g.drawLine(bal, fent + m / 2, bal + sz, fent + m / 2);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            if (figyelt == null || db < 2) {
                g.setColor(Color.LIGHT_GRAY);
                g.drawString(I18n.t(figyelt == null ? "osc.waitselect" : "osc.collecting"), bal + 10, fent + 20);
                return;
            }
            double tVeg = ido[(kezdet + db - 1) % MERET], tKezd = tVeg - ablak;
            double maxU = 1e-9, maxI = 1e-12;
            for (int k = 0; k < db; k++) {
                int i = (kezdet + k) % MERET;
                if (ido[i] < tKezd) continue;
                maxU = Math.max(maxU, Math.abs(fesz[i]));
                maxI = Math.max(maxI, Math.abs(aramok[i]));
            }
            maxU = kerekit(maxU);
            maxI = kerekit(maxI);
            gorbe(g, fesz, maxU, tKezd, bal, fent, sz, m, new Color(80, 230, 90));
            gorbe(g, aramok, maxI, tKezd, bal, fent, sz, m, new Color(255, 170, 40));
            g.setColor(new Color(80, 230, 90));
            g.drawString("+" + siRovid(maxU, "V"), 4, fent + 10);
            g.drawString("−" + siRovid(maxU, "V"), 4, fent + m);
            g.drawString(si(figyelt.u(), "V"), 4, fent + m / 2 + 4);
            g.setColor(new Color(255, 170, 40));
            g.drawString("+" + siRovid(maxI, "A"), bal + sz + 6, fent + 10);
            g.drawString("−" + siRovid(maxI, "A"), bal + sz + 6, fent + m);
            g.drawString(si(figyelt.aram, "A"), bal + sz + 6, fent + m / 2 + 4);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(figyelt.azon + "   " + siRovid(ablak / 10, "s") + I18n.t("osc.perdiv") + "   " + I18n.f("osc.time", si(tVeg, "s")), bal + 6, h - 5);
        }

        private void gorbe(Graphics2D g, double[] adat, double max, double tKezd, int bal, int fent, int sz, int m, Color szin) {
            Path2D p = new Path2D.Double();
            boolean elso = true;
            for (int k = 0; k < db; k++) {
                int i = (kezdet + k) % MERET;
                if (ido[i] < tKezd) continue;
                double x = bal + (ido[i] - tKezd) / ablak * sz;
                double y = fent + m / 2.0 - adat[i] / max * (m / 2.0);
                if (elso) { p.moveTo(x, y); elso = false; } else p.lineTo(x, y);
            }
            g.setColor(szin);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(p);
        }

        /** Felfelé kerekít 1-2-5 lépésekre a szép skálázáshoz. */
        private double kerekit(double v) {
            double e = Math.pow(10, Math.floor(Math.log10(v)));
            double m = v / e;
            return (m <= 1 ? 1 : m <= 2 ? 2 : m <= 5 ? 5 : 10) * e;
        }
    }

    // ================================================================ főablak

    final List<Elem> elemek = new ArrayList<>();
    final Szimulator sim = new Szimulator(elemek);
    final Rajz rajz = new Rajz();
    final Vaszon vaszon = new Vaszon();
    final TulajdonsagPanel tulajdonsagok = new TulajdonsagPanel();
    final Oszcilloszkop oszcilloszkop = new Oszcilloszkop();
    final Deque<String> visszavonasVerem = new ArrayDeque<>(), ujraVerem = new ArrayDeque<>();
    private final JLabel allapot = new JLabel(" ");
    private final JLabel hibaCimke = new JLabel(" ");
    private final JSlider sebessegCsuszka = new JSlider(0, 60, 40);
    private final JLabel sebessegCimke = new JLabel();
    private final JButton inditGomb = new JButton(I18n.t("btn.start"));
    private final JButton resetGomb = new JButton(I18n.t("btn.reset"));
    private final JToggleButton kijGomb = new JToggleButton(I18n.t("tool.select"), true);
    private final JLabel sebessegFelirat = new JLabel();
    private final Map<String, JToggleButton> eszkozGombok = new HashMap<>();
    private final Map<Lang, JToggleButton> nyelvGombok = new EnumMap<>(Lang.class);
    boolean fut, animacio = true, aramokMutatasa = true, modositott;
    private Path fajl;
    private String nyitottNev;
    private double lepesAkku;
    private long utolsoKeret = System.nanoTime();
    private int lepesekMasodpercenkent, lepesSzamlalo;
    private long meresKezdet = System.nanoTime();
    private boolean lassu;
    private JToolBar eszkoztar;

    public AramkorSzimulator() {
        super(I18n.t("app.title"));
        setJMenuBar(menusor());

        eszkoztar = new JToolBar();
        eszkoztar.setFloatable(false);
        ButtonGroup csoport = new ButtonGroup();
        kijGomb.setToolTipText(I18n.t("tool.select.tip"));
        kijGomb.addActionListener(e -> eszkozValasztas(null));
        csoport.add(kijGomb);
        eszkoztar.add(kijGomb);
        eszkozGombok.put(null, kijGomb);
        eszkoztar.addSeparator();
        for (String[] t : eszkozok()) {
            JToggleButton b = new JToggleButton(new EszkozIkon(t[0]));
            b.setToolTipText(I18n.f("tool.tip", t[1]));
            b.addActionListener(e -> eszkozValasztas(t[0]));
            csoport.add(b);
            eszkoztar.add(b);
            eszkozGombok.put(t[0], b);
        }
        eszkoztar.addSeparator();
        inditGomb.addActionListener(e -> inditasLeallitas());
        eszkoztar.add(inditGomb);
        resetGomb.setToolTipText(I18n.t("btn.reset.tip"));
        resetGomb.addActionListener(e -> alaphelyzet());
        eszkoztar.add(resetGomb);
        eszkoztar.addSeparator();
        eszkoztar.add(sebessegFelirat);
        sebessegCsuszka.setMaximumSize(new Dimension(160, 30));
        sebessegCsuszka.setToolTipText(I18n.t("slider.speed.tip"));
        sebessegCsuszka.addChangeListener(e -> sebessegCimke.setText(" " + siRovid(sebesseg(), "s") + " / s "));
        eszkoztar.add(sebessegCsuszka);
        eszkoztar.add(sebessegCimke);
        sebessegFelirat.setText(" " + I18n.t("lbl.speed") + ": ");
        sebessegCimke.setText(" " + siRovid(sebesseg(), "s") + " / s ");
        eszkoztar.addSeparator();
        ButtonGroup nyelvCsoport = new ButtonGroup();
        for (Lang l : Lang.values()) {
            JToggleButton b = new JToggleButton(new ZaszloIkon(l), l == I18n.nyelv);
            b.setToolTipText(l.nev);
            b.addActionListener(e -> { I18n.valt(l); nyelvValtozott(); });
            nyelvCsoport.add(b);
            eszkoztar.add(b);
            nyelvGombok.put(l, b);
        }

        JSplitPane vizszintes = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, vaszon, tulajdonsagok);
        vizszintes.setResizeWeight(1);
        JSplitPane fuggoleges = new JSplitPane(JSplitPane.VERTICAL_SPLIT, vizszintes, oszcilloszkop);
        fuggoleges.setResizeWeight(1);

        JPanel also = new JPanel(new BorderLayout());
        also.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));
        allapot.setBorder(new EmptyBorder(3, 8, 3, 8));
        hibaCimke.setForeground(NEGATIV);
        hibaCimke.setBorder(new EmptyBorder(3, 8, 3, 8));
        also.add(allapot, BorderLayout.WEST);
        also.add(hibaCimke, BorderLayout.CENTER);

        add(eszkoztar, BorderLayout.NORTH);
        add(fuggoleges, BorderLayout.CENTER);
        add(also, BorderLayout.SOUTH);

        // az eszköztár gombjai ne vegyék el a fókuszt a vászontól (különben a Szóköz a gombot nyomná meg)
        for (Component c : eszkoztar.getComponents()) c.setFocusable(false);

        new javax.swing.Timer(30, e -> keret()).start();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { if (mentesKerdes()) System.exit(0); }
        });
        setSize(1400, 880);
        setLocationRelativeTo(null);
    }

    private JMenuBar menusor() {
        JMenuBar mb = new JMenuBar();
        JMenu fajlMenu = new JMenu(I18n.t("menu.file"));
        menupont(fajlMenu, I18n.t("menu.file.new"), "control N", () -> { if (mentesKerdes()) ujAramkor(); });
        menupont(fajlMenu, I18n.t("menu.file.open"), "control O", this::megnyitas);
        menupont(fajlMenu, I18n.t("menu.file.save"), "control S", () -> mentes(false));
        menupont(fajlMenu, I18n.t("menu.file.saveas"), "control shift S", () -> mentes(true));
        fajlMenu.addSeparator();
        JMenu peldak = new JMenu(I18n.t("menu.file.examples"));
        for (String[] p : PELDAK) menupont(peldak, I18n.t(p[0]), null, () -> { if (mentesKerdes()) { peldaBetoltes(p); } });
        fajlMenu.add(peldak);
        fajlMenu.addSeparator();
        menupont(fajlMenu, I18n.t("menu.file.exit"), null, () -> { if (mentesKerdes()) System.exit(0); });
        mb.add(fajlMenu);

        JMenu szerk = new JMenu(I18n.t("menu.edit"));
        menupont(szerk, I18n.t("menu.edit.undo"), "control Z", this::visszavonas);
        menupont(szerk, I18n.t("menu.edit.redo"), "control Y", this::ujra);
        szerk.addSeparator();
        // A sima billentyűs parancsokat (Del, R, Szóköz, pont) a rajzvászon kezeli, hogy a paramétermezőkben
        // gépeléskor ne süljenek el.
        menupont(szerk, I18n.t("menu.edit.delete"), null, this::torolKijelolt);
        menupont(szerk, I18n.t("menu.edit.rotate"), null, this::forgat);
        menupont(szerk, I18n.t("menu.edit.duplicate"), "control D", this::kettoz);
        mb.add(szerk);

        JMenu szim = new JMenu(I18n.t("menu.sim"));
        menupont(szim, I18n.t("menu.sim.startstop"), null, this::inditasLeallitas);
        menupont(szim, I18n.t("menu.sim.step"), null, this::egyLepes);
        menupont(szim, I18n.t("menu.sim.reset"), "control R", this::alaphelyzet);
        szim.addSeparator();
        menupont(szim, I18n.t("menu.sim.settings"), null, this::beallitasok);
        mb.add(szim);

        JMenu nezet = new JMenu(I18n.t("menu.view"));
        JCheckBoxMenuItem feszSzin = new JCheckBoxMenuItem(I18n.t("menu.view.colors"), rajz.feszSzinezes);
        feszSzin.addActionListener(e -> { rajz.feszSzinezes = feszSzin.isSelected(); vaszon.repaint(); });
        JCheckBoxMenuItem anim = new JCheckBoxMenuItem(I18n.t("menu.view.animation"), animacio);
        anim.addActionListener(e -> { animacio = anim.isSelected(); vaszon.repaint(); });
        JCheckBoxMenuItem aramok = new JCheckBoxMenuItem(I18n.t("menu.view.currents"), aramokMutatasa);
        aramok.addActionListener(e -> { aramokMutatasa = aramok.isSelected(); vaszon.repaint(); });
        nezet.add(feszSzin);
        nezet.add(anim);
        nezet.add(aramok);
        nezet.addSeparator();
        menupont(nezet, I18n.t("menu.view.zoomin"), "control ADD", () -> vaszon.nagyit(1.2));
        menupont(nezet, I18n.t("menu.view.zoomout"), "control SUBTRACT", () -> vaszon.nagyit(1 / 1.2));
        menupont(nezet, I18n.t("menu.view.fit"), "control 0", vaszon::illeszt);
        mb.add(nezet);

        JMenu nyelvMenu = new JMenu(I18n.t("menu.lang"));
        ButtonGroup nyelvCsoport = new ButtonGroup();
        for (Lang l : Lang.values()) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(l.nev, l == I18n.nyelv);
            mi.addActionListener(e -> { I18n.valt(l); nyelvValtozott(); });
            nyelvCsoport.add(mi);
            nyelvMenu.add(mi);
        }
        mb.add(nyelvMenu);

        JMenu sugo = new JMenu(I18n.t("menu.help"));
        menupont(sugo, I18n.t("menu.help.usage"), "F1", this::sugo);
        sugo.addSeparator();
        menupont(sugo, I18n.t("menu.help.about"), null, this::nevjegy);
        mb.add(sugo);
        return mb;
    }

    private void nevjegy() {
        JOptionPane.showMessageDialog(this, I18n.t("dlg.about"), I18n.t("menu.help.about"), JOptionPane.INFORMATION_MESSAGE);
    }

    /** Minden az aktuális nyelven újraépül: menüsor, eszköztár-feliratok, panelek, fejléc. */
    private void nyelvValtozott() {
        setJMenuBar(menusor());
        kijGomb.setText(I18n.t("tool.select"));
        kijGomb.setToolTipText(I18n.t("tool.select.tip"));
        for (String[] t : eszkozok()) eszkozGombok.get(t[0]).setToolTipText(I18n.f("tool.tip", t[1]));
        inditGomb.setText(fut ? I18n.t("btn.stop") : I18n.t("btn.start"));
        resetGomb.setText(I18n.t("btn.reset"));
        resetGomb.setToolTipText(I18n.t("btn.reset.tip"));
        sebessegFelirat.setText(" " + I18n.t("lbl.speed") + ": ");
        sebessegCsuszka.setToolTipText(I18n.t("slider.speed.tip"));
        nyelvGombok.get(I18n.nyelv).setSelected(true);
        tulajdonsagok.nyelvValtozott();
        oszcilloszkop.nyelvValtozott();
        setTitle(ablakCim());
        allapotFrissit();
        vaszon.repaint();
        eszkoztar.revalidate();
        eszkoztar.repaint();
        revalidate();
        repaint();
    }

    /** Fájl → Új áramkör: üres áramkör, visszaállított fejlécnévvel és állapottal. */
    private void ujAramkor() {
        betolt("", true);
        fajl = null;
        nyitottNev = null;
        modositott = false;
        visszavonasVerem.clear();
        ujraVerem.clear();
        setTitle(ablakCim());
    }

    private String ablakCim() {
        return (nyitottNev != null ? nyitottNev + " – " : "") + I18n.t("app.title");
    }

    private void menupont(JMenu m, String szoveg, String gyors, Runnable r) {
        JMenuItem mi = new JMenuItem(szoveg);
        if (gyors != null) mi.setAccelerator(KeyStroke.getKeyStroke(gyors));
        mi.addActionListener(e -> r.run());
        m.add(mi);
    }

    /** Eszköztár-ikon: az alkatrész saját rajzát kicsinyítve mutatja. */
    static class EszkozIkon implements Icon {
        private final Elem minta;

        EszkozIkon(String kod) {
            minta = kod.equals("GND") ? letrehoz(kod, new Point(0, 0), new Point(0, 1)) : letrehoz(kod, new Point(0, 0), new Point(3, 0));
            if (minta instanceof Led) ((Led) minta).szin = 0;
        }

        @Override public int getIconWidth() { return 40; }
        @Override public int getIconHeight() { return 26; }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (minta instanceof Fold) {
                g.translate(x + 20, y + 3);
                g.scale(0.75, 0.75);
            } else {
                g.translate(x + 2, y + 13);
                g.scale(0.75, 0.75);
            }
            minta.rajzol(g, new Rajz(), false, false, false);
            g.dispose();
        }
    }

    /** Egyszerűsített zászlóikon (3:2 képarány) a nyelvválasztó gombokhoz. */
    static class ZaszloIkon implements Icon {
        static final int SZ = 20, M = 14;
        private final Lang nyelv;

        ZaszloIkon(Lang nyelv) { this.nyelv = nyelv; }

        @Override public int getIconWidth() { return SZ; }
        @Override public int getIconHeight() { return M; }

        @Override
        public void paintIcon(Component c, Graphics g0, int x, int y) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(x, y);
            switch (nyelv) {
                case HU: savok(g, new Color(206, 29, 39), Color.WHITE, new Color(67, 111, 78)); break;
                case DE: savok(g, Color.BLACK, new Color(222, 0, 40), new Color(255, 206, 0)); break;
                case PL: savok(g, Color.WHITE, new Color(220, 20, 60)); break;
                case FR: oszlopok(g, new Color(0, 36, 125), Color.WHITE, new Color(237, 41, 57)); break;
                case ES: spanyol(g); break;
                default: angol(g); break;
            }
            g.setColor(new Color(0, 0, 0, 90));
            g.drawRect(0, 0, SZ - 1, M - 1);
            g.dispose();
        }

        private void savok(Graphics2D g, Color... szinek) {
            int h = M / szinek.length;
            for (int i = 0; i < szinek.length; i++) {
                g.setColor(szinek[i]);
                int yy = i * h, hh = i == szinek.length - 1 ? M - yy : h;
                g.fillRect(0, yy, SZ, hh);
            }
        }

        private void oszlopok(Graphics2D g, Color... szinek) {
            int w = SZ / szinek.length;
            for (int i = 0; i < szinek.length; i++) {
                g.setColor(szinek[i]);
                int xx = i * w, ww = i == szinek.length - 1 ? SZ - xx : w;
                g.fillRect(xx, 0, ww, M);
            }
        }

        private void spanyol(Graphics2D g) {
            g.setColor(new Color(170, 21, 27));
            g.fillRect(0, 0, SZ, M);
            g.setColor(new Color(241, 191, 0));
            g.fillRect(0, M / 4, SZ, M / 2);
        }

        /** Egyszerűsített „Union Jack”: kék alapon fehér–piros átlók és kereszt. */
        private void angol(Graphics2D g) {
            g.setColor(new Color(0, 36, 125));
            g.fillRect(0, 0, SZ, M);
            g.setStroke(new BasicStroke(4f));
            g.setColor(Color.WHITE);
            g.drawLine(0, 0, SZ, M);
            g.drawLine(0, M, SZ, 0);
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(207, 20, 43));
            g.drawLine(0, 0, SZ, M);
            g.drawLine(0, M, SZ, 0);
            g.setColor(Color.WHITE);
            g.fillRect(0, M / 2 - 3, SZ, 6);
            g.fillRect(SZ / 2 - 4, 0, 8, M);
            g.setColor(new Color(207, 20, 43));
            g.fillRect(0, M / 2 - 1, SZ, 2);
            g.fillRect(SZ / 2 - 2, 0, 4, M);
        }
    }

    // ---------------------------------------------------------------- szerkesztési műveletek

    void eszkozValasztas(String kod) {
        vaszon.eszkoz = kod;
        JToggleButton b = eszkozGombok.get(kod);
        if (b != null) b.setSelected(true);
        vaszon.setCursor(Cursor.getPredefinedCursor(kod != null ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
        vaszon.requestFocusInWindow();
        allapotFrissit();
    }

    void kijelol(Elem e) {
        vaszon.kijelolt = e;
        tulajdonsagok.beallit(e);
        oszcilloszkop.rogzit(sim.ido, e);
        vaszon.repaint();
    }

    String ujAzonosito(Elem e) {
        if (e instanceof Vezetek || e instanceof Fold) return "";
        int max = 0;
        for (Elem x : elemek) {
            if (x.getClass() == e.getClass() && x.azon.startsWith(e.elotag())) {
                try { max = Math.max(max, Integer.parseInt(x.azon.substring(e.elotag().length()))); } catch (NumberFormatException ignored) { }
            }
        }
        return e.elotag() + (max + 1);
    }

    void visszavonasMentes() {
        visszavonasVerem.push(szerializal());
        if (visszavonasVerem.size() > 200) visszavonasVerem.removeLast();
        ujraVerem.clear();
        modositott = true;
    }

    void aramkorValtozott() {
        sim.piszkos = true;
        vaszon.hibas = null;
        hibaCimke.setText(" ");
        vaszon.repaint();
    }

    void parameterValtozott() {
        aramkorValtozott();
        tulajdonsagok.frissitMeresek();
        tulajdonsagok.cim.setText(vaszon.kijelolt == null ? " " : vaszon.kijelolt.azon + " – " + vaszon.kijelolt.nev());
    }

    void torolKijelolt() {
        if (vaszon.kijelolt == null) return;
        visszavonasMentes();
        elemek.remove(vaszon.kijelolt);
        kijelol(null);
        aramkorValtozott();
    }

    void forgat() {
        Elem e = vaszon.kijelolt;
        if (e == null) return;
        visszavonasMentes();
        int dx = e.b.x - e.a.x, dy = e.b.y - e.a.y;
        e.b = new Point(e.a.x - dy, e.a.y + dx);
        aramkorValtozott();
    }

    void kettoz() {
        Elem e = vaszon.kijelolt;
        if (e == null) return;
        visszavonasMentes();
        Elem uj = elemSorbol(elemSor(e).replaceFirst("^(\\S+) (-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)",
                "$1 " + (e.a.x + 2) + " " + (e.a.y + 2) + " " + (e.b.x + 2) + " " + (e.b.y + 2)));
        uj.azon = ujAzonosito(uj);
        elemek.add(uj);
        kijelol(uj);
        aramkorValtozott();
    }

    void visszavonas() {
        if (visszavonasVerem.isEmpty()) { Toolkit.getDefaultToolkit().beep(); return; }
        ujraVerem.push(szerializal());
        betolt(visszavonasVerem.pop(), false);
    }

    void ujra() {
        if (ujraVerem.isEmpty()) { Toolkit.getDefaultToolkit().beep(); return; }
        visszavonasVerem.push(szerializal());
        betolt(ujraVerem.pop(), false);
    }

    // ---------------------------------------------------------------- szimuláció vezérlése

    double sebesseg() { return Math.pow(10, sebessegCsuszka.getValue() / 10.0 - 6); }

    void inditasLeallitas() {
        fut = !fut;
        if (fut && sim.dt <= 0) fut = false;
        inditGomb.setText(fut ? I18n.t("btn.stop") : I18n.t("btn.start"));
        utolsoKeret = System.nanoTime();
        lepesAkku = 0;
        allapotFrissit();
    }

    void alaphelyzet() {
        sim.alaphelyzet();
        oszcilloszkop.torol();
        hibaCimke.setText(" ");
        vaszon.hibas = null;
        vaszon.repaint();
        tulajdonsagok.frissitMeresek();
    }

    void egyLepes() {
        if (fut) inditasLeallitas();
        try {
            sim.lepes();
            oszcilloszkop.rogzit(sim.ido, vaszon.kijelolt);
        } catch (AramkorHiba h) {
            hibaKezeles(h);
        }
        vaszon.repaint();
        tulajdonsagok.frissitMeresek();
    }

    private void hibaKezeles(AramkorHiba h) {
        if (fut) inditasLeallitas();
        vaszon.hibas = h.elem;
        hibaCimke.setText("<html>" + I18n.f("err.label", h.getMessage()) + "</html>");
        vaszon.repaint();
    }

    /** Animációs keret (~33/s): szimulációs lépések, pöttyök mozgatása, kijelzők frissítése. */
    private void keret() {
        long most = System.nanoTime();
        double valosDt = Math.min(0.1, (most - utolsoKeret) / 1e9);
        utolsoKeret = most;
        if (fut) {
            lepesAkku += sebesseg() * valosDt / sim.dt;
            int lepesek = (int) lepesAkku;
            lepesAkku -= lepesek;
            long hatar = most + 22_000_000L;
            lassu = false;
            try {
                for (int i = 0; i < lepesek; i++) {
                    sim.lepes();
                    oszcilloszkop.rogzit(sim.ido, vaszon.kijelolt);
                    lepesSzamlalo++;
                    if ((i & 15) == 15 && System.nanoTime() > hatar) { lassu = true; lepesAkku = 0; break; }
                }
            } catch (AramkorHiba h) {
                hibaKezeles(h);
            }
            for (Elem e : elemek) e.pottyPoz += pottySebesseg(e.aram) * valosDt;
        }
        if (most - meresKezdet > 1_000_000_000L) {
            lepesekMasodpercenkent = lepesSzamlalo;
            lepesSzamlalo = 0;
            meresKezdet = most;
            allapotFrissit();
        }
        vaszon.repaint();
        oszcilloszkop.frissit();
        tulajdonsagok.frissitMeresek();
    }

    /** Pöttysebesség (rácsegység/s) logaritmikusan az áramerősségből. */
    static double pottySebesseg(double aram) {
        double a = Math.abs(aram);
        if (a < 1e-9) return 0;
        return Math.signum(aram) * Math.min(220, 18 * (Math.log10(a) + 9));
    }

    void allapotFrissit() {
        String s = I18n.t(fut ? "status.running" : "status.stopped")
                + "   |   " + I18n.t("status.time") + " " + si(sim.ido, "s")
                + "   |   " + I18n.t("status.timestep") + " " + siRovid(sim.dt, "s")
                + "   |   " + lepesekMasodpercenkent + " " + I18n.t("status.stepspersec")
                + (lassu ? " " + I18n.t("status.slow") : "")
                + (sim.konvergenciaHibak > 0 ? "   |   " + I18n.t("status.convergence") + " " + sim.konvergenciaHibak : "");
        Point p = vaszon.hoverPont;
        if (p != null) {
            double v = sim.pontFesz(p);
            s += "   |   " + I18n.t("status.grid") + " (" + p.x + ", " + p.y + ")" + (Double.isNaN(v) ? "" : "  U = " + si(v, "V"));
        }
        if (vaszon.eszkoz != null) s += "   |   " + I18n.t("status.place");
        allapot.setText(s);
    }

    private void beallitasok() {
        JTextField dtMezo = new JTextField(siRovid(sim.dt, "").trim(), 10);
        JComboBox<String> modszer = new JComboBox<>(new String[]{I18n.t("method.trapez"), I18n.t("method.euler")});
        modszer.setSelectedIndex(sim.trapez ? 0 : 1);
        JPanel p = new JPanel(new GridLayout(0, 2, 6, 6));
        p.add(new JLabel(I18n.t("dlg.settings.dt")));
        p.add(dtMezo);
        p.add(new JLabel(I18n.t("dlg.settings.method")));
        p.add(modszer);
        if (JOptionPane.showConfirmDialog(this, p, I18n.t("dlg.settings.title"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            double dt = ertelmez(dtMezo.getText());
            if (dt < 1e-12 || dt > 1) throw new NumberFormatException();
            sim.dt = dt;
            sim.trapez = modszer.getSelectedIndex() == 0;
            alaphelyzet();
            allapotFrissit();
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, I18n.t("dlg.settings.dt.invalid"), I18n.t("app.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- fájlkezelés

    String elemSor(Elem e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.kod()).append(' ').append(e.a.x).append(' ').append(e.a.y).append(' ').append(e.b.x).append(' ').append(e.b.y);
        for (Param p : e.parameterek()) sb.append(' ').append(p.kulcs).append('=').append(p.olvas.getAsDouble());
        return sb.toString();
    }

    Elem elemSorbol(String sor) {
        String[] t = sor.trim().split("\\s+");
        Elem e = letrehoz(t[0], new Point(Integer.parseInt(t[1]), Integer.parseInt(t[2])),
                new Point(Integer.parseInt(t[3]), Integer.parseInt(t[4])));
        Map<String, Param> parok = new HashMap<>();
        for (Param p : e.parameterek()) parok.put(p.kulcs, p);
        // a választó paramétereket (pl. LED színe) előbb állítjuk, mert alapértéket adhatnak a többinek
        List<String[]> ertekek = new ArrayList<>();
        for (int i = 5; i < t.length; i++) {
            int eq = t[i].indexOf('=');
            if (eq > 0) ertekek.add(new String[]{t[i].substring(0, eq), t[i].substring(eq + 1)});
        }
        ertekek.sort(Comparator.comparingInt(kv -> parok.containsKey(kv[0]) && parok.get(kv[0]).valasztek != null ? 0 : 1));
        for (String[] kv : ertekek) {
            Param p = parok.get(kv[0]);
            if (p != null) p.ir.accept(ertelmez(kv[1]));
        }
        e.alaphelyzet();
        return e;
    }

    String szerializal() {
        StringBuilder sb = new StringBuilder("# " + PROGRAM + " áramkör\n");
        sb.append("$ dt=").append(sim.dt).append(" trapez=").append(sim.trapez ? 1 : 0)
                .append(" sebesseg=").append(sebessegCsuszka.getValue()).append(" ablak=").append(oszcilloszkop.ablak).append('\n');
        for (Elem e : elemek) sb.append(elemSor(e)).append('\n');
        return sb.toString();
    }

    /** Áramkör betöltése szöveges formátumból. */
    void betolt(String szoveg, boolean beallitasokkal) {
        List<Elem> uj = new ArrayList<>();
        for (String sor : szoveg.split("\\R")) {
            sor = sor.trim();
            if (sor.isEmpty() || sor.startsWith("#")) continue;
            if (sor.startsWith("$")) {
                if (!beallitasokkal) continue;
                for (String kv : sor.substring(1).trim().split("\\s+")) {
                    String[] p = kv.split("=");
                    if (p.length != 2) continue;
                    switch (p[0]) {
                        case "dt": sim.dt = Double.parseDouble(p[1]); break;
                        case "trapez": sim.trapez = p[1].equals("1"); break;
                        case "sebesseg": sebessegCsuszka.setValue(Integer.parseInt(p[1])); break;
                        case "ablak": oszcilloszkop.beallitAblak(Double.parseDouble(p[1])); break;
                        default:
                    }
                }
                continue;
            }
            uj.add(elemSorbol(sor));
        }
        elemek.clear();
        for (Elem e : uj) {
            e.azon = ujAzonosito(e);
            elemek.add(e);
        }
        kijelol(null);
        sim.alaphelyzet();
        oszcilloszkop.torol();
        aramkorValtozott();
        tulajdonsagok.frissitMeresek();
    }

    private void megnyitas() {
        if (!mentesKerdes()) return;
        JFileChooser fc = new JFileChooser(fajl != null ? fajl.getParent().toFile() : null);
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(I18n.t("filefilter"), "aramkor"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path p = fc.getSelectedFile().toPath();
            betolt(new String(Files.readAllBytes(p), StandardCharsets.UTF_8), true);
            fajl = p;
            nyitottNev = p.getFileName().toString();
            modositott = false;
            visszavonasVerem.clear();
            ujraVerem.clear();
            setTitle(ablakCim());
            SwingUtilities.invokeLater(vaszon::illeszt);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, I18n.f("dlg.open.fail", ex.getMessage()), I18n.t("app.title"), JOptionPane.ERROR_MESSAGE);
        }
    }

    private boolean mentes(boolean maskent) {
        Path cel = fajl;
        if (cel == null || maskent) {
            JFileChooser fc = new JFileChooser(fajl != null ? fajl.getParent().toFile() : null);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(I18n.t("filefilter"), "aramkor"));
            fc.setSelectedFile(new File(fajl != null ? fajl.getFileName().toString() : "aramkor.aramkor"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
            cel = fc.getSelectedFile().toPath();
            if (!cel.toString().toLowerCase().endsWith(".aramkor")) cel = Path.of(cel + ".aramkor");
        }
        try {
            Files.write(cel, szerializal().getBytes(StandardCharsets.UTF_8));
            fajl = cel;
            nyitottNev = cel.getFileName().toString();
            modositott = false;
            setTitle(ablakCim());
            return true;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, I18n.f("dlg.save.fail", ex.getMessage()), I18n.t("app.title"), JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /** Igaz, ha folytatható a művelet (nincs mentetlen változás, vagy a felhasználó döntött). */
    private boolean mentesKerdes() {
        if (!modositott || elemek.isEmpty()) return true;
        int v = JOptionPane.showConfirmDialog(this, I18n.t("dlg.unsaved"), I18n.t("app.title"), JOptionPane.YES_NO_CANCEL_OPTION);
        if (v == JOptionPane.YES_OPTION) return mentes(false);
        return v == JOptionPane.NO_OPTION;
    }

    void peldaBetoltes(String[] p) {
        betolt(p[1], true);
        fajl = null;
        nyitottNev = I18n.t(p[0]);
        modositott = false;
        visszavonasVerem.clear();
        ujraVerem.clear();
        setTitle(ablakCim());
        hibaCimke.setText(" ");
        allapot.setText(I18n.t(p[2]));
        SwingUtilities.invokeLater(vaszon::illeszt);
        if (!fut) inditasLeallitas();
    }

    private void sugo() {
        JTextArea ta = new JTextArea(I18n.sugo());
        ta.setEditable(false);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JOptionPane.showMessageDialog(this, new JScrollPane(ta), I18n.t("menu.help.usage"), JOptionPane.PLAIN_MESSAGE);
    }

    // ---------------------------------------------------------------- példák

    /** {névkulcs, áramkör, leíráskulcs az állapotsorba} */
    static final String[][] PELDAK = {
            {"ex.led1.name",
                    "$ dt=1e-5 trapez=1 sebesseg=30 ablak=0.01\n"
                            + "V 10 18 10 10 U=9 Rb=0\nR 10 10 18 10 R=470 P=0.25\nLED 18 10 18 18 szin=0 Uf=1.9 Imax=0.02\n"
                            + "AM 18 18 10 18\nGND 10 18 10 20\n",
                    "ex.led1.desc"},
            {"ex.divider.name",
                    "$ dt=1e-5 trapez=1 sebesseg=30 ablak=0.01\n"
                            + "V 10 18 10 10 U=12 Rb=0\nW 10 10 18 10\nR 18 10 18 14 R=1000 P=0.25\nR 18 14 18 18 R=2000 P=0.25\n"
                            + "W 18 18 10 18\nW 18 14 23 14\nW 18 18 23 18\nVM 23 14 23 18 R=1e7\nGND 10 18 10 20\n",
                    "ex.divider.desc"},
            {"ex.rc.name",
                    "$ dt=1e-4 trapez=1 sebesseg=60 ablak=5\n"
                            + "V 10 18 10 10 U=10 Rb=0\nS 10 10 16 10 zart=1\nR 16 10 22 10 R=10000 P=0.25\n"
                            + "C 22 10 22 18 C=1e-4 U0=0 Umax=50\nW 22 18 10 18\nGND 10 18 10 20\n",
                    "ex.rc.desc"},
            {"ex.rlc.name",
                    "$ dt=5e-6 trapez=1 sebesseg=40 ablak=0.01\n"
                            + "AC 10 18 10 10 jel=0 A=5 f=503.3 fi=0 U0=0 Rb=0\nR 10 10 16 10 R=10 P=5\nL 16 10 22 10 L=0.01 I0=0\n"
                            + "C 22 10 22 18 C=1e-5 U0=0 Umax=50\nW 22 18 10 18\nGND 10 18 10 20\n",
                    "ex.rlc.desc"},
            {"ex.bridge.name",
                    "$ dt=2e-5 trapez=1 sebesseg=47 ablak=0.05\n"
                            + "AC 6 18 6 10 jel=0 A=12 f=50 fi=0 U0=0 Rb=0.5\nW 6 10 10 10\nW 10 10 10 14\nW 6 18 6 22\nW 6 22 18 22\nW 18 22 18 14\n"
                            + "D 10 14 14 10\nD 18 14 14 10\nD 14 18 10 14\nD 14 18 18 14\n"
                            + "W 14 10 24 10\nW 14 18 14 20\nW 14 20 24 20\nC 24 10 24 20 C=4.7e-4 U0=0 Umax=25\n"
                            + "W 24 10 33 10\nW 24 20 33 20\nR 33 10 33 20 R=1000 P=0.5\n"
                            + "W 33 10 42 10\nW 33 20 42 20\nVM 42 10 42 20 R=1e7\nGND 33 20 33 23\n",
                    "ex.bridge.desc"},
            {"ex.zener.name",
                    "$ dt=1e-5 trapez=1 sebesseg=30 ablak=0.01\n"
                            + "V 10 18 10 10 U=15 Rb=0\nR 10 10 18 10 R=470 P=0.5\nD 18 18 18 10 Is=1e-9 n=1.7 Uz=5.1 Imax=0.2\n"
                            + "W 18 10 24 10\nR 24 10 24 18 R=1000 P=0.25\nW 24 18 18 18\nW 18 18 10 18\n"
                            + "W 24 10 28 10\nW 24 18 28 18\nVM 28 10 28 18 R=1e7\nGND 10 18 10 20\n",
                    "ex.zener.desc"},
            {"ex.rl.name",
                    "$ dt=1e-5 trapez=0 sebesseg=47 ablak=0.05\n"
                            + "V 10 18 10 10 U=12 Rb=0\nS 10 10 16 10 zart=0\nR 16 10 22 10 R=100 P=2\nL 22 10 22 18 L=1 I0=0\n"
                            + "W 22 18 10 18\nGND 10 18 10 20\nW 22 10 28 10\nLAMP 28 10 28 18 Un=12 Pn=1\nW 28 18 22 18\n",
                    "ex.rl.desc"}
    };

    // ================================================================ indítás

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
            AramkorSzimulator app = new AramkorSzimulator();
            app.setVisible(true);
            app.peldaBetoltes(PELDAK[0]);
        });
    }
}
