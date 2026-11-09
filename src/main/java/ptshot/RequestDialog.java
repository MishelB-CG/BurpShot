package ptshot;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.UIManager;

/**
 * Shows the HTTP request that Share is about to send - without the file's bytes (they are a single marker line) - and lets the user edit it
 * (generic REST destinations). Secrets are shown as {@code {{secret:KEY}}} tokens unless "Show secrets" is ticked, so the dialog can be
 * screenshotted or screen-shared safely; tokens are filled in when the request is sent.
 */
final class RequestDialog {
    private RequestDialog() {}

    /**
     * @param initial the text to start from (an earlier edit), or null for the generated request
     * @return the request text to send (secrets masked as tokens), or null if the user did not choose to use it
     */
    static String open(Window owner, RemoteClient.RequestPreview prev, Map<String, String> cfg, String initial) {
        final String[] result = {null};
        JDialog d = new JDialog(owner, prev.editable ? "Request to be sent (editable)" : "Request to be sent (read only)", Dialog.ModalityType.APPLICATION_MODAL);
        JTextArea area = new JTextArea(initial != null ? initial : prev.text, 24, 90);
        int fs = Math.max(15, UIManager.getFont("Label.font") == null ? 15 : UIManager.getFont("Label.font").getSize() + 3); // follows Burp's UI scale, never tiny
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, fs));
        area.setEditable(prev.editable);
        area.setLineWrap(false);
        area.setCaretPosition(0);

        StringBuilder help = new StringBuilder();
        if (prev.editable) {
            help.append("This is the request exactly as it will be sent. Edit the request line, headers or body freely. ");
            help.append("The marker line \"").append(RemoteClient.FILE_MARK).append("\" is replaced by the file's bytes (delete it to send no file).");
        }
        if (prev.note != null && !prev.note.isEmpty()) help.append(prev.editable ? " " : "").append(prev.note);
        JTextArea note = Txt.note(help.toString(), true);
        note.setColumns(90);
        note.setRows(4);

        JCheckBox reveal = new JCheckBox("Show secrets");
        reveal.setToolTipText("Replace the {{secret:...}} tokens with the real values (for checking); they are masked again when you use the request");
        reveal.setEnabled(prev.editable);
        reveal.addActionListener(e -> area.setText(reveal.isSelected() ? RemoteClient.revealSecrets(area.getText(), cfg) : RemoteClient.maskSecrets(area.getText(), cfg)));

        JButton reset = new JButton("Reset to generated request");
        reset.setEnabled(prev.editable);
        reset.addActionListener(e -> {
            reveal.setSelected(false);
            area.setText(prev.text);
            area.setCaretPosition(0);
        });
        JButton use = new JButton("Use this request");
        use.setVisible(prev.editable);
        use.addActionListener(e -> {
            result[0] = RemoteClient.maskSecrets(area.getText(), cfg);
            d.dispose();
        });
        JButton cancel = new JButton(prev.editable ? "Cancel" : "Close");
        cancel.addActionListener(e -> d.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(reveal);
        buttons.add(reset);
        buttons.add(use);
        buttons.add(cancel);
        JPanel top = new JPanel(new BorderLayout());
        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
        top.add(note, BorderLayout.CENTER);
        d.getContentPane().add(top, BorderLayout.NORTH);
        JPanel mid = new JPanel(new BorderLayout());
        mid.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        mid.add(new JScrollPane(area), BorderLayout.CENTER);
        d.getContentPane().add(mid, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return result[0];
    }
}
