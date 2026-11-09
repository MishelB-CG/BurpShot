package ptshot;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** "Storage..." : where captures are kept, password encryption, and opening exported files. */
final class StorageDialog {
    private StorageDialog() {}

    // ------------------------------------------------------------------ password prompts

    /** Asks twice for a new password (minimum length enforced); null if cancelled. The caller wipes the array. */
    static char[] askNewPassword(Component parent, String title) {
        JPasswordField p1 = new JPasswordField(24), p2 = new JPasswordField(24);
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        panel.add(new JLabel("Choose a password for the captures (at least " + Vault.MIN_PASSWORD + " characters; a long passphrase is better)."), c);
        c.gridy = 1;
        panel.add(new JLabel("It is never stored. If you forget it, the encrypted captures cannot be recovered."), c);
        c.gridwidth = 1;
        c.gridy = 2;
        panel.add(new JLabel("Password"), c);
        c.gridx = 1;
        panel.add(p1, c);
        c.gridx = 0;
        c.gridy = 3;
        panel.add(new JLabel("Repeat"), c);
        c.gridx = 1;
        panel.add(p2, c);
        while (true) {
            int r = JOptionPane.showConfirmDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) {
                p1.setText("");
                p2.setText("");
                return null;
            }
            char[] a = p1.getPassword(), b = p2.getPassword();
            boolean same = Arrays.equals(a, b);
            Arrays.fill(b, '\0');
            if (a.length < Vault.MIN_PASSWORD) {
                JOptionPane.showMessageDialog(parent, "The password must have at least " + Vault.MIN_PASSWORD + " characters.");
            } else if (!same) {
                JOptionPane.showMessageDialog(parent, "The two passwords are not the same.");
            } else {
                p1.setText("");
                p2.setText("");
                return a;
            }
            Arrays.fill(a, '\0');
        }
    }

    /** Asks for an existing password; null if cancelled. The caller wipes the array. */
    static char[] askPassword(Component parent, String message) {
        JPasswordField pw = new JPasswordField(24);
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.add(new JLabel(message), BorderLayout.NORTH);
        panel.add(pw, BorderLayout.CENTER);
        int r = JOptionPane.showConfirmDialog(parent, panel, "BurpShot password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        char[] out = r == JOptionPane.OK_OPTION ? pw.getPassword() : null;
        pw.setText("");
        return out;
    }

    /** The yellow bar's button: unlock saved encrypted captures, or (nothing saved yet) set the password. */
    static void promptPassword(MainPanel owner, Persist store) {
        Component parent = owner;
        if (store.lockedCount() > 0) {
            char[] pw = askPassword(parent, "Password to unlock " + store.lockedCount() + " encrypted capture(s):");
            if (pw == null) return;
            owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            store.unlock(pw, err -> {
                owner.setCursor(Cursor.getDefaultCursor());
                if (err != null) JOptionPane.showMessageDialog(parent, err, "Unlock failed", JOptionPane.ERROR_MESSAGE);
                owner.refreshLockBar();
                owner.saveSoon(); // anything that was waiting for the password is written now
            });
        } else if (store.encrypt() && !store.unlocked()) {
            char[] pw = askNewPassword(parent, "Set the encryption password");
            if (pw == null) return;
            owner.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            store.setPassword(pw, owner.snapshots(), err -> {
                owner.setCursor(Cursor.getDefaultCursor());
                if (err != null) JOptionPane.showMessageDialog(parent, err, "Could not set the password", JOptionPane.ERROR_MESSAGE);
                owner.refreshLockBar();
            });
        }
    }

    /** Recovery for a lost password: after a clear warning, removes the locked captures and turns encryption off, so nobody is ever stuck. */
    static void forgotPassword(MainPanel owner, Persist store, Component parent) {
        int n = store.lockedCount();
        String what = n > 0 ? n + " saved capture(s) that are still locked will be DELETED permanently" : "encryption will be turned off";
        String msg = "Without the password the encrypted data cannot be read by anyone.\nIf you continue, " + what
                + " and\nencryption is turned off, so you can set a new password afterwards.\n\nOpen tabs are kept. Continue?";
        if (JOptionPane.showConfirmDialog(parent, msg, "Forgot the password", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        store.discardLocked(err -> {
            if (err != null) JOptionPane.showMessageDialog(parent, err, "Could not reset", JOptionPane.ERROR_MESSAGE);
            owner.refreshLockBar();
            owner.saveSoon();
        });
    }

    // ------------------------------------------------------------------ the dialog

    static void open(MainPanel owner, Persist store) {
        Window w = SwingUtilities.getWindowAncestor(owner);
        JDialog d = new JDialog(w, "Storage and encryption", Dialog.ModalityType.APPLICATION_MODAL);

        JRadioButton rbProject = new JRadioButton("The Burp project file");
        JRadioButton rbSession = new JRadioButton("This session only");
        JRadioButton rbFolder = new JRadioButton("A folder:");
        ButtonGroup g = new ButtonGroup();
        g.add(rbProject);
        g.add(rbSession);
        g.add(rbFolder);
        JTextField folder = new JTextField(store.dir(), 34);
        JButton browse = new JButton("Browse...");
        JLabel here = new JLabel(" ");
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        JLabel encState = new JLabel(" ");
        JButton enable = new JButton("Encrypt with a password...");
        JButton disable = new JButton("Turn off encryption");
        JButton lock = new JButton("Lock now");
        JButton forgot = new JButton("Forgot the password...");

        // the radio buttons / folder field are a pending choice: they are set once here and then only changed by the user
        switch (store.mode()) {
            case SESSION: rbSession.setSelected(true); break;
            case DIRECTORY: rbFolder.setSelected(true); break;
            default: rbProject.setSelected(true);
        }
        folder.setEnabled(rbFolder.isSelected());
        browse.setEnabled(rbFolder.isSelected());
        java.util.function.Supplier<Persist.Mode> chosenMode = () -> rbSession.isSelected() ? Persist.Mode.SESSION : rbFolder.isSelected() ? Persist.Mode.DIRECTORY : Persist.Mode.PROJECT;
        java.util.function.Supplier<String> chosenDir = () -> {
            try {
                return folder.getText().trim().isEmpty() ? "" : Paths.get(folder.getText().trim()).toAbsolutePath().normalize().toString();
            } catch (Exception ex) {
                return "";
            }
        };
        java.util.function.BooleanSupplier locationChanged = () -> {
            Persist.Mode m = chosenMode.get();
            return m != store.mode() || (m == Persist.Mode.DIRECTORY && !chosenDir.get().equals(store.dir()));
        };

        Runnable refresh = () -> {
            here.setText("Now saving to: " + store.where());
            if (!store.encrypt()) encState.setText("Encryption is OFF - captures are stored without protection.");
            else encState.setText(store.unlocked() ? "Encryption is ON - unlocked for this session." : "Encryption is ON - LOCKED (password not entered this session).");
            enable.setText(store.encrypt() ? "Change password..." : "Encrypt with a password...");
            enable.setEnabled(!store.encrypt() || store.unlocked());
            disable.setEnabled(store.encrypt() && store.unlocked());
            lock.setEnabled(store.encrypt() && store.unlocked());
            forgot.setEnabled(store.encrypt() && !store.unlocked());
            owner.refreshLockBar();
        };
        java.awt.event.ActionListener radios = e -> {
            folder.setEnabled(rbFolder.isSelected());
            browse.setEnabled(rbFolder.isSelected());
        };
        rbProject.addActionListener(radios);
        rbSession.addActionListener(radios);
        rbFolder.addActionListener(radios);

        browse.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(folder.getText().isEmpty() ? null : new File(folder.getText()));
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Folder for BurpShot captures");
            if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) folder.setText(fc.getSelectedFile().getAbsolutePath());
        });

        ok.addActionListener(e -> {
            Persist.Mode m = chosenMode.get();
            String dir = store.dir();
            if (m == Persist.Mode.DIRECTORY) {
                dir = chosenDir.get();
                if (!Persist.validFolder(dir)) {
                    JOptionPane.showMessageDialog(d, "Choose a folder (an absolute path).");
                    return;
                }
            }
            if (!locationChanged.getAsBoolean()) { // nothing pending: just close
                d.dispose();
                return;
            }
            if (m == Persist.Mode.SESSION && JOptionPane.showConfirmDialog(d, "In 'this session only' mode captures are kept in memory and are gone when Burp closes\n(or the extension is unloaded). Continue?",
                    "This session only", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            d.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            store.relocate(m, dir, owner.snapshots(), err -> {
                d.setCursor(Cursor.getDefaultCursor());
                refresh.run();
                if (err != null) { // stay open, the choice is kept so it can be corrected
                    JOptionPane.showMessageDialog(d, err, "Could not move the captures", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                JOptionPane.showMessageDialog(d, "Captures are now saved to: " + store.where() + "\n(The previous copy was left where it was.)");
                d.dispose();
            });
        });

        enable.addActionListener(e -> {
            if (store.lockedCount() > 0) {
                JOptionPane.showMessageDialog(d, "Unlock the existing encrypted captures first.");
                return;
            }
            char[] pw = askNewPassword(d, store.encrypt() ? "New password" : "Encrypt captures with a password");
            if (pw == null) return;
            d.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            store.setPassword(pw, owner.snapshots(), err -> {
                d.setCursor(Cursor.getDefaultCursor());
                if (err != null) JOptionPane.showMessageDialog(d, err, "Could not encrypt", JOptionPane.ERROR_MESSAGE);
                refresh.run();
            });
        });
        disable.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(d, "Captures will be saved WITHOUT encryption from now on. Continue?", "Turn off encryption",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            store.disableEncryption(owner.snapshots(), err -> {
                if (err != null) JOptionPane.showMessageDialog(d, err, "Could not turn off encryption", JOptionPane.ERROR_MESSAGE);
                refresh.run();
            });
        });
        forgot.addActionListener(e -> {
            forgotPassword(owner, store, d);
            refresh.run();
        });
        lock.addActionListener(e -> {
            owner.saveNow(); // queued before the lock, so nothing is lost
            store.lock(err -> {
                ShareDialog.wipe();
                owner.removeTabsKeepData();
                refresh.run();
            });
        });

        JButton open = new JButton("Open a capture file...");
        open.setToolTipText("Open a .burpshot capture archive or decrypt an encrypted file that BurpShot exported");
        open.addActionListener(e -> openFile(owner, store, d));
        Runnable cancelAction = () -> {
            if (locationChanged.getAsBoolean() && JOptionPane.showConfirmDialog(d, "You chose a different place for the captures but did not press OK.\nDiscard that change?", "Storage",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return;
            d.dispose();
        };
        cancel.addActionListener(e -> cancelAction.run());
        d.setDefaultCloseOperation(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                cancelAction.run();
            }
        });

        JPanel where = new JPanel();
        where.setLayout(new javax.swing.BoxLayout(where, javax.swing.BoxLayout.Y_AXIS));
        where.setBorder(BorderFactory.createTitledBorder("Where captures are kept"));
        where.add(row(rbProject));
        where.add(row(notes("Saved inside the project; they come back when the project is opened again", "(a temporary project forgets them).")));
        where.add(row(rbSession));
        where.add(row(notes("Kept in memory only while Burp is open; nothing is written anywhere.", "Also gone if the extension is unloaded or reloaded.")));
        JPanel folderRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        folderRow.add(rbFolder);
        folderRow.add(folder);
        folderRow.add(browse);
        where.add(folderRow);
        where.add(row(notes("Files go to <folder>/<project name>/ (one file per capture).", "Save PNG opens in its 'screenshots' sub-folder.")));
        where.add(row(here));
        where.add(row(notes("The location is applied when you press OK (the captures are copied there).")));

        JPanel enc = new JPanel();
        enc.setLayout(new javax.swing.BoxLayout(enc, javax.swing.BoxLayout.Y_AXIS));
        enc.setBorder(BorderFactory.createTitledBorder("Encryption at rest (these buttons act immediately)"));
        enc.add(row(encState));
        enc.add(row(notes("ENCRYPTED: the saved captures (project file, session, folder), and anything you tick \"Encrypt\" for:",
                "   Save PNG (checkbox in the save dialog) and Share uploads (checkbox in the Share dialog).",
                "NOT encrypted: Copy image, the Preview window, and PNGs saved with the box unticked - they are for your report.",
                "AES-256-GCM, key from your password (PBKDF2-HMAC-SHA256, 600,000 rounds). The password is never stored; no recovery.")));
        JPanel encButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        encButtons.add(enable);
        encButtons.add(disable);
        encButtons.add(lock);
        encButtons.add(forgot);
        enc.add(encButtons);

        JPanel files = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        files.setBorder(BorderFactory.createTitledBorder("Files"));
        files.add(open);

        JPanel center = new JPanel();
        center.setLayout(new javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        center.add(where);
        center.add(enc);
        center.add(files);
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        south.add(ok);
        south.add(cancel);
        d.getContentPane().add(center, BorderLayout.CENTER);
        d.getContentPane().add(south, BorderLayout.SOUTH);
        refresh.run();
        d.pack();
        d.setLocationRelativeTo(w);
        d.setVisible(true);
    }

    private static JPanel row(java.awt.Component c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        p.add(c);
        return p;
    }

    private static JPanel notes(String... lines) {
        return Txt.lines(true, lines);
    }

    private static JLabel note(String s) {
        JLabel l = new JLabel(s);
        l.setFont(l.getFont().deriveFont(java.awt.Font.ITALIC));
        return l;
    }

    // ------------------------------------------------------------------ opening exported files

    /** One window instead of a second file chooser: the decrypted image is shown, and saving it is an explicit, optional button. */
    private static void showDecryptedPng(Component parent, byte[] png, Path source) throws Exception {
        java.awt.image.BufferedImage img = Png.decode(png);
        JDialog v = new JDialog(SwingUtilities.getWindowAncestor(parent), "Decrypted: " + source.getFileName(), Dialog.ModalityType.APPLICATION_MODAL);
        javax.swing.JScrollPane sp = new javax.swing.JScrollPane(new JLabel(new javax.swing.ImageIcon(img)));
        sp.setPreferredSize(new java.awt.Dimension(Math.min(img.getWidth() + 24, 1000), Math.min(img.getHeight() + 24, 650)));
        JButton save = new JButton("Save as PNG...");
        save.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(source.getParent() == null ? null : source.getParent().toFile());
            fc.setSelectedFile(new File(source.getFileName().toString().replaceAll("(?i)(\\.png)?\\.bsv$", "") + ".png"));
            if (fc.showSaveDialog(v) != JFileChooser.APPROVE_OPTION) return;
            File out = fc.getSelectedFile();
            if (!out.getName().toLowerCase().endsWith(".png")) out = new File(out.getPath() + ".png");
            try {
                Files.write(out.toPath(), png);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(v, "Could not save: " + ex.getMessage());
            }
        });
        JButton close = new JButton("Close");
        close.addActionListener(e -> v.dispose());
        JPanel s = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        s.add(save);
        s.add(close);
        v.getContentPane().add(sp, BorderLayout.CENTER);
        v.getContentPane().add(s, BorderLayout.SOUTH);
        v.pack();
        v.setLocationRelativeTo(parent);
        v.setVisible(true);
    }

    private static void openFile(MainPanel owner, Persist store, Component parent) {
        JFileChooser fc = new JFileChooser(Persist.defaultShotDir());
        fc.setDialogTitle("Open a BurpShot capture file");
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Path p = fc.getSelectedFile().toPath();
        char[] pw = null;
        try {
            if (Files.size(p) > (700L << 20)) throw new IllegalStateException("The file is too large.");
            byte[] data = Files.readAllBytes(p);
            byte[] plain = data;
            if (Vault.isEncrypted(data)) {
                parent.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                try {
                    plain = null;
                    if (store.unlocked()) {
                        try {
                            plain = store.decryptIfNeeded(data);
                        } catch (Vault.BadPasswordException ignored) {
                            // not encrypted with this session's password: ask for the file's own
                        }
                    }
                    if (plain == null) {
                        pw = askPassword(parent, "This file is encrypted. Password:");
                        if (pw == null) return;
                        plain = Persist.decryptWith(pw, data);
                    }
                } finally {
                    parent.setCursor(Cursor.getDefaultCursor());
                }
            }
            if (Png.looksLikePng(plain)) {
                showDecryptedPng(parent, plain, p);
            } else {
                Store.Restored r = CaptureArchive.unpack(plain);
                if (r.blocks.isEmpty()) throw new IllegalStateException("The archive contains no layers.");
                owner.openImported(r);
                JOptionPane.showMessageDialog(parent, "Opened as a new capture tab.");
            }
        } catch (Vault.BadPasswordException e) {
            JOptionPane.showMessageDialog(parent, e.getMessage(), "Could not open the file", JOptionPane.ERROR_MESSAGE);
        } catch (Throwable t) {
            String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            JOptionPane.showMessageDialog(parent, "Could not open the file: " + m, "Open", JOptionPane.ERROR_MESSAGE);
        } finally {
            if (pw != null) Arrays.fill(pw, '\0');
        }
    }
}
