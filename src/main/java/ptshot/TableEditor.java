package ptshot;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** Spreadsheet-style editor for a TABLE layer: edit cells, add / remove rows and columns, insert "..." rows. */
final class TableEditor extends JPanel {
    private final Runnable onChange;
    private Block blk;
    private final JTable table = new JTable();
    private final Model model = new Model();

    private final class Model extends AbstractTableModel {
        int cols() {
            int n = 0;
            if (blk != null) for (List<String> r : blk.rows) n = Math.max(n, r.size());
            return n;
        }

        public int getRowCount() {
            return blk == null ? 0 : blk.rows.size();
        }

        public int getColumnCount() {
            return cols();
        }

        @Override
        public String getColumnName(int c) {
            return String.valueOf(c + 1);
        }

        public Object getValueAt(int r, int c) {
            List<String> row = blk.rows.get(r);
            return c < row.size() ? row.get(c) : "";
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return true;
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            List<String> row = blk.rows.get(r);
            while (row.size() <= c) row.add("");
            row.set(c, v == null ? "" : v.toString());
            fireTableCellUpdated(r, c);
            onChange.run();
        }
    }

    TableEditor(Runnable onChange) {
        super(new BorderLayout(0, 4));
        this.onChange = onChange;
        table.setModel(model);
        table.setCellSelectionEnabled(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(Math.max(22, table.getRowHeight()));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                comp.setFont(t.getFont().deriveFont(r == 0 ? Font.BOLD : Font.PLAIN));
                return comp;
            }
        });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        bar.add(btn("+ Row", "Insert a row below the selected one", e -> addRow(false)));
        bar.add(btn("… Row", "Insert a row of … below the selected one (shows that a series of requests was skipped)", e -> addRow(true)));
        bar.add(btn("+ Column", "Insert a column to the right of the selected one", e -> addCol()));
        bar.add(btn("− Row", "Delete the selected row(s)", e -> delRows()));
        bar.add(btn("− Column", "Delete the selected column(s)", e -> delCols()));
        JLabel hint = new JLabel("  First row = header. Double-click a cell to edit.");
        hint.setFont(hint.getFont().deriveFont(Font.ITALIC));
        bar.add(hint);
        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
    }

    JTable table() {
        return table;
    }

    private static JButton btn(String t, String tip, java.awt.event.ActionListener l) {
        JButton b = new JButton(t);
        b.setToolTipText(tip);
        b.setMargin(new Insets(2, 6, 2, 6));
        b.setFocusable(false);
        b.addActionListener(l);
        return b;
    }

    void load(Block b) {
        stopEditing();
        blk = b;
        model.fireTableStructureChanged();
        for (int c = 0; c < table.getColumnCount(); c++) table.getColumnModel().getColumn(c).setPreferredWidth(110);
    }

    private void stopEditing() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
    }

    /** Selected cells as {row0, col0, row1, col1}, or null if nothing is selected. */
    int[] selection() {
        int[] rs = table.getSelectedRows(), cs = table.getSelectedColumns();
        if (rs.length == 0 || cs.length == 0) return null;
        return new int[]{rs[0], cs[0], rs[rs.length - 1], cs[cs.length - 1]};
    }

    private int selRow() {
        int[] rs = table.getSelectedRows();
        return rs.length == 0 ? blk.rows.size() - 1 : rs[rs.length - 1];
    }

    private void addRow(boolean ellipsis) {
        if (blk == null) return;
        stopEditing();
        int n = Math.max(1, model.cols());
        List<String> row = new ArrayList<>();
        for (int i = 0; i < n; i++) row.add(ellipsis ? "…" : "");
        int at = Math.min(blk.rows.size(), selRow() + 1);
        blk.rows.add(at, row);
        model.fireTableStructureChanged();
        table.setRowSelectionInterval(at, at);
        onChange.run();
    }

    private void addCol() {
        if (blk == null) return;
        stopEditing();
        int[] cs = table.getSelectedColumns();
        int at = cs.length == 0 ? model.cols() : cs[cs.length - 1] + 1;
        for (int r = 0; r < blk.rows.size(); r++) {
            List<String> row = blk.rows.get(r);
            while (row.size() < model.cols()) row.add("");
            row.add(Math.min(at, row.size()), r == 0 ? "Column" : "");
        }
        model.fireTableStructureChanged();
        onChange.run();
    }

    private void delRows() {
        if (blk == null) return;
        stopEditing();
        int[] rs = table.getSelectedRows();
        if (rs.length == 0 || rs.length >= blk.rows.size()) return; // keep at least one row
        for (int i = rs.length - 1; i >= 0; i--) blk.rows.remove(rs[i]);
        model.fireTableStructureChanged();
        onChange.run();
    }

    private void delCols() {
        if (blk == null) return;
        stopEditing();
        int[] cs = table.getSelectedColumns();
        if (cs.length == 0 || cs.length >= model.cols()) return; // keep at least one column
        for (List<String> row : blk.rows) {
            for (int i = cs.length - 1; i >= 0; i--) if (cs[i] < row.size()) row.remove(cs[i]);
        }
        model.fireTableStructureChanged();
        onChange.run();
    }
}
