package ptshot;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;

/** Find bar for the content editor: highlights every match, steps with Enter / Shift+Enter, optionally across all layers. */
final class SearchBar extends JPanel {
    /** Lets the bar continue into the next / previous layer that contains a match. */
    interface Hooks {
        /** Selects the next (dir &gt; 0) or previous layer with a match for p; false if there is none other than the current one. */
        boolean jumpLayer(int dir, Pattern p);

        /** True if the current layer shows only a window of a long body (then the search can cover the whole body). */
        default boolean windowed() {
            return false;
        }

        /** The whole body of the current layer. */
        default String body() {
            return "";
        }

        /** Body offset of an editor position. */
        default int bodyOffset(int editorPos) {
            return editorPos;
        }

        /** Moves the window so that body range [s, e) is visible and selects it. */
        default void showBodyMatch(int s, int e) {
        }
    }

    private final JTextComponent ta;
    private final Hooks hooks;
    private final JTextField field = new JTextField(14);
    private final JCheckBox matchCase = new JCheckBox("Aa"), regex = new JCheckBox(".*"), allLayers = new JCheckBox("All layers"),
            wholeBody = new JCheckBox("Whole body", true);
    private final JLabel count = new JLabel(" ");
    private final Highlighter.HighlightPainter all = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 235, 59, 150));
    private final Highlighter.HighlightPainter current = new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 152, 0, 190));
    private final List<Object> tags = new ArrayList<>();
    private Object curTag;
    private final Color okBg;

    SearchBar(JTextComponent ta, Hooks hooks) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 2));
        this.ta = ta;
        this.hooks = hooks;
        okBg = field.getBackground();
        field.setToolTipText("Find in this layer (Ctrl+F). Enter = next, Shift+Enter = previous, Esc = back to the editor");
        matchCase.setToolTipText("Match case");
        regex.setToolTipText("Regular expression");
        allLayers.setToolTipText("Continue into the other layers (request, response, ...) when this one has no more matches");
        wholeBody.setToolTipText("The body is shown through a window: search the whole body (on) or only the part currently shown (off)");
        wholeBody.setVisible(false);
        for (JCheckBox c : new JCheckBox[]{matchCase, regex, allLayers, wholeBody}) {
            c.setFocusable(false);
            c.setMargin(new Insets(0, 2, 0, 2));
            c.addActionListener(e -> live());
        }
        JButton prev = btn("▲", "Previous match (Shift+Enter)", -1), next = btn("▼", "Next match (Enter)", 1);
        add(new JLabel("Find"));
        add(field);
        add(prev);
        add(next);
        add(count);
        add(matchCase);
        add(regex);
        add(allLayers);
        add(wholeBody);
        setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
        field.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                live();
            }

            public void removeUpdate(DocumentEvent e) {
                live();
            }

            public void changedUpdate(DocumentEvent e) {
                live();
            }
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    step(e.isShiftDown() ? -1 : 1);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    ta.requestFocusInWindow();
                    e.consume();
                }
            }
        });
        // the editor text changes (typing, layer switch, filters): keep the highlights in step
        ta.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) {
                later();
            }

            public void removeUpdate(DocumentEvent e) {
                later();
            }

            public void changedUpdate(DocumentEvent e) {
            }

            void later() {
                SwingUtilities.invokeLater(() -> refresh());
            }
        });
    }

    private JButton btn(String t, String tip, int dir) {
        JButton b = new JButton(t);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setMargin(new Insets(1, 6, 1, 6));
        b.addActionListener(e -> step(dir));
        return b;
    }

    /** Ctrl+F: focus the field, pre-filled with a one-line selection of the editor. */
    void focusField() {
        String sel = ta.getSelectedText();
        if (sel != null && !sel.isEmpty() && sel.indexOf('\n') < 0) field.setText(sel);
        field.requestFocusInWindow();
        field.selectAll();
    }

    private Pattern pattern() {
        String q = field.getText();
        if (q.isEmpty()) {
            field.setBackground(okBg);
            return null;
        }
        try {
            int flags = matchCase.isSelected() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            Pattern p = regex.isSelected() ? Pattern.compile(q, flags | Pattern.MULTILINE) : Pattern.compile(Pattern.quote(q), flags);
            field.setBackground(okBg);
            return p;
        } catch (PatternSyntaxException e) {
            field.setBackground(new Color(255, 190, 190));
            return null;
        }
    }

    static List<int[]> matches(Pattern p, String text) {
        List<int[]> out = new ArrayList<>();
        java.util.regex.Matcher m = p.matcher(text);
        while (m.find() && out.size() < 5000) {
            if (m.end() == m.start()) {
                if (m.end() >= text.length()) break;
                continue; // empty regex matches are useless to show
            }
            out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    private void clearHighlights() {
        Highlighter h = ta.getHighlighter();
        for (Object t : tags) h.removeHighlight(t);
        tags.clear();
        if (curTag != null) h.removeHighlight(curTag);
        curTag = null;
    }

    /** Rebuilds the highlights and the counter for the current text; does not move the selection. */
    void refresh() {
        clearHighlights();
        wholeBody.setVisible(hooks.windowed());
        Pattern p = pattern();
        if (p == null) {
            count.setText(" ");
            return;
        }
        List<int[]> ms = matches(p, ta.getText());
        Highlighter h = ta.getHighlighter();
        int idx = -1, ss = ta.getSelectionStart(), se = ta.getSelectionEnd();
        try {
            for (int i = 0; i < ms.size(); i++) {
                int[] m = ms.get(i);
                if (m[0] == ss && m[1] == se) {
                    idx = i;
                    curTag = h.addHighlight(m[0], m[1], current);
                } else {
                    tags.add(h.addHighlight(m[0], m[1], all));
                }
            }
        } catch (BadLocationException ignored) {
        }
        if (whole()) { // the counter covers the whole body, the highlights only what is shown
            List<int[]> all = matches(p, hooks.body());
            int cs = hooks.bodyOffset(ta.getSelectionStart()), ce = hooks.bodyOffset(ta.getSelectionEnd()), at = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i)[0] == cs && all.get(i)[1] == ce) at = i;
            count.setText(all.isEmpty() ? "no matches" : (at >= 0 ? (at + 1) + " / " : "") + all.size() + (all.size() >= 5000 ? "+" : "") + " in body");
            return;
        }
        count.setText(ms.isEmpty() ? "no matches" : (idx >= 0 ? (idx + 1) + " / " : "") + ms.size() + (ms.size() >= 5000 ? "+" : ""));
    }

    private boolean whole() {
        return wholeBody.isSelected() && hooks.windowed();
    }

    /** Matches in the coordinates the search works in: the editor text, or the whole body when searching through a window. */
    private List<int[]> found(Pattern p) {
        return matches(p, whole() ? hooks.body() : ta.getText());
    }

    private int selStart() {
        return whole() ? hooks.bodyOffset(ta.getSelectionStart()) : ta.getSelectionStart();
    }

    private int selEnd() {
        return whole() ? hooks.bodyOffset(ta.getSelectionEnd()) : ta.getSelectionEnd();
    }

    private void go(int[] m) {
        if (whole()) {
            hooks.showBodyMatch(m[0], m[1]);
            refresh();
        } else {
            select(m);
        }
    }

    private void select(int[] m) {
        ta.setCaretPosition(m[1]);
        ta.moveCaretPosition(m[0]);
        refresh();
    }

    /** Typing in the field: highlight everything and jump to the first match at or after the cursor. */
    private void live() {
        Pattern p = pattern();
        refresh();
        if (p == null) return;
        List<int[]> ms = found(p);
        if (ms.isEmpty()) return;
        int from = selStart();
        for (int[] m : ms) {
            if (m[0] >= from) {
                go(m);
                return;
            }
        }
        go(ms.get(0));
    }

    private void step(int dir) {
        Pattern p = pattern();
        if (p == null) return;
        List<int[]> ms = found(p);
        boolean cross = allLayers.isSelected();
        if (ms.isEmpty()) {
            if (cross && hooks.jumpLayer(dir, p)) edge(dir);
            return;
        }
        int[] pick = null;
        if (dir > 0) {
            int from = selEnd();
            for (int[] m : ms) if (m[0] >= from) { pick = m; break; }
        } else {
            int from = selStart();
            for (int i = ms.size() - 1; i >= 0; i--) if (ms.get(i)[1] <= from) { pick = ms.get(i); break; }
        }
        if (pick == null) { // ran off the end of this layer
            if (cross && hooks.jumpLayer(dir, p)) {
                edge(dir);
                return;
            }
            pick = dir > 0 ? ms.get(0) : ms.get(ms.size() - 1);
        }
        go(pick);
    }

    /** After a jump to another layer: the first match (going forward) or the last one (going back). */
    private void edge(int dir) {
        Pattern p = pattern();
        if (p == null) return;
        List<int[]> ms = found(p);
        if (!ms.isEmpty()) go(dir > 0 ? ms.get(0) : ms.get(ms.size() - 1));
    }
}
