package ptshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Line-based three-way merge. {@code base} is what BurpShot generated before a filter was changed, {@code ours} is the text as the user
 * edited it, {@code theirs} is what BurpShot generates with the new filter. The user's edits and the filter change are both applied; where
 * both touch the same lines the user's version wins (and the conflict is counted), so manual edits are never lost.
 */
final class Merge3 {
    private Merge3() {}

    static final class Result {
        final String text;
        final int conflicts;

        Result(String text, int conflicts) {
            this.text = text;
            this.conflicts = conflicts;
        }
    }

    private static final int MAX_EDITS = 3000;

    private static final class Hunk {
        final int bs, be;      // replaced range of the base lines [bs, be)
        final int[] lines;     // replacement (line ids)

        Hunk(int bs, int be, int[] lines) {
            this.bs = bs;
            this.be = be;
            this.lines = lines;
        }
    }

    /** @return the merged text, or null if the texts differ too much to be compared quickly (the caller then asks the user). */
    static Result merge(String base, String ours, String theirs) {
        if (ours.equals(base)) return new Result(theirs, 0);
        if (theirs.equals(base) || ours.equals(theirs)) return new Result(ours, 0);
        Map<String, Integer> ids = new HashMap<>();
        List<String> names = new ArrayList<>();
        int[] b = ids(base, ids, names), o = ids(ours, ids, names), t = ids(theirs, ids, names);
        List<Hunk> ho = hunks(b, o), ht = hunks(b, t);
        if (ho == null || ht == null) return null;

        int conflicts = 0;
        List<Hunk> keep = new ArrayList<>();
        for (Hunk h : ht) {
            boolean clash = false;
            for (Hunk x : ho) {
                if (overlap(h, x)) {
                    clash = true;
                    break;
                }
            }
            if (clash) conflicts++;
            else keep.add(h);
        }
        // apply both hunk lists to the base in position order (ours first at the same position)
        List<Hunk> all = new ArrayList<>(ho);
        all.addAll(keep);
        java.util.Set<Hunk> mine = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        mine.addAll(ho);
        all.sort((x, y) -> x.bs != y.bs ? Integer.compare(x.bs, y.bs) : (mine.contains(x) ? 0 : 1) - (mine.contains(y) ? 0 : 1));
        StringBuilder out = new StringBuilder();
        boolean first = true;
        int pos = 0;
        for (Hunk h : all) {
            for (; pos < h.bs; pos++) first = add(out, names.get(b[pos]), first);
            for (int id : h.lines) first = add(out, names.get(id), first);
            pos = Math.max(pos, h.be);
        }
        for (; pos < b.length; pos++) first = add(out, names.get(b[pos]), first);
        return new Result(out.toString(), conflicts);
    }

    private static boolean add(StringBuilder o, String line, boolean first) {
        if (!first) o.append('\n');
        o.append(line);
        return false;
    }

    private static boolean overlap(Hunk a, Hunk b) {
        boolean ai = a.bs == a.be, bi = b.bs == b.be;
        if (ai && bi) return false;                              // two insertions at the same place: keep both
        if (ai) return a.bs > b.bs && a.bs < b.be;               // an insertion strictly inside a changed range
        if (bi) return b.bs > a.bs && b.bs < a.be;
        return a.bs < b.be && b.bs < a.be;                       // changed ranges that intersect
    }

    private static int[] ids(String s, Map<String, Integer> ids, List<String> names) {
        String[] lines = s.split("\n", -1);
        int[] r = new int[lines.length];
        for (int i = 0; i < lines.length; i++) {
            Integer id = ids.get(lines[i]);
            if (id == null) {
                id = names.size();
                ids.put(lines[i], id);
                names.add(lines[i]);
            }
            r[i] = id;
        }
        return r;
    }

    /** Changed regions between {@code a} and {@code b} (Myers' O(ND) diff), or null if there are more than MAX_EDITS differences. */
    private static List<Hunk> hunks(int[] a, int[] b) {
        int pre = 0;
        while (pre < a.length && pre < b.length && a[pre] == b[pre]) pre++;
        int suf = 0;
        while (suf < a.length - pre && suf < b.length - pre && a[a.length - 1 - suf] == b[b.length - 1 - suf]) suf++;
        int[] x = java.util.Arrays.copyOfRange(a, pre, a.length - suf), y = java.util.Arrays.copyOfRange(b, pre, b.length - suf);
        List<int[]> match = myers(x, y);
        if (match == null) return null;
        List<Hunk> out = new ArrayList<>();
        int ai = 0, bi = 0;
        match.add(new int[]{x.length, y.length}); // sentinel
        for (int[] m : match) {
            if (m[0] > ai || m[1] > bi) out.add(new Hunk(pre + ai, pre + m[0], java.util.Arrays.copyOfRange(y, bi, m[1])));
            ai = m[0] + 1;
            bi = m[1] + 1;
        }
        return out;
    }

    /** Matching (aIndex, bIndex) pairs of a longest common subsequence, in order; null if the edit distance exceeds MAX_EDITS. */
    private static List<int[]> myers(int[] a, int[] b) {
        int n = a.length, m = b.length;
        List<int[]> res = new ArrayList<>();
        if (n == 0 || m == 0) return res;
        int max = Math.min(n + m, MAX_EDITS), off = max + 1;
        int[] v = new int[2 * max + 3];
        List<int[]> trace = new ArrayList<>();
        int found = -1;
        for (int d = 0; d <= max && found < 0; d++) {
            trace.add(java.util.Arrays.copyOfRange(v, off - d - 1, off + d + 2)); // v at the start of round d, for k in [-d-1, d+1]
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) x = v[off + k + 1];
                else x = v[off + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && a[x] == b[y]) {
                    x++;
                    y++;
                }
                v[off + k] = x;
                if (x >= n && y >= m) {
                    found = d;
                    break;
                }
            }
        }
        if (found < 0) return null;
        int x = n, y = m;
        for (int d = found; d > 0; d--) {
            int[] vd = trace.get(d); // index of k: k + d + 1
            int k = x - y;
            int prevK = (k == -d || (k != d && vd[k - 1 + d + 1] < vd[k + 1 + d + 1])) ? k + 1 : k - 1;
            int prevX = vd[prevK + d + 1], prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                res.add(new int[]{x - 1, y - 1});
                x--;
                y--;
            }
            x = prevX;
            y = prevY;
        }
        while (x > 0 && y > 0) {
            res.add(new int[]{x - 1, y - 1});
            x--;
            y--;
        }
        java.util.Collections.reverse(res);
        return res;
    }
}
