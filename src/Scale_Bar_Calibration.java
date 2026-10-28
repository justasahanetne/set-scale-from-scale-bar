/*
 * Set Scale from Scale Bar - ImageJ / Fiji plugin
 * Copyright (C) 2026 Justinas Jorudas
 * Developed with Claude (Anthropic)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Line;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.gui.WaitForUserDialog;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.ImageProcessor;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scale Bar Calibration for ImageJ 1.54p / Fiji.
 *
 * Draw a rectangle around the scale bar and its label (e.g. "10 µm"), then run
 * Plugins > Measurement > Set Scale from Scale Bar. The plugin
 *   1. finds the bar (longest straight horizontal stroke) and measures its length
 *      with sub-pixel precision,
 *   2. reads the label with a small built-in character recogniser (digits and units),
 *   3. shows the result for confirmation and applies it with Analyze > Set Scale.
 *
 * If no rectangle is present, the last used rectangle is reused (handy for image series
 * from the same instrument). Hold Shift while starting to always show the dialog.
 */
public class Scale_Bar_Calibration implements PlugIn {

    static final String TITLE = "Set Scale from Scale Bar";
    static final String PREF = "sbc.";
    static final String CHARS = "0123456789.,µunmpc";

    // ------------------------------------------------------------------ entry

    public void run(String arg) {
        ImagePlus imp = IJ.getImage();
        if (imp == null) return;
        Roi roi = imp.getRoi();
        boolean reused = false;
        if (roi == null || !roi.isArea()) {
            Rectangle last = lastRect(imp);
            if (last != null) {
                roi = new Roi(last);
                reused = true;
            } else {
                IJ.setTool("rectangle");
                new WaitForUserDialog(TITLE,
                        "Draw a rectangle around the scale bar and its label\n"
                                + "(a little larger than the bar), then click OK.").show();
                roi = imp.getRoi();
                if (roi == null || !roi.isArea()) {
                    IJ.error(TITLE, "A rectangular selection around the scale bar is required.");
                    return;
                }
            }
        }
        Rectangle r = roi.getBounds().intersection(new Rectangle(0, 0, imp.getWidth(), imp.getHeight()));
        if (r.width < 10 || r.height < 3) {
            IJ.error(TITLE, "The selection is too small.");
            return;
        }

        Result res;
        try {
            res = analyze(imp.getProcessor(), r);
        } catch (Exception e) {
            IJ.handleException(e);
            return;
        }
        if (res == null || res.barLength <= 0) {
            IJ.error(TITLE, "No scale bar found in the selected rectangle.\n \n"
                    + "Make sure the rectangle contains the whole bar\n"
                    + "(with some margin) and no long border lines.");
            return;
        }
        Prefs.set(PREF + "rect", r.x + "," + r.y + "," + r.width + "," + r.height);

        double known = res.value;
        String unit = res.unit;
        boolean labelOk = known > 0 && unit != null;
        if (!labelOk) {
            if (known <= 0) known = Prefs.get(PREF + "known", 10);
            if (unit == null) unit = Prefs.get(PREF + "unit", "µm");
        }
        boolean skip = Prefs.get(PREF + "skipDialog", false) && labelOk && !IJ.shiftKeyDown();
        boolean global = Prefs.get(PREF + "global", false);
        String[] methods = {"Automatic", "Outer ends of the bar", "Centres of the end ticks"};
        int method = (int) Prefs.get(PREF + "method", 0);
        if (method < 0 || method > 2) method = 0;
        final Result fres = res;
        double bar = lengthFor(res, method);

        if (!skip) {
            Overlay saved = imp.getOverlay();
            Overlay ov = saved == null ? new Overlay() : saved.duplicate();
            Roi box = new Roi(r);
            box.setStrokeColor(Color.yellow);
            ov.add(box);
            Line bl = new Line(res.x1, res.y, res.x2, res.y);
            bl.setStrokeColor(Color.red);
            bl.setStrokeWidth(Math.max(1, res.thickness));
            ov.add(bl);
            imp.setOverlay(ov);

            GenericDialog gd = new GenericDialog(TITLE);
            gd.addMessage("Detected scale bar (red line): " + IJ.d2s(res.outerLength, 2) + " px between outer ends"
                    + (res.tickLength > 0 ? ", " + IJ.d2s(res.tickLength, 2) + " px between end-tick centres" : "")
                    + (res.ruler ? "\n(ruler with intermediate ticks)" : "")
                    + (reused ? "\nUsing the rectangle from the previous image." : ""));
            gd.addMessage(labelOk
                    ? "Label read as: \"" + res.text + "\""
                    : "Label could not be read" + (res.text.length() > 0 ? " (got \"" + res.text + "\")" : "")
                    + " - please enter the value.");
            gd.addChoice("Measure bar between:", methods, methods[method]);
            gd.addNumericField("Bar length:", bar, 2, 10, "pixels");
            gd.addNumericField("Known distance:", known, known == Math.rint(known) ? 0 : 3, 10, "");
            gd.addStringField("Unit:", unit, 8);
            gd.addCheckbox("Global (apply to all images)", global);
            gd.addCheckbox("Don't show this dialog when the label is read (hold Shift to show)",
                    Prefs.get(PREF + "skipDialog", false));
            gd.addHelp("<html><h3>" + TITLE + "</h3>Draw a rectangle around the scale bar and its "
                    + "label, then run the command. Works with plain bars, bars interrupted by their label "
                    + "(SEM data bars) and coloured rulers with ticks.<br><br>"
                    + "<b>Measure bar between</b>: <i>Automatic</i> uses the centres of the end ticks for rulers "
                    + "with intermediate ticks and the outer ends otherwise.<br><br>"
                    + "If no rectangle is drawn, the rectangle used last time is reused.<br>"
                    + "Hold <b>Shift</b> when starting the command to always show this dialog.</html>");
            gd.addDialogListener((dlg, e) -> {
                if (e != null && e.getSource() instanceof java.awt.Choice) {
                    int m = ((java.awt.Choice) e.getSource()).getSelectedIndex();
                    java.util.Vector<?> nf = dlg.getNumericFields();
                    if (nf != null && !nf.isEmpty())
                        ((java.awt.TextField) nf.get(0)).setText(IJ.d2s(lengthFor(fres, m), 2));
                }
                return true;
            });
            gd.showDialog();
            imp.setOverlay(saved);
            if (gd.wasCanceled()) return;
            method = gd.getNextChoiceIndex();
            Prefs.set(PREF + "method", method);
            bar = gd.getNextNumber();
            known = gd.getNextNumber();
            unit = gd.getNextString().trim();
            global = gd.getNextBoolean();
            Prefs.set(PREF + "skipDialog", gd.getNextBoolean());
            if (Double.isNaN(bar) || bar <= 0 || Double.isNaN(known) || known <= 0) {
                IJ.error(TITLE, "Bar length and known distance must be positive numbers.");
                return;
            }
        }
        Prefs.set(PREF + "known", known);
        Prefs.set(PREF + "unit", unit);
        Prefs.set(PREF + "global", global);
        apply(imp, bar, known, unit, global);
    }

    /** Bar length for the chosen method (0 automatic, 1 outer ends, 2 end-tick centres). */
    static double lengthFor(Result r, int method) {
        if (method == 1 || r.tickLength <= 0) return r.outerLength;
        if (method == 2) return r.tickLength;
        return r.barLength;
    }

    static Rectangle lastRect(ImagePlus imp) {
        String s = Prefs.get(PREF + "rect", "");
        String[] p = s.split(",");
        if (p.length != 4) return null;
        try {
            Rectangle r = new Rectangle(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
            if (r.x < 0 || r.y < 0 || r.x + r.width > imp.getWidth() || r.y + r.height > imp.getHeight())
                return null;
            return r;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void apply(ImagePlus imp, double bar, double known, String unit, boolean global) {
        String u = unit;
        if (u.equals("µm") || u.equals("μm") || u.equalsIgnoreCase("micron")) u = "um";
        String opts = "distance=" + IJ.d2s(bar, 4) + " known=" + IJ.d2s(known, 6)
                + " unit=" + (u.indexOf(' ') >= 0 ? "[" + u + "]" : u) + (global ? " global" : "");
        IJ.run(imp, "Set Scale...", opts);
        Calibration cal = imp.getCalibration();
        IJ.showStatus("Scale set: " + IJ.d2s(bar, 2) + " px = " + IJ.d2s(known, 4, 9) + " "
                + cal.getUnits() + "  (" + IJ.d2s(cal.pixelWidth, 6, 9) + " " + cal.getUnit() + "/pixel)");
    }

    // ------------------------------------------------------------------ analysis

    static class Result {
        double barLength;      // pixels (sub-pixel)
        double x1, x2, y;      // bar position in image coordinates
        double thickness = 1;
        String text = "";
        double value = -1;
        String unit;
        double confidence = Double.MAX_VALUE;   // average template distance (lower = better)
        double outerLength = -1, tickLength = -1;
        boolean labelNear, labelClose, labelInGap, ruler;
    }

    static class Comp {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1, area;
        Set<Integer> labels = new HashSet<Integer>();
        int clipX0 = Integer.MIN_VALUE, clipX1 = Integer.MAX_VALUE;  // column limits after splitting

        int w() { return x1 - x0 + 1; }
        int h() { return y1 - y0 + 1; }

        void addBox(int ax0, int ay0, int ax1, int ay1, int a) {
            x0 = Math.min(x0, ax0); y0 = Math.min(y0, ay0);
            x1 = Math.max(x1, ax1); y1 = Math.max(y1, ay1);
            area += a;
        }
    }

    static final double TOL = 0.2;          // colour tolerance (normalised units) for the bar
    static final int MIN_BAR = 12;

    /** A straight horizontal stroke candidate. */
    static class Seg {
        int y, x0, x1, top, bot;  // rows top..bot, columns x0..x1 (inclusive)
        double[] col;             // mean colour
        double score;
        int len() { return x1 - x0 + 1; }
    }

    static Result analyze(ImageProcessor ip, Rectangle user) {
        Rectangle bounds = new Rectangle(0, 0, ip.getWidth(), ip.getHeight());
        user = user.intersection(bounds);
        if (user.width < 10 || user.height < 3) return new Result();
        // work on a slightly larger area so a label cut by a tight rectangle is still read whole;
        // the bar itself must lie inside the user's rectangle
        int padY = Math.max(10, user.height / 2), padX = 10;
        Rectangle r = new Rectangle(user.x - padX, user.y - padY, user.width + 2 * padX, user.height + 2 * padY)
                .intersection(bounds);
        int ux0 = user.x - r.x, uy0 = user.y - r.y, ux1 = ux0 + user.width - 1, uy1 = uy0 + user.height - 1;
        int w = r.width, h = r.height;
        // ---- colour (normalised to 0..1) and luminance of the rectangle
        boolean rgb = ip instanceof ij.process.ColorProcessor;
        float[][] c = new float[3][w * h];
        float[] L = new float[w * h];
        if (rgb) {
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int p = ip.getPixel(r.x + x, r.y + y);
                    int i = y * w + x;
                    c[0][i] = ((p >> 16) & 0xff) / 255f;
                    c[1][i] = ((p >> 8) & 0xff) / 255f;
                    c[2][i] = (p & 0xff) / 255f;
                    L[i] = 0.299f * c[0][i] + 0.587f * c[1][i] + 0.114f * c[2][i];
                }
        } else {
            float[] v = new float[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) v[y * w + x] = ip.getPixelValue(r.x + x, r.y + y);
            float[] s = v.clone();
            Arrays.sort(s);
            float lo = s[(int) (0.002 * (s.length - 1))], hi = s[(int) (0.998 * (s.length - 1))];
            if (hi <= lo) { lo = s[0]; hi = s[s.length - 1] + 1e-6f; }
            for (int i = 0; i < v.length; i++) {
                float f = Math.max(0f, Math.min(1f, (v[i] - lo) / (hi - lo)));
                c[0][i] = c[1][i] = c[2][i] = L[i] = f;
            }
        }
        Result res = new Result();

        // ---- 1. bar candidates: uniform horizontal strokes that contrast with the rows above and below.
        // Score: length; x2 with end ticks; x0.5 for borders between two differently coloured areas
        // (frame lines of an SEM data bar); x0.6 per end touching the rectangle edge.
        List<Seg> segs = findStrokes(c, w, h);
        if (segs.isEmpty()) return res;
        List<List<Seg>> groups = new ArrayList<List<Seg>>();
        List<Double> scores = new ArrayList<Double>();
        for (Seg s : segs) {
            int ym0 = (s.top + s.bot) / 2;
            int ov = Math.min(s.x1, ux1) - Math.max(s.x0, ux0) + 1;
            if (ym0 < uy0 || ym0 > uy1 || ov < 0.5 * s.len()) continue;   // outside the user's rectangle
            List<Seg> parts = grow(s, segs);
            int gx0 = Integer.MAX_VALUE, gx1 = -1, top = Integer.MAX_VALUE, bot = -1;
            for (Seg p : parts) {
                gx0 = Math.min(gx0, p.x0); gx1 = Math.max(gx1, p.x1);
                top = Math.min(top, p.top); bot = Math.max(bot, p.bot);
            }
            boolean dup = false;
            for (List<Seg> g : groups) if (g.containsAll(parts) && parts.containsAll(g)) dup = true;
            if (dup) continue;
            double sc = gx1 - gx0 + 1;
            double[] bgc = outsideColour(c, w, h, top, bot, gx0, gx1);
            boolean[] like = likeMask(c, w, h, s.col, bgc, 0.5);
            double[] tl = tickInfo(like, w, h, top, bot, gx0, true);
            double[] tr = tickInfo(like, w, h, top, bot, gx1, false);
            // matching ticks at both ends (same width and height) are typical for scale bars
            if (tl != null && tr != null && Math.abs(tl[1] - tr[1]) <= 1
                    && Math.abs(tl[2] - tr[2]) <= Math.max(2, 0.2 * Math.max(tl[2], tr[2]))
                    && Math.abs(tl[3] - tr[3]) <= Math.max(2, 0.2 * Math.max(tl[3], tr[3]))) sc *= 2;
            else if (tl != null || tr != null) sc *= 1.2;
            double[] above = rowMedian(c, w, h, top - 2, gx0, gx1), below = rowMedian(c, w, h, bot + 2, gx0, gx1);
            if (above != null && below != null && dist(above, below) > 2 * TOL) sc *= 0.5;
            // a bar has free ends: just beyond them is background again
            int ym = (top + bot) / 2;
            if (gx0 <= 1) sc *= 0.6;
            else if (gx0 - 3 >= 0 && dist(c[0][ym * w + gx0 - 3], c[1][ym * w + gx0 - 3], c[2][ym * w + gx0 - 3], bgc) > 2 * TOL) sc *= 0.5;
            if (gx1 >= w - 2) sc *= 0.6;
            else if (gx1 + 3 < w && dist(c[0][ym * w + gx1 + 3], c[1][ym * w + gx1 + 3], c[2][ym * w + gx1 + 3], bgc) > 2 * TOL) sc *= 0.5;
            groups.add(parts);
            scores.add(sc);
        }
        Integer[] idx = new Integer[groups.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (a, b) -> Double.compare(scores.get(b), scores.get(a)));
        Result best = null;
        double bestFinal = -1;
        for (int k = 0; k < Math.min(4, idx.length); k++) {
            double sc = scores.get(idx[k]);
            if (k > 0 && sc < 0.2 * scores.get(idx[0])) break;
            Result cand = measure(groups.get(idx[k]), c, L, w, h, r);
            double fin = sc * (cand.labelInGap ? 3 : cand.labelClose ? 2.5 : cand.labelNear ? 1.5 : 1);
            if (DEBUG) System.out.println("candidate x" + IJ.d2s(cand.x1, 0) + "-" + IJ.d2s(cand.x2, 0) + " y=" + cand.y
                    + " score=" + IJ.d2s(sc, 1) + " label=" + cand.text + " near=" + cand.labelNear
                    + " gap=" + cand.labelInGap + " final=" + IJ.d2s(fin, 1));
            if (fin > bestFinal) { bestFinal = fin; best = cand; }
        }
        return best;
    }

    /** Join collinear pieces of the same colour: the bar may be interrupted by its label. */
    static List<Seg> grow(Seg seed, List<Seg> segs) {
        List<Seg> parts = new ArrayList<Seg>();
        parts.add(seed);
        int bx0 = seed.x0, bx1 = seed.x1;
        boolean grown = true;
        while (grown) {
            grown = false;
            Seg bestL = null, bestR = null;
            for (Seg s : segs) {
                if (parts.contains(s)) continue;
                if (Math.abs((s.top + s.bot) - (seed.top + seed.bot)) > 3) continue;
                if (Math.abs((s.bot - s.top) - (seed.bot - seed.top)) > 2) continue;
                if (dist(s.col, seed.col) > TOL) continue;
                int ml = Math.max(s.len(), bx1 - bx0 + 1);
                double ratio = Math.max(s.len(), bx1 - bx0 + 1) / (double) Math.min(s.len(), bx1 - bx0 + 1);
                // pieces on both sides of a centred label have similar lengths; allow larger gaps then
                int maxGap = (int) ((ratio <= 1.5 ? 6 : 3) * ml);
                if (s.x1 < bx0 && bx0 - s.x1 - 1 <= maxGap && (bestL == null || s.x1 > bestL.x1)) bestL = s;
                if (s.x0 > bx1 && s.x0 - bx1 - 1 <= maxGap && (bestR == null || s.x0 < bestR.x0)) bestR = s;
            }
            int cur = bx1 - bx0 + 1;
            if (bestL != null && bestL.len() >= 0.15 * cur && bestL.len() <= 6.0 * cur) { parts.add(bestL); bx0 = bestL.x0; grown = true; }
            if (bestR != null && bestR.len() >= 0.15 * cur && bestR.len() <= 6.0 * cur) { parts.add(bestR); bx1 = bestR.x1; grown = true; }
        }
        return parts;
    }

    /** Mask of pixels whose colour lies at least 'frac' of the way from bg towards col (and near col). */
    static boolean[] likeMask(float[][] c, int w, int h, double[] col, double[] bg, double frac) {
        double[] axis = {col[0] - bg[0], col[1] - bg[1], col[2] - bg[2]};
        double an = axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2];
        boolean[] m = new boolean[w * h];
        if (an < 1e-9) return m;
        double maxDist = Math.sqrt(an) * (1 - frac) + TOL;
        for (int i = 0; i < m.length; i++) {
            double p = ((c[0][i] - bg[0]) * axis[0] + (c[1][i] - bg[1]) * axis[1] + (c[2][i] - bg[2]) * axis[2]) / an;
            m[i] = p >= frac && dist(c[0][i], c[1][i], c[2][i], col) <= maxDist;
        }
        return m;
    }

    /** Measure the bar made of 'parts' and read the label next to it. */
    static Result measure(List<Seg> parts, float[][] c, float[] L, int w, int h, Rectangle r) {
        Result res = new Result();
        Seg seed = parts.get(0);
        int bx0 = Integer.MAX_VALUE, bx1 = -1;
        for (Seg p : parts) { bx0 = Math.min(bx0, p.x0); bx1 = Math.max(bx1, p.x1); }
        int top = seed.top, bot = seed.bot;
        for (Seg s : parts) { top = Math.min(top, s.top); bot = Math.max(bot, s.bot); }
        double[] barCol = seed.col;
        res.thickness = bot - top + 1;

        // background colour next to the bar (median of the rows just outside)
        double[] bg = outsideColour(c, w, h, top, bot, bx0, bx1);

        // ---- 2. sub-pixel outer ends from the averaged profile (projection onto bar-bg axis)
        int wx0 = Math.max(0, bx0 - 4), wx1 = Math.min(w - 1, bx1 + 4);
        double[] prof = new double[wx1 - wx0 + 1];
        double[] axis = {barCol[0] - bg[0], barCol[1] - bg[1], barCol[2] - bg[2]};
        double an = axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2];
        if (an < 1e-9) an = 1e-9;
        int rows = 0;
        for (int y = top; y <= bot; y++) {
            if (bot - top >= 2 && (y == top || y == bot)) continue;
            for (int x = wx0; x <= wx1; x++) {
                int i = y * w + x;
                prof[x - wx0] += ((c[0][i] - bg[0]) * axis[0] + (c[1][i] - bg[1]) * axis[1]
                        + (c[2][i] - bg[2]) * axis[2]) / an;
            }
            rows++;
        }
        double left = bx0, right = bx1 + 1;
        if (rows > 0) {
            for (int i = 0; i < prof.length; i++) prof[i] /= rows;
            left = crossing(prof, wx0, bx0 - wx0, true, left);
            right = crossing(prof, wx0, bx1 - wx0, false, right);
        }
        res.outerLength = right - left;
        if (Math.abs(res.outerLength - (bx1 - bx0 + 1)) > 6) res.outerLength = bx1 - bx0 + 1;

        // ---- 3. end ticks (short strokes across the bar at its ends)
        boolean[] barLike = likeMask(c, w, h, barCol, bg, 0.5);
        double tl = tickCentre(barLike, w, h, top, bot, bx0, true);
        double tr = tickCentre(barLike, w, h, top, bot, bx1, false);
        res.tickLength = (!Double.isNaN(tl) && !Double.isNaN(tr)) ? tr - tl : -1;
        // ruler-type bar (intermediate ticks): its length is measured between the end-tick centres
        if (res.tickLength > 0) {
            int groups = 0;
            boolean prev = false;
            int need = Math.max(2, bot - top + 1);
            for (int x = bx0; x <= bx1; x++) {
                boolean onBar = false;   // skip the gap holding the label
                for (Seg p : parts) if (x >= p.x0 && x <= p.x1) onBar = true;
                if (!onBar) { prev = false; continue; }
                int up = 0, dn = 0;
                for (int y = top - 1; y >= 0 && barLike[y * w + x]; y--) up++;
                for (int y = bot + 1; y < h && barLike[y * w + x]; y++) dn++;
                boolean t = up >= need || dn >= need;
                if (t && !prev) groups++;
                prev = t;
            }
            res.ruler = groups >= 4;
        }
        res.barLength = res.ruler ? res.tickLength : res.outerLength;
        double cx = (left + right) / 2.0;
        res.x1 = r.x + cx - res.barLength / 2.0;
        res.x2 = r.x + cx + res.barLength / 2.0;
        res.y = r.y + (top + bot + 1) / 2.0;

        // pixels belonging to the bar (with ticks), excluded from the label search
        boolean[] barMask = new boolean[w * h];
        int[] stack = new int[w * h];
        int sp = 0;
        for (Seg s : parts)
            for (int y = s.top; y <= s.bot; y++)
                for (int x = s.x0; x <= s.x1; x++) {
                    int i = y * w + x;
                    if (barLike[i] && !barMask[i]) { barMask[i] = true; stack[sp++] = i; }
                }
        boolean[] faint = likeMask(c, w, h, barCol, bg, 0.3);
        while (sp > 0) {
            int p = stack[--sp];
            int px = p % w, py = p / w;
            for (int dy = -1; dy <= 1; dy++)
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = px + dx, ny = py + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int q = ny * w + nx;
                    // stay close to the bar: ticks are short
                    if (Math.abs(ny - (top + bot) / 2) > Math.max(25, 6 * (bot - top + 1))) continue;
                    if (faint[q] && !barMask[q]) { barMask[q] = true; stack[sp++] = q; }
                }
        }
        // dilate by one pixel to remove anti-aliased fringes
        boolean[] bm2 = barMask.clone();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                if (barMask[y * w + x])
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (nx >= 0 && ny >= 0 && nx < w && ny < h) bm2[ny * w + nx] = true;
                        }
        barMask = bm2;

        // ---- 4. label: try bright-on-dark and dark-on-bright text
        Reading best = null;
        for (int pol = 0; pol < 2; pol++) {
            Reading rd = readLabel(L, barMask, w, h, pol == 0, (top + bot) / 2.0);
            if (rd != null && (best == null || better(rd, best))) best = rd;
        }
        if (best != null) {
            res.text = best.text;
            res.confidence = best.cost;
            if (best.cost <= MAX_COST) {
                double[] val = new double[1];
                res.unit = parse(best.text.replace(" ", ""), val);
                res.value = val[0];
                // is the label next to this bar? (above/below within a few text heights, or in its gap)
                double lcx = (best.x0 + best.x1) / 2.0, len = bx1 - bx0 + 1;
                double lh = Math.max(4, best.y1 - best.y0 + 1);
                boolean overX = lcx >= bx0 - 0.25 * len && lcx <= bx1 + 0.25 * len;
                double dy = Math.max(0, Math.max(best.y0 - bot, top - best.y1));
                res.labelNear = res.value > 0 && res.unit != null && overX && dy <= 3 * lh;
                res.labelClose = res.labelNear && dy <= 1.5 * lh;
                if (res.labelNear && parts.size() > 1 && dy <= 0.5 * lh) {
                    for (Seg p : parts)
                        for (Seg q : parts)
                            if (p.x1 < q.x0 && best.x0 > p.x1 && best.x1 < q.x0) res.labelInGap = true;
                }
            }
        }
        return res;
    }

    /** Separable running max (or min) over a (2R+1)^2 window. */
    static float[] rangeFilter(float[] L, int w, int h, int R, boolean max) {
        float[] t = new float[w * h], o = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                float m = L[y * w + x];
                for (int k = Math.max(0, x - R); k <= Math.min(w - 1, x + R); k++) {
                    float f = L[y * w + k];
                    if (max ? f > m : f < m) m = f;
                }
                t[y * w + x] = m;
            }
        for (int x = 0; x < w; x++)
            for (int y = 0; y < h; y++) {
                float m = t[y * w + x];
                for (int k = Math.max(0, y - R); k <= Math.min(h - 1, y + R); k++) {
                    float f = t[k * w + x];
                    if (max ? f > m : f < m) m = f;
                }
                o[y * w + x] = m;
            }
        return o;
    }

    static boolean better(Reading a, Reading b) {
        if (a.text == null) return false;
        if (b.text == null) return true;
        // prefer readings that use more glyphs when the match quality is similar
        if (Math.abs(a.cost - b.cost) <= 0.03) {
            if (a.glyphs != b.glyphs) return a.glyphs > b.glyphs;
            return a.dist < b.dist;
        }
        return a.cost < b.cost;
    }

    static double dist(double[] a, double[] b) {
        double d0 = a[0] - b[0], d1 = a[1] - b[1], d2 = a[2] - b[2];
        return Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }

    static double dist(float r, float g, float b, double[] m) {
        double d0 = r - m[0], d1 = g - m[1], d2 = b - m[2];
        return Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }

    /** Uniform horizontal strokes (bands of rows) that differ from what is above and below them. */
    static List<Seg> findStrokes(float[][] c, int w, int h) {
        List<Seg> out = new ArrayList<Seg>();
        boolean[] used = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                int i0 = y * w + x;
                if (used[i0]) { x++; continue; }
                double[] m = {c[0][i0], c[1][i0], c[2][i0]};
                int n = 1, e = x;
                while (true) {
                    int nx = e + 1;
                    if (nx < w && dist(c[0][y * w + nx], c[1][y * w + nx], c[2][y * w + nx], m) <= TOL) {
                        e = nx;
                    } else if (nx + 1 < w && dist(c[0][y * w + nx + 1], c[1][y * w + nx + 1], c[2][y * w + nx + 1], m) <= TOL) {
                        e = nx + 1;   // tolerate a single outlier pixel
                    } else break;
                    if (n < 64) {     // running mean of the first pixels
                        int j = y * w + e;
                        m[0] = (m[0] * n + c[0][j]) / (n + 1);
                        m[1] = (m[1] * n + c[1][j]) / (n + 1);
                        m[2] = (m[2] * n + c[2][j]) / (n + 1);
                        n++;
                    }
                }
                int len = e - x + 1;
                if (len >= MIN_BAR && !(x <= 1 && e >= w - 2)) {
                    Seg s = makeSeg(c, w, h, y, x, e, m);
                    if (s != null) {
                        out.add(s);
                        for (int yy = s.top; yy <= s.bot; yy++)
                            for (int xx = s.x0; xx <= s.x1; xx++) used[yy * w + xx] = true;
                    }
                }
                x = e + 1;
            }
        }
        return out;
    }

    static double rowFraction(float[][] c, int w, int y, int x0, int x1, double[] m, double tol, boolean within) {
        int n = 0, t = 0;
        for (int x = x0; x <= x1; x++) {
            int i = y * w + x;
            boolean in = dist(c[0][i], c[1][i], c[2][i], m) <= tol;
            if (in == within) n++;
            t++;
        }
        return t == 0 ? 0 : n / (double) t;
    }

    static Seg makeSeg(float[][] c, int w, int h, int y, int x0, int x1, double[] m) {
        int top = y, bot = y;
        while (top - 1 >= 0 && rowFraction(c, w, top - 1, x0, x1, m, TOL, true) >= 0.85) top--;
        while (bot + 1 < h && rowFraction(c, w, bot + 1, x0, x1, m, TOL, true) >= 0.85) bot++;
        int th = bot - top + 1;
        if (th > Math.max(3, 0.05 * (x1 - x0 + 1)) || th > 12) return null;   // an area, not a stroke
        // contrast with the rows just outside the band (2 px away, beyond anti-aliasing)
        int ya = top - 2, yb = bot + 2;
        if (ya < 0 && yb >= h) return null;
        double fa = ya < 0 ? 1 : rowFraction(c, w, ya, x0, x1, m, 2 * TOL, false);
        double fb = yb >= h ? 1 : rowFraction(c, w, yb, x0, x1, m, 2 * TOL, false);
        // clear on one side, mostly clear on the other (ticks may sit on one side)
        if (Math.max(fa, fb) < 0.8 || Math.min(fa, fb) < 0.55) return null;
        Seg s = new Seg();
        s.y = y; s.x0 = x0; s.x1 = x1; s.top = top; s.bot = bot; s.col = m;
        return s;
    }

    static double[] rowMedian(float[][] c, int w, int h, int y, int x0, int x1) {
        if (y < 0 || y >= h) return null;
        double[] out = new double[3];
        for (int k = 0; k < 3; k++) {
            float[] a = new float[x1 - x0 + 1];
            for (int x = x0; x <= x1; x++) a[x - x0] = c[k][y * w + x];
            Arrays.sort(a);
            out[k] = a[a.length / 2];
        }
        return out;
    }

    static double[] outsideColour(float[][] c, int w, int h, int top, int bot, int x0, int x1) {
        List<float[]> px = new ArrayList<float[]>();
        for (int y : new int[]{top - 2, top - 3, bot + 2, bot + 3}) {
            if (y < 0 || y >= h) continue;
            for (int x = x0; x <= x1; x++) {
                int i = y * w + x;
                px.add(new float[]{c[0][i], c[1][i], c[2][i]});
            }
        }
        double[] out = new double[3];
        if (px.isEmpty()) return out;
        for (int k = 0; k < 3; k++) {
            float[] a = new float[px.size()];
            for (int i = 0; i < a.length; i++) a[i] = px.get(i)[k];
            Arrays.sort(a);
            out[k] = a[a.length / 2];
        }
        return out;
    }

    /** 50 % crossing of the normalised profile at the left or right end (pixel-edge coordinates). */
    static double crossing(double[] prof, int wx0, int i, boolean leftEnd, double dflt) {
        if (leftEnd) {
            // walk outwards while above 0.5
            while (i > 0 && prof[i - 1] >= 0.5) i--;
            while (i < prof.length - 1 && prof[i] < 0.5) i++;
            if (i > 0 && prof[i] >= 0.5 && prof[i - 1] < 0.5)
                return wx0 + i - 0.5 + (0.5 - prof[i - 1]) / (prof[i] - prof[i - 1]);
        } else {
            while (i < prof.length - 1 && prof[i + 1] >= 0.5) i++;
            while (i > 0 && prof[i] < 0.5) i--;
            if (i < prof.length - 1 && prof[i] >= 0.5 && prof[i + 1] < 0.5)
                return wx0 + i + 0.5 + (prof[i] - 0.5) / (prof[i] - prof[i + 1]);
        }
        return dflt;
    }

    /** Centre (pixel-edge coordinates) of a tick crossing the bar at its end, or NaN. */
    static double tickCentre(boolean[] barLike, int w, int h, int top, int bot, int end, boolean leftEnd) {
        double[] t = tickInfo(barLike, w, h, top, bot, end, leftEnd);
        return t == null ? Double.NaN : t[0];
    }

    /** {centre, width, height above, height below} of an end tick, or null. */
    static double[] tickInfo(boolean[] barLike, int w, int h, int top, int bot, int end, boolean leftEnd) {
        int th = bot - top + 1;
        int maxUp = 0, maxDn = 0;
        int need = Math.max(2, th);   // tick must stick out at least this far
        int search = Math.max(4, 3 * th + 2);
        int first = -1, last = -1;
        for (int k = 0; k <= search; k++) {
            int x = leftEnd ? end + k : end - k;
            if (x < 0 || x >= w) break;
            int up = 0, dn = 0;
            for (int y = top - 1; y >= 0 && barLike[y * w + x]; y--) up++;
            for (int y = bot + 1; y < h && barLike[y * w + x]; y++) dn++;
            boolean tick = up >= need || dn >= need;
            if (tick) {
                if (first < 0) first = x;
                last = x;
                maxUp = Math.max(maxUp, up);
                maxDn = Math.max(maxDn, dn);
            } else if (first >= 0) break;
        }
        if (first < 0) return null;
        int a = Math.min(first, last), b = Math.max(first, last);
        return new double[]{(a + b + 1) / 2.0, b - a + 1, maxUp, maxDn};
    }

    /** Find and read the label "<number> <unit>" in the rectangle (bar pixels excluded). */
    static Reading readLabel(float[] L, boolean[] barMask, int w, int h, boolean bright, double barY) {
        // local contrast threshold: a pixel is text if it is at least TEXT_THR of the way from the
        // local background (local max for dark text, local min for bright text) to the local extreme
        int R = 7;
        float[] mx = rangeFilter(L, w, h, R, true), mn = rangeFilter(L, w, h, R, false);
        boolean[] fg = new boolean[w * h];
        for (int i = 0; i < L.length; i++) {
            if (barMask[i]) continue;
            double range = mx[i] - mn[i];
            if (range < 0.2) continue;
            fg[i] = bright ? L[i] - mn[i] >= TEXT_THR * range : mx[i] - L[i] >= TEXT_THR * range;
        }
        // estimate the text height, then cut long straight lines (label frames, underlines)
        int[] lab = new int[w * h];
        List<Comp> comps = labelComponents(fg, w, h, lab);
        List<Integer> hts = new ArrayList<Integer>();
        for (int id = 1; id < comps.size(); id++) {
            Comp cc = comps.get(id);
            if (cc.area >= 6 && cc.h() >= 5 && cc.h() < 0.8 * h && cc.w() < 3 * cc.h()) hts.add(cc.h());
        }
        if (hts.isEmpty()) return null;
        java.util.Collections.sort(hts);
        int H = hts.get(hts.size() / 2);
        int longH = Math.max(25, 3 * H);
        boolean[] cut = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int x = 0;
            while (x < w) {
                if (!fg[y * w + x]) { x++; continue; }
                int e = x;
                while (e + 1 < w && fg[y * w + e + 1]) e++;
                if (e - x + 1 >= longH) for (int xx = x; xx <= e; xx++) cut[y * w + xx] = true;
                x = e + 1;
            }
        }
        for (int i = 0; i < fg.length; i++) if (cut[i]) fg[i] = false;
        lab = new int[w * h];
        comps = labelComponents(fg, w, h, lab);
        List<Comp> glyphs = new ArrayList<Comp>(), tiny = new ArrayList<Comp>();
        for (int id = 1; id < comps.size(); id++) {
            Comp cc = comps.get(id);
            if (DEBUG && cc.area >= 2) System.out.println("  comp " + cc.x0 + "-" + cc.x1 + "," + cc.y0 + "-" + cc.y1 + " a=" + cc.area);
            if (cc.area < 2) continue;
            if (cc.area < 3 || cc.h() < 3) { tiny.add(cc); continue; }   // possible decimal point
            if (cc.h() > 0.8 * h || cc.w() > 0.6 * w) continue;
            if (cc.w() > 5 * cc.h() + 3 || cc.h() > 5 * cc.w() + 6) continue;   // remaining line pieces
            if (cc.h() >= 1.5 * H && cc.w() <= 0.35 * cc.h()) continue;           // frame sides, taller than text
            glyphs.add(cc);
        }
        // drop containers (e.g. the frame of a label box)
        List<Comp> keep = new ArrayList<Comp>();
        for (Comp a : glyphs) {
            int inside = 0;
            for (Comp b : glyphs)
                if (b != a && b.x0 >= a.x0 && b.x1 <= a.x1 && b.y0 >= a.y0 && b.y1 <= a.y1) inside++;
            if (inside < 2) keep.add(a);
        }
        Reading best = null;
        for (List<Comp> line : groupLines(keep)) {
            List<Comp> gl = mergeColumns(line);
            // tiny marks lying in a gap between two glyphs, near the baseline: decimal points
            if (!tiny.isEmpty() && gl.size() >= 2) {
                int ly0 = Integer.MAX_VALUE, ly1 = -1;
                for (Comp cc : gl) { ly0 = Math.min(ly0, cc.y0); ly1 = Math.max(ly1, cc.y1); }
                List<Comp> add = new ArrayList<Comp>();
                for (int i = 0; i + 1 < gl.size(); i++) {
                    Comp a = gl.get(i), b = gl.get(i + 1);
                    for (Comp t : tiny)
                        if (t.x0 > a.x1 && t.x1 < b.x0 && t.y0 >= ly0 + 0.55 * (ly1 - ly0) && t.y1 <= ly1 + 1)
                            add.add(t);
                }
                if (!add.isEmpty()) {
                    gl.addAll(add);
                    gl.sort((a, b) -> a.x0 - b.x0);
                }
            }
            if (gl.size() < 2) continue;
            // split the line into chunks at large gaps
            int[] hs = new int[gl.size()];
            for (int i = 0; i < hs.length; i++) hs[i] = gl.get(i).h();
            Arrays.sort(hs);
            double medH = hs[hs.length / 2];
            List<Comp> chunk = new ArrayList<Comp>();
            for (int i = 0; i <= gl.size(); i++) {
                boolean brk = i == gl.size() || (!chunk.isEmpty() && gl.get(i).x0 - chunk.get(chunk.size() - 1).x1 > 1.5 * medH);
                if (brk) {
                    if (chunk.size() >= 2) {
                        Reading rd = recognise(chunk, lab, w);
                        if (DEBUG) {
                            StringBuilder o = new StringBuilder("chunk(" + (bright ? "bright" : "dark") + "):");
                            for (Comp cc : chunk) o.append(" [" + cc.x0 + "-" + cc.x1 + "," + cc.y0 + "-" + cc.y1 + "]");
                            System.out.println(o + " -> " + (rd == null ? null : rd.text + " " + IJ.d2s(rd.cost, 3)));
                        }
                        if (rd != null && rd.text != null) {
                            int ly0 = Integer.MAX_VALUE, ly1 = -1;
                            for (Comp cc : chunk) { ly0 = Math.min(ly0, cc.y0); ly1 = Math.max(ly1, cc.y1); }
                            rd.dist = Math.abs((ly0 + ly1) / 2.0 - barY);
                            if (best == null || better(rd, best)) best = rd;
                        }
                    }
                    chunk = new ArrayList<Comp>();
                }
                if (i < gl.size()) chunk.add(gl.get(i));
            }
        }
        return best;
    }

    static final float TEXT_THR = 0.4f;
    static boolean DEBUG = false, DEBUG_FONTS = false;

    /** 8-connected component labelling; index 0 of the returned list is null (background). */
    static List<Comp> labelComponents(boolean[] fg, int w, int h, int[] lab) {
        List<Comp> comps = new ArrayList<Comp>();
        comps.add(null); // label 0 = background
        int[] stack = new int[w * h];
        for (int i = 0; i < fg.length; i++) {
            if (!fg[i] || lab[i] != 0) continue;
            int id = comps.size();
            Comp c = new Comp();
            c.labels.add(id);
            int sp = 0;
            stack[sp++] = i;
            lab[i] = id;
            while (sp > 0) {
                int p = stack[--sp];
                int px = p % w, py = p / w;
                c.addBox(px, py, px, py, 1);
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = py + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx;
                        if (nx < 0 || nx >= w) continue;
                        int q = ny * w + nx;
                        if (fg[q] && lab[q] == 0) {
                            lab[q] = id;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            comps.add(c);
        }
        return comps;
    }

    static boolean rowCovers(boolean[] fg, int w, int y, int x0, int x1) {
        int n = 0;
        for (int x = x0; x <= x1; x++) if (fg[y * w + x]) n++;
        return n >= 0.9 * (x1 - x0 + 1);
    }

    static double medianRun(float[] v, int w, int top, int bot, int x0, int x1) {
        int m = x1 - x0 + 1;
        int cut = Math.min(m / 4, 6);
        List<Float> vals = new ArrayList<Float>();
        for (int y = top; y <= bot; y++)
            for (int x = x0 + cut; x <= x1 - cut; x++) vals.add(v[y * w + x]);
        if (vals.isEmpty()) return v[top * w + (x0 + x1) / 2];
        float[] a = new float[vals.size()];
        for (int i = 0; i < a.length; i++) a[i] = vals.get(i);
        Arrays.sort(a);
        return a[a.length / 2];
    }

    static double medianBackground(float[] v, boolean[] fg) {
        int n = 0;
        for (boolean b : fg) if (!b) n++;
        float[] a = new float[Math.max(1, n)];
        int k = 0;
        for (int i = 0; i < v.length; i++) if (!fg[i]) a[k++] = v[i];
        Arrays.sort(a, 0, Math.max(1, k));
        return a[Math.max(0, k / 2)];
    }

    static float otsu(float[] v) {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float f : v) { if (f < min) min = f; if (f > max) max = f; }
        if (max <= min) return min;
        int nb = 256;
        int[] hist = new int[nb];
        for (float f : v) {
            int b = (int) ((f - min) / (max - min) * (nb - 1));
            hist[b]++;
        }
        double total = v.length, sumAll = 0;
        for (int i = 0; i < nb; i++) sumAll += i * (double) hist[i];
        double wB = 0, sumB = 0, best = -1;
        int bestT = 0;
        for (int t = 0; t < nb; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            double wF = total - wB;
            if (wF == 0) break;
            sumB += t * (double) hist[t];
            double mB = sumB / wB, mF = (sumAll - sumB) / wF;
            double between = wB * wF * (mB - mF) * (mB - mF);
            if (between > best) { best = between; bestT = t; }
        }
        return min + (bestT + 0.5f) / (nb - 1) * (max - min);
    }

    static List<List<Comp>> groupLines(List<Comp> comps) {
        List<Comp> sorted = new ArrayList<Comp>(comps);
        // tallest first so lines are seeded by full-height glyphs
        sorted.sort((a, b) -> b.h() - a.h());
        List<List<Comp>> lines = new ArrayList<List<Comp>>();
        List<int[]> spans = new ArrayList<int[]>();
        for (Comp c : sorted) {
            int found = -1;
            double bestOv = 0;
            for (int i = 0; i < lines.size(); i++) {
                int[] s = spans.get(i);
                int H = s[1] - s[0] + 1;
                int ov = Math.min(s[1], c.y1) - Math.max(s[0], c.y0) + 1;
                double f = ov / (double) c.h();
                // glyphs of a comparable size, mostly inside the line's vertical span
                if (c.h() >= 0.45 * H && f >= 0.6 && f > bestOv) { found = i; bestOv = f; }
                // small marks (decimal point, comma) inside the lower part of the line
                if (found != i && c.h() <= 0.35 * H && c.y0 >= s[0] + 0.4 * H && c.y1 <= s[1] + 0.35 * H
                        && bestOv < 0.5) { found = i; bestOv = 0.5; }
            }
            if (found < 0) {
                List<Comp> l = new ArrayList<Comp>();
                l.add(c);
                lines.add(l);
                spans.add(new int[]{c.y0, c.y1});
            } else {
                lines.get(found).add(c);
            }
        }
        return lines;
    }

    static List<Comp> mergeColumns(List<Comp> line) {
        List<Comp> s = new ArrayList<Comp>(line);
        s.sort((a, b) -> a.x0 - b.x0);
        List<Comp> out = new ArrayList<Comp>();
        for (Comp c : s) {
            Comp last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null) {
                int ov = Math.min(last.x1, c.x1) - Math.max(last.x0, c.x0) + 1;
                if (ov > 0 && ov >= 0.5 * Math.min(last.w(), c.w())) {
                    Comp m = new Comp();
                    m.addBox(last.x0, last.y0, last.x1, last.y1, last.area);
                    m.addBox(c.x0, c.y0, c.x1, c.y1, c.area);
                    m.labels.addAll(last.labels);
                    m.labels.addAll(c.labels);
                    out.set(out.size() - 1, m);
                    continue;
                }
            }
            out.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ recognition

    static int GW = 10, GH = 14;
    static double W_GEOM = 0.35, W_AR = 0.08;
    static boolean BLUR = false;
    static double DEAD = 0.1;
    static double SPLIT_PENALTY = 0.05;
    static double MAX_COST = 0.3;
    static Set<String> EXCLUDE_FONTS = new HashSet<String>();   // for testing

    /** Glyph descriptor: normalised bitmap + geometry relative to the text line. */
    static class Desc {
        float[] grid;
        double relTop, relBot, logAR;
    }

    static float[] gridFrom(boolean[] m, int mw, int mh) {
        float[] g = new float[GW * GH];
        for (int gy = 0; gy < GH; gy++) {
            for (int gx = 0; gx < GW; gx++) {
                double xa = gx * (double) mw / GW, xb = (gx + 1) * (double) mw / GW;
                double ya = gy * (double) mh / GH, yb = (gy + 1) * (double) mh / GH;
                double s = 0, a = 0;
                for (int y = (int) Math.floor(ya); y < Math.ceil(yb) && y < mh; y++) {
                    double fy = Math.min(yb, y + 1) - Math.max(ya, y);
                    for (int x = (int) Math.floor(xa); x < Math.ceil(xb) && x < mw; x++) {
                        double fx = Math.min(xb, x + 1) - Math.max(xa, x);
                        double f = fx * fy;
                        a += f;
                        if (m[y * mw + x]) s += f;
                    }
                }
                g[gy * GW + gx] = a > 0 ? (float) (s / a) : 0f;
            }
        }
        if (BLUR) {
            float[] b = new float[g.length];
            for (int gy = 0; gy < GH; gy++)
                for (int gx = 0; gx < GW; gx++) {
                    float sum = 0, wsum = 0;
                    for (int dy = -1; dy <= 1; dy++)
                        for (int dx = -1; dx <= 1; dx++) {
                            int yy = gy + dy, xx = gx + dx;
                            if (yy < 0 || yy >= GH || xx < 0 || xx >= GW) continue;
                            float wt = (dx == 0 && dy == 0) ? 4 : (dx == 0 || dy == 0) ? 2 : 1;
                            sum += wt * g[yy * GW + xx];
                            wsum += wt;
                        }
                    b[gy * GW + gx] = sum / wsum;
                }
            g = b;
        }
        return g;
    }

    static Reading recognise(List<Comp> gl, int[] lab, int w) {
        // line metrics: baseline = median bottom, cap top = highest top of glyphs sitting on the baseline
        int[] sb = new int[gl.size()];
        for (int i = 0; i < gl.size(); i++) sb[i] = gl.get(i).y1 + 1;
        Arrays.sort(sb);
        int baseline = sb[sb.length / 2];
        int maxH = 0;
        for (Comp c : gl) maxH = Math.max(maxH, c.h());
        int capTop = Integer.MAX_VALUE;
        for (Comp c : gl)
            if (Math.abs(c.y1 + 1 - baseline) <= Math.max(1, 0.15 * maxH)) capTop = Math.min(capTop, c.y0);
        if (capTop == Integer.MAX_VALUE) capTop = baseline - maxH;
        double capH = Math.max(1, baseline - capTop);
        Templates tpl = Templates.get((int) Math.round(capH));

        gl = splitTouching(gl, lab, w, capTop, capH, tpl);
        List<Desc> descs = new ArrayList<Desc>();
        for (Comp c : gl) descs.add(describe(c, lab, w, capTop, capH));

        // for every font: score matrix, then decode "<number><unit>" with a small grammar on every
        // word-aligned run of glyphs; keep the font/reading with the lowest cost
        int n = gl.size();
        boolean[] startOk = new boolean[n], endOk = new boolean[n];
        for (int i = 0; i < n; i++) {
            startOk[i] = i == 0 || gl.get(i).x0 - gl.get(i - 1).x1 - 1 >= 0.4 * capH;
            endOk[i] = i == n - 1 || gl.get(i + 1).x0 - gl.get(i).x1 - 1 >= 0.4 * capH;
        }
        Reading best = new Reading();
        for (Templates.FontSet fs : tpl.fonts) {
            double[][] S = new double[n][CHARS.length()];
            StringBuilder raw = new StringBuilder();
            for (int g = 0; g < n; g++) {
                int bk = 0;
                for (int k = 0; k < CHARS.length(); k++) {
                    S[g][k] = score(descs.get(g), fs.descs[k]);
                    if (S[g][k] < S[g][bk]) bk = k;
                }
                raw.append(CHARS.charAt(bk));
            }
            for (int i = 0; i < n; i++) {
                if (!startOk[i]) continue;
                for (int j = i + 1; j < n && j - i < 12; j++) {
                    if (!endOk[j]) continue;
                    Reading r = decode(Arrays.copyOfRange(S, i, j + 1));
                    if (r.text == null) continue;
                    r.raw = raw.toString();
                    r.glyphs = j - i + 1;
                    r.x0 = gl.get(i).x0; r.x1 = gl.get(j).x1;
                    r.y0 = Integer.MAX_VALUE; r.y1 = -1;
                    for (int g = i; g <= j; g++) { r.y0 = Math.min(r.y0, gl.get(g).y0); r.y1 = Math.max(r.y1, gl.get(g).y1); }
                    if (better(r, best)) best = r;
                }
            }
            if (DEBUG && DEBUG_FONTS) System.out.println(fs.name + " raw=" + raw + " -> " + best.text + " cost=" + IJ.d2s(best.cost, 4));
        }
        return best;
    }

    static class Reading {
        String raw = "", text = null;
        double cost = Double.MAX_VALUE;   // average template distance per glyph
        int glyphs;                       // number of glyphs used by the reading
        int x0, x1, y0, y1;               // bounding box of the reading (rectangle coordinates)
        double dist;                      // vertical distance of the text line from the bar
    }

    /** Units with a small prior cost (rarely used units must match clearly better). */
    static final String[] DEC_UNITS = {"nm", "\u00B5m", "um", "mm", "m", "cm", "pm"};
    static final double[] DEC_PRIOR = {0, 0, 0.03, 0, 0.08, 0.08, 0.08};

    /** Best reading of the form digits[(.|,)digits]unit for a glyph-by-character score matrix. */
    static Reading decode(double[][] S) {
        int n = S.length;
        Reading best = new Reading();
        if (n < 2) return best;
        int kDot = CHARS.indexOf('.'), kComma = CHARS.indexOf(',');
        for (int ui = 0; ui < DEC_UNITS.length; ui++) {
            String u = DEC_UNITS[ui];
            int L = u.length(), M = n - L;
            if (M < 1 || M > 5) continue;   // scale bar values have at most 4 digits (+ separator)
            double uc = DEC_PRIOR[ui];
            for (int i = 0; i < L; i++) uc += S[M + i][CHARS.indexOf(u.charAt(i))];
            // number part: optional separator at position sep (0 = none)
            for (int sep = 0; sep <= Math.max(0, M - 2); sep++) {
                StringBuilder num = new StringBuilder();
                double nc = 0;
                for (int g = 0; g < M; g++) {
                    if (sep > 0 && g == sep) {
                        boolean dot = S[g][kDot] <= S[g][kComma];
                        nc += Math.min(S[g][kDot], S[g][kComma]);
                        num.append(dot ? '.' : ',');
                        continue;
                    }
                    // a leading zero is only allowed directly before the separator ("0.5")
                    boolean noZero = g == 0 && (M == 1 || sep != 1);
                    int bd = noZero ? 1 : 0;
                    for (int d = 1; d <= 9; d++) if (S[g][d] < S[g][bd]) bd = d;
                    nc += S[g][bd];
                    num.append((char) ('0' + bd));
                }
                double cost = (nc + uc) / n;
                if (cost < best.cost) {
                    best.cost = cost;
                    best.text = num + " " + (u.equals("um") ? "\u00B5m" : u);
                }
                if (M < 3) break;   // a separator needs digits on both sides
            }
        }
        return best;
    }

    static Desc describe(Comp c, int[] lab, int w, int capTop, double capH) {
        boolean[] m = new boolean[c.w() * c.h()];
        for (int y = c.y0; y <= c.y1; y++)
            for (int x = c.x0; x <= c.x1; x++)
                m[(y - c.y0) * c.w() + (x - c.x0)] = c.labels.contains(lab[y * w + x])
                        && x >= c.clipX0 && x <= c.clipX1;
        Desc d = new Desc();
        d.grid = gridFrom(m, c.w(), c.h());
        d.relTop = (c.y0 - capTop) / capH;
        d.relBot = (c.y1 + 1 - capTop) / capH;
        d.logAR = Math.log(c.w() / (double) c.h());
        return d;
    }

    /** Best score of a descriptor over all templates of all fonts. */
    static double bestScore(Desc d, Templates tpl) {
        double bs = Double.MAX_VALUE;
        for (Templates.FontSet fs : tpl.fonts)
            for (Desc t : fs.descs) bs = Math.min(bs, score(d, t));
        return bs;
    }

    /**
     * Glyphs wider than any single character are touching characters (e.g. "00", "300" or "µm" at
     * small sizes). Find the best segmentation into 1..4 pieces (cuts at low-ink columns) by
     * dynamic programming over the template scores; keep a split only if it matches clearly better.
     */
    static List<Comp> splitTouching(List<Comp> gl, int[] lab, int w, int capTop, double capH, Templates tpl) {
        List<Comp> out = new ArrayList<Comp>();
        for (Comp c : gl) {
            if (c.w() < 0.9 * capH) { out.add(c); continue; }
            int cw = c.w();
            int[] col = new int[cw];
            for (int y = c.y0; y <= c.y1; y++)
                for (int x = c.x0; x <= c.x1; x++)
                    if (c.labels.contains(lab[y * w + x])) col[x - c.x0]++;
            // candidate cut positions: boundaries before column i (1..cw-1) at local ink minima
            List<Integer> cuts = new ArrayList<Integer>();
            cuts.add(0);
            for (int i = 1; i < cw; i++) {
                int l = col[i - 1], r = col[i];
                int ll = i >= 2 ? col[i - 2] : Integer.MAX_VALUE, rr = i + 1 < cw ? col[i + 1] : Integer.MAX_VALUE;
                int m = Math.min(l, r);
                if (cw <= 48 || (m <= Math.min(ll, rr) && m <= 0.5 * c.h())) cuts.add(i);
            }
            cuts.add(cw);
            int n = cuts.size();
            int minW = Math.max(2, (int) (0.12 * capH));
            double[][] pieceScore = new double[n][n];
            Comp[][] pieces = new Comp[n][n];
            for (int a = 0; a < n; a++)
                for (int b = a + 1; b < n; b++) {
                    pieceScore[a][b] = Double.MAX_VALUE;
                    int x0 = cuts.get(a), x1 = cuts.get(b) - 1;
                    if (x1 - x0 + 1 < minW || x1 - x0 + 1 > 1.45 * capH) continue;
                    Comp p = new Comp();
                    p.labels.addAll(c.labels);
                    for (int y = c.y0; y <= c.y1; y++)
                        for (int x = c.x0 + x0; x <= c.x0 + x1; x++)
                            if (c.labels.contains(lab[y * w + x])) p.addBox(x, y, x, y, 1);
                    if (p.area == 0) continue;
                    p.clipX0 = c.x0 + x0;
                    p.clipX1 = c.x0 + x1;
                    pieces[a][b] = p;
                    pieceScore[a][b] = bestScore(describe(p, lab, w, capTop, capH), tpl);
                }
            double whole = bestScore(describe(c, lab, w, capTop, capH), tpl);
            // dp[k][j]: best total score covering cuts[0..j] with k pieces
            int K = 4;
            double[][] dp = new double[K + 1][n];
            int[][] from = new int[K + 1][n];
            for (double[] row : dp) Arrays.fill(row, Double.MAX_VALUE);
            dp[0][0] = 0;
            for (int k = 1; k <= K; k++)
                for (int j = 1; j < n; j++)
                    for (int i = 0; i < j; i++) {
                        if (dp[k - 1][i] == Double.MAX_VALUE || pieceScore[i][j] == Double.MAX_VALUE) continue;
                        double v = dp[k - 1][i] + pieceScore[i][j];
                        if (v < dp[k][j]) { dp[k][j] = v; from[k][j] = i; }
                    }
            // a cap-height glyph this wide cannot be a single character (digits are narrower)
            boolean capGlyph = (c.y0 - capTop) < 0.15 * capH && c.w() > 0.95 * capH;
            int bestK = 1;
            double bestTot = capGlyph ? Double.MAX_VALUE / 2 : whole + SPLIT_PENALTY;
            for (int k = 2; k <= K; k++) {
                if (dp[k][n - 1] == Double.MAX_VALUE) continue;
                double tot = dp[k][n - 1] + SPLIT_PENALTY * k;
                if (tot < bestTot) { bestTot = tot; bestK = k; }
            }
            if (bestK == 1) { out.add(c); continue; }
            List<Comp> parts = new ArrayList<Comp>();
            int j = n - 1;
            for (int k = bestK; k >= 1; k--) {
                int i = from[k][j];
                parts.add(0, pieces[i][j]);
                j = i;
            }
            out.addAll(parts);
        }
        return out;
    }

    static double score(Desc a, Desc b) {
        double mse = 0;
        for (int i = 0; i < a.grid.length; i++) {
            double d = a.grid[i] - b.grid[i];
            mse += d * d;
        }
        mse /= a.grid.length;
        // small dead zone: 1-pixel differences from font hinting should not decide the match
        double dt = Math.max(0, Math.abs(a.relTop - b.relTop) - DEAD);
        double db = Math.max(0, Math.abs(a.relBot - b.relBot) - DEAD);
        return mse + W_GEOM * (dt + db) + W_AR * Math.abs(a.logAR - b.logAR);
    }

    static final Pattern NUM_UNIT = Pattern.compile("^([0-9]+(?:[.,][0-9]+)?)([µunmpc]*)$");

    /** Parse "10µm" etc.; returns the unit (or null) and stores the value in out[0]. */
    static String parse(String txt, double[] out) {
        out[0] = -1;
        Matcher m = NUM_UNIT.matcher(txt);
        if (!m.matches()) {
            // tolerate a leading/trailing stray mark
            Matcher m2 = Pattern.compile("([0-9]+(?:[.,][0-9]+)?)([µunmpc]+)").matcher(txt);
            if (!m2.find()) return null;
            m = m2;
        }
        try {
            out[0] = Double.parseDouble(m.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
        String u = m.group(2);
        if (u.equals("µm") || u.equals("um") || u.equals("µ") || u.equals("u")) return "µm";
        if (u.equals("nm") || u.equals("mm") || u.equals("m") || u.equals("pm") || u.equals("cm")) return u;
        return null;
    }

    // ------------------------------------------------------------------ templates

    static class Templates {
        static final java.util.Map<Integer, Templates> cache = new java.util.HashMap<Integer, Templates>();
        final List<FontSet> fonts = new ArrayList<FontSet>();

        static class FontSet {
            String name;
            Desc[] descs = new Desc[CHARS.length()];
        }

        /** Templates rendered so that the digit height equals capH pixels (like the image text). */
        static synchronized Templates get(int capH) {
            capH = Math.max(5, Math.min(capH, 80));
            Templates t = cache.get(capH);
            if (t == null) {
                t = new Templates(capH);
                cache.put(capH, t);
            }
            return t;
        }

        Templates(int capH) {
            String[] wanted = {"Arial", "Helvetica", "Liberation Sans", "Segoe UI", "Tahoma", "Verdana",
                    "Calibri", "Microsoft Sans Serif", "DejaVu Sans", "FreeSans", "Inter", "Carlito",
                    "Times New Roman", "Liberation Serif", "DejaVu Serif", "Courier New", "Consolas",
                    "Lucida Console", "DejaVu Sans Mono", "SansSerif", "Serif", "Monospaced"};
            Set<String> avail = new HashSet<String>();
            try {
                for (String f : GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames())
                    avail.add(f.toLowerCase(Locale.ROOT));
            } catch (Throwable t) { /* headless */ }
            for (String name : wanted) {
                boolean logical = name.equals("SansSerif") || name.equals("Serif") || name.equals("Monospaced");
                if (!logical && !avail.contains(name.toLowerCase(Locale.ROOT))) continue;
                if (EXCLUDE_FONTS.contains(name)) continue;
                for (int style : new int[]{Font.PLAIN, Font.BOLD}) {
                    Font f48 = new Font(name, style, 48);
                    int[] z = ink(f48, '0', 120, 90, 0.5f);
                    if (z == null) continue;
                    float size = 48f * capH / (z[3] - z[1] + 1);
                    FontSet fs = render(f48.deriveFont(size), (int) Math.ceil(size * 2.5));
                    if (fs != null) fonts.add(fs);
                }
            }
        }

        static FontSet render(Font font, int S) {
            int base = (int) (S * 0.7);
            FontSet fs = new FontSet();
            fs.name = font.getName() + (font.isBold() ? " Bold" : "");
            // cap height from the digit "0"
            int[] zero = ink(font, '0', S, base, TEXT_THR);
            if (zero == null) return null;
            int capTop = zero[1];
            double capH = base - capTop;
            for (int k = 0; k < CHARS.length(); k++) {
                char ch = CHARS.charAt(k);
                BufferedImage bi = draw(font, ch, S, base);
                int[] b = bbox(bi, TEXT_THR);
                if (b == null) return null;
                int mw = b[2] - b[0] + 1, mh = b[3] - b[1] + 1;
                boolean[] m = new boolean[mw * mh];
                for (int y = 0; y < mh; y++)
                    for (int x = 0; x < mw; x++)
                        m[y * mw + x] = (bi.getRGB(b[0] + x, b[1] + y) & 0xff) >= TEXT_THR * 255;
                Desc d = new Desc();
                d.grid = gridFrom(m, mw, mh);
                d.relTop = (b[1] - capTop) / capH;
                d.relBot = (b[3] + 1 - capTop) / capH;
                d.logAR = Math.log(mw / (double) mh);
                fs.descs[k] = d;
            }
            return fs;
        }

        static BufferedImage draw(Font font, char ch, int S, int base) {
            BufferedImage bi = new BufferedImage(S, S, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = bi.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.white);
            g.setFont(font);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.drawString(String.valueOf(ch), S / 6f, base);
            g.dispose();
            return bi;
        }

        static int[] ink(Font font, char ch, int S, int base, float thr) {
            return bbox(draw(font, ch, S, base), thr);
        }

        static int[] bbox(BufferedImage bi, float thr) {
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
            for (int y = 0; y < bi.getHeight(); y++)
                for (int x = 0; x < bi.getWidth(); x++)
                    if ((bi.getRGB(x, y) & 0xff) >= thr * 255) {
                        x0 = Math.min(x0, x); y0 = Math.min(y0, y);
                        x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                    }
            return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
        }
    }
}
