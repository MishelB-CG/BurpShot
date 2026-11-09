package ptshot;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * "Share...": uploads the screenshot (PNG) or the whole capture (editable archive), optionally encrypted with the BurpShot password,
 * to a remote destination. The form is generated from {@link RemoteSpec}, so every destination type works the same way.
 *
 * <p>Secrets (passwords, tokens, API keys, cookies, private keys) are only ever kept in memory for the session and only when
 * "remember for this session" is ticked; saved destination profiles contain no secrets. SSH host keys are trusted explicitly.
 */
final class ShareDialog {
    private ShareDialog() {}

    private static final String K_PROFILES = "ptshot.share.profiles", K_HOSTKEYS = "ptshot.share.hostkeys";
    private static final int MAX_PROFILES = 50;
    /** Secrets of destinations used this session (profile name -> field -> value). Never written to disk. */
    private static final Map<String, Map<String, String>> SESSION_SECRETS = new HashMap<>();

    /** Forget every secret kept for this session (called when the extension unloads or the captures are locked). */
    static void wipe() {
        SESSION_SECRETS.clear();
    }

    // ------------------------------------------------------------------ saved profiles (no secrets) and trusted host keys

    static final class Profile {
        String name, type;
        final Map<String, String> cfg = new LinkedHashMap<>();
    }

    private static String pref(String k) {
        Persist p = Persist.current;
        return p == null ? null : p.pref(k);
    }

    private static void setPref(String k, String v) {
        Persist p = Persist.current;
        if (p != null) p.setPref(k, v);
    }

    /** Reads the saved profiles. Untrusted input: parsed with the limited parser, only known types / non-secret keys survive. */
    @SuppressWarnings("unchecked")
    static List<Profile> loadProfiles() {
        List<Profile> out = new ArrayList<>();
        String json = pref(K_PROFILES);
        if (json == null || json.isEmpty() || json.length() > 1_000_000) return out;
        try {
            Object o = Mini.parse(json);
            if (!(o instanceof List)) return out;
            for (Object e : (List<Object>) o) {
                if (out.size() >= MAX_PROFILES || !(e instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) e;
                Object n = m.get("name"), t = m.get("type"), c = m.get("cfg");
                if (!(n instanceof String) || !(t instanceof String) || !(c instanceof Map)) continue;
                RemoteSpec.Type ty = RemoteSpec.type((String) t);
                if (ty == null || ((String) n).length() > 60 || ((String) n).trim().isEmpty()) continue;
                Profile p = new Profile();
                p.name = ((String) n).trim();
                p.type = ty.id;
                for (RemoteSpec.Field f : ty.fields) {
                    if (f.secret) continue; // a saved profile never carries secrets, even if someone edited the preferences
                    Object v = ((Map<String, Object>) c).get(f.key);
                    if (v instanceof String && ((String) v).length() <= 4000) p.cfg.put(f.key, (String) v);
                }
                out.add(p);
            }
        } catch (RuntimeException ignored) {
            // unreadable preferences are ignored
        }
        return out;
    }

    private static void saveProfiles(List<Profile> l) {
        List<Object> arr = new ArrayList<>();
        for (Profile p : l) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.name);
            m.put("type", p.type);
            m.put("cfg", new LinkedHashMap<String, Object>(p.cfg));
            arr.add(m);
        }
        setPref(K_PROFILES, Mini.write(arr));
    }

    @SuppressWarnings("unchecked")
    static Map<String, String> hostKeys() {
        Map<String, String> out = new LinkedHashMap<>();
        String json = pref(K_HOSTKEYS);
        if (json == null || json.isEmpty() || json.length() > 200_000) return out;
        try {
            Object o = Mini.parse(json);
            if (o instanceof Map) {
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                    if (e.getValue() instanceof String && e.getKey().length() < 300 && ((String) e.getValue()).startsWith("SHA256:") && out.size() < 500) out.put(e.getKey(), (String) e.getValue());
                }
            }
        } catch (RuntimeException ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ the dialog

    static void open(Window owner, BufferedImage image, Persist.Snap snap, String suggestedName) {
        JDialog d = new JDialog(owner, "Share / save to remote", Dialog.ModalityType.APPLICATION_MODAL);
        final List<RemoteSpec.Type> types = RemoteSpec.types();
        final Map<String, JComponent> comps = new LinkedHashMap<>();
        final Map<String, RemoteSpec.Field> fieldOf = new LinkedHashMap<>();
        final Map<String, JComponent[]> rowsOf = new LinkedHashMap<>();
        final List<Profile> profiles = loadProfiles();

        // ---- what to send
        JRadioButton rbPng = new JRadioButton("Screenshot (PNG)", true);
        JRadioButton rbArchive = new JRadioButton("Capture archive (editable: layers, annotations, images)");
        ButtonGroup bg = new ButtonGroup();
        bg.add(rbPng);
        bg.add(rbArchive);
        JCheckBox encrypt = new JCheckBox("Encrypt with my BurpShot password before sending");
        encrypt.setToolTipText("AES-256-GCM. Open it later with Storage... > Open a capture file...");
        JTextField nameField = new JTextField(suggestedName, 28);
        JLabel nameHint = new JLabel(" ");
        Runnable nameHintUpdate = () -> nameHint.setText("sent as: " + remoteName(nameField.getText(), rbArchive.isSelected(), encrypt.isSelected()));
        nameField.getDocument().addDocumentListener(new SimpleDoc(nameHintUpdate));
        rbPng.addActionListener(e -> nameHintUpdate.run());
        rbArchive.addActionListener(e -> nameHintUpdate.run());
        encrypt.addActionListener(e -> nameHintUpdate.run());

        // ---- destination
        JComboBox<String> profileBox = new JComboBox<>();
        JButton saveProfile = new JButton("Save as profile...");
        JButton delProfile = new JButton("Delete profile");
        JComboBox<String> typeBox = new JComboBox<>();
        for (RemoteSpec.Type t : types) typeBox.addItem(t.title);
        JTextArea typeInfo = Txt.note(" ", true);
        typeInfo.setColumns(70);
        typeInfo.setRows(4);
        JTextArea warn = Txt.note(" ", false);
        warn.setColumns(70);
        warn.setRows(2);
        warn.setForeground(new java.awt.Color(170, 60, 0));
        JPanel form = new JPanel(new GridBagLayout());
        JCheckBox remember = new JCheckBox("Remember secrets for this session (never saved to disk)", true);

        JTextArea log = new JTextArea(4, 70);
        log.setEditable(false);
        log.setLineWrap(true);
        log.setWrapStyleWord(true);
        log.setColumns(70);
        final String[] lastLocation = {null};
        JButton copyLoc = new JButton("Copy location");
        copyLoc.setEnabled(false);
        JButton test = new JButton("Test connection");
        JButton upload = new JButton("Upload");
        JButton close = new JButton("Close");
        JButton viewReq = new JButton("View / edit request...");
        viewReq.setToolTipText("See the HTTP request that will be sent (without the file's bytes) and, for REST endpoints, edit it");
        final String[] edited = {null};      // the edited request text (secrets as tokens), null = generated from the form
        final String[] editedHost = {null};  // host of the generated request when the edit was made
        @SuppressWarnings("unchecked")
        final Map<String, String>[] editedFor = new Map[1]; // form state when the edit was made
        final Object[] cache = new Object[2]; // prepared bytes of the last upload / preview
        JLabel editedInfo = new JLabel(" ");
        editedInfo.setForeground(new java.awt.Color(0, 90, 160));
        JButton discardEdit = new JButton("Discard edit");
        discardEdit.setVisible(false);
        Runnable updateEdited = () -> {
            editedInfo.setText(edited[0] == null ? " " : "An edited request will be sent instead of the generated one.");
            discardEdit.setVisible(edited[0] != null);
        };
        discardEdit.addActionListener(e -> {
            edited[0] = null;
            updateEdited.run();
        });

        Runnable[] rebuild = new Runnable[1];
        Runnable updateWarn = () -> {
            RemoteSpec.Type t = types.get(Math.max(0, typeBox.getSelectedIndex()));
            StringBuilder w = new StringBuilder();
            if (t.plaintext) w.append("This protocol is NOT encrypted on the network: anyone on the path can read what you send. ");
            JComponent ins = comps.get("insecureTls");
            if (ins instanceof JCheckBox && ((JCheckBox) ins).isSelected()) w.append("Certificate checks are OFF: a man-in-the-middle could read what you send. ");
            if (!encrypt.isSelected()) w.append("The file is sent unencrypted (tick the encryption box to protect it).");
            warn.setText(w.length() == 0 ? " " : w.toString());
        };
        encrypt.addActionListener(e -> updateWarn.run());

        rebuild[0] = () -> {
            form.removeAll();
            comps.clear();
            fieldOf.clear();
            rowsOf.clear();
            RemoteSpec.Type t = types.get(Math.max(0, typeBox.getSelectedIndex()));
            typeInfo.setText(t.description);
            edited[0] = null;
            updateEdited.run();
            viewReq.setEnabled(RemoteClient.hasRequestView(t.id));
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(2, 4, 2, 4);
            c.anchor = GridBagConstraints.WEST;
            int row = 0;
            for (RemoteSpec.Field f : t.fields) {
                JComponent comp;
                switch (f.kind) {
                    case PASSWORD: {
                        JPasswordField pf = new JPasswordField(f.def, 30);
                        comp = pf;
                        break;
                    }
                    case CHOICE: {
                        JComboBox<String> cb = new JComboBox<>(f.choices);
                        if (!f.def.isEmpty()) cb.setSelectedItem(f.def);
                        comp = cb;
                        break;
                    }
                    case MULTILINE: {
                        JTextArea ta = new JTextArea(f.def, 3, 28);
                        ta.setLineWrap(true);
                        comp = new JScrollPane(ta);
                        comps.put(f.key + "#area", ta);
                        break;
                    }
                    case CHECKBOX: {
                        JCheckBox cb = new JCheckBox(f.label, "true".equals(f.def));
                        comp = cb;
                        break;
                    }
                    default: {
                        JTextField tf = new JTextField(f.def, 30);
                        comp = tf;
                    }
                }
                if (f.help != null && !f.help.isEmpty()) comp.setToolTipText(f.help);
                JLabel lab = new JLabel(f.kind == RemoteSpec.Kind.CHECKBOX ? " " : f.label);
                if (f.secret) lab.setText(lab.getText() + " •");
                c.gridx = 0;
                c.gridy = row;
                c.gridwidth = 1;
                c.fill = GridBagConstraints.NONE;
                c.weightx = 0;
                form.add(lab, c);
                c.gridx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.weightx = 1;
                JComponent shown = comp;
                if (f.kind == RemoteSpec.Kind.FILE) {
                    JPanel fp = new JPanel(new BorderLayout(4, 0));
                    fp.add(comp, BorderLayout.CENTER);
                    JButton br = new JButton("...");
                    br.addActionListener(e -> {
                        JFileChooser fc = new JFileChooser();
                        if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) ((JTextField) comp).setText(fc.getSelectedFile().getAbsolutePath());
                    });
                    fp.add(br, BorderLayout.EAST);
                    shown = fp;
                }
                form.add(shown, c);
                comps.put(f.key, comp);
                fieldOf.put(f.key, f);
                rowsOf.put(f.key, new JComponent[]{lab, shown});
                row++;
            }
            // showIf: "otherKey=a|b" hides a row unless that choice field has one of those values
            Runnable vis = () -> {
                for (RemoteSpec.Field f : t.fields) {
                    boolean show = true;
                    if (f.showIf != null && !f.showIf.isEmpty() && f.showIf.contains("=")) {
                        String[] kv = f.showIf.split("=", 2);
                        JComponent dep = comps.get(kv[0]);
                        String cur = dep instanceof JComboBox ? String.valueOf(((JComboBox<?>) dep).getSelectedItem()) : "";
                        show = Arrays.asList(kv[1].split("\\|")).contains(cur);
                    }
                    for (JComponent x : rowsOf.get(f.key)) x.setVisible(show);
                }
                form.revalidate();
                form.repaint();
                d.pack();
            };
            for (RemoteSpec.Field f : t.fields) {
                JComponent comp = comps.get(f.key);
                if (comp instanceof JComboBox) ((JComboBox<?>) comp).addActionListener(e -> vis.run());
                if (comp instanceof JCheckBox) ((JCheckBox) comp).addActionListener(e -> updateWarn.run());
            }
            vis.run();
            updateWarn.run();
        };
        typeBox.addActionListener(e -> rebuild[0].run());

        Runnable fillProfiles = () -> {
            profileBox.removeAllItems();
            profileBox.addItem("(new destination)");
            for (Profile p : profiles) profileBox.addItem(p.name);
        };
        fillProfiles.run();

        // loading a profile: its non-secret fields, plus secrets remembered earlier in this session
        profileBox.addActionListener(e -> {
            int i = profileBox.getSelectedIndex() - 1;
            if (i < 0 || i >= profiles.size()) return;
            Profile p = profiles.get(i);
            for (int k = 0; k < types.size(); k++) if (types.get(k).id.equals(p.type)) typeBox.setSelectedIndex(k);
            rebuild[0].run();
            Map<String, String> sec = SESSION_SECRETS.getOrDefault(p.name, new HashMap<>());
            for (Map.Entry<String, JComponent> en : comps.entrySet()) {
                RemoteSpec.Field f = fieldOf.get(en.getKey());
                if (f == null) continue;
                String v = f.secret ? sec.get(f.key) : p.cfg.get(f.key);
                if (v != null) setValue(en.getValue(), comps.get(f.key + "#area"), v);
            }
        });

        saveProfile.addActionListener(e -> {
            Object n = JOptionPane.showInputDialog(d, "Name for this destination (no passwords or tokens are saved):", "Save profile", JOptionPane.PLAIN_MESSAGE, null, null, "");
            if (n == null || n.toString().trim().isEmpty()) return;
            String name = n.toString().trim();
            if (name.length() > 60) name = name.substring(0, 60);
            RemoteSpec.Type t = types.get(typeBox.getSelectedIndex());
            Map<String, String> all = collect(t, comps);
            Profile p = null;
            for (Profile x : profiles) if (x.name.equalsIgnoreCase(name)) p = x;
            if (p == null) {
                if (profiles.size() >= MAX_PROFILES) {
                    JOptionPane.showMessageDialog(d, "Too many saved profiles.");
                    return;
                }
                p = new Profile();
                p.name = name;
                profiles.add(p);
            }
            p.type = t.id;
            p.cfg.clear();
            Map<String, String> sec = new HashMap<>();
            for (RemoteSpec.Field f : t.fields) {
                String v = all.get(f.key);
                if (v == null || v.isEmpty()) continue;
                if (f.secret) sec.put(f.key, v);
                else p.cfg.put(f.key, v);
            }
            if (remember.isSelected() && !sec.isEmpty()) SESSION_SECRETS.put(p.name, sec);
            saveProfiles(profiles);
            fillProfiles.run();
            profileBox.setSelectedItem(p.name);
        });
        delProfile.addActionListener(e -> {
            int i = profileBox.getSelectedIndex() - 1;
            if (i < 0 || i >= profiles.size()) return;
            SESSION_SECRETS.remove(profiles.get(i).name);
            profiles.remove(i);
            saveProfiles(profiles);
            fillProfiles.run();
        });

        // ---- actions
        final RemoteClient.Ui ui = new RemoteClient.Ui() {
            public void progress(String m) {
                SwingUtilities.invokeLater(() -> log.append(m + "\n"));
            }

            public String trustedHostKey(String hostPort) {
                return hostKeys().get(hostPort);
            }

            public boolean confirmHostKey(String hostPort, String keyType, String fingerprint, boolean changed) {
                final boolean[] ok = {false};
                try {
                    SwingUtilities.invokeAndWait(() -> {
                        String msg = changed
                                ? "WARNING: the SSH host key of " + hostPort + " has CHANGED.\nThis can mean the server was reinstalled - or that someone is intercepting the connection.\n\nNew " + keyType + " fingerprint:\n" + fingerprint + "\n\nTrust it anyway?"
                                : "The authenticity of " + hostPort + " is not known yet.\n\n" + keyType + " fingerprint:\n" + fingerprint + "\n\nCompare it with the server's fingerprint. Trust this host?";
                        ok[0] = JOptionPane.showConfirmDialog(d, msg, changed ? "Host key changed" : "Unknown SSH host", JOptionPane.YES_NO_OPTION,
                                changed ? JOptionPane.ERROR_MESSAGE : JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
                    });
                } catch (Exception e) {
                    return false;
                }
                return ok[0];
            }

            public void rememberHostKey(String hostPort, String fingerprint) {
                Map<String, String> m = hostKeys();
                m.put(hostPort, fingerprint);
                setPref(K_HOSTKEYS, Mini.write(new LinkedHashMap<String, Object>(m)));
            }
        };

        Runnable busyOn = () -> {
            test.setEnabled(false);
            upload.setEnabled(false);
            d.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR));
        };
        Runnable busyOff = () -> {
            test.setEnabled(true);
            upload.setEnabled(true);
            d.setCursor(java.awt.Cursor.getDefaultCursor());
        };

        test.addActionListener(e -> {
            RemoteSpec.Type t = types.get(typeBox.getSelectedIndex());
            Map<String, String> cfg = collect(t, comps);
            if (!confirmRisk(d, t, cfg)) return;
            log.setText("");
            busyOn.run();
            new Thread(() -> {
                try {
                    RemoteClient.Result r = RemoteClient.test(t.id, cfg, ui);
                    SwingUtilities.invokeLater(() -> log.append("OK: " + r.location + " - " + r.detail + "\n"));
                } catch (RemoteClient.RemoteException ex) {
                    SwingUtilities.invokeLater(() -> log.append("FAILED: " + ex.getMessage() + "\n"));
                } catch (Throwable ex) {
                    SwingUtilities.invokeLater(() -> log.append("FAILED: " + ex.getClass().getSimpleName() + "\n"));
                } finally {
                    SwingUtilities.invokeLater(busyOff);
                }
            }, "BurpShot-share-test").start();
        });

        java.util.function.Supplier<Map<String, String>> formState = () -> {
            RemoteSpec.Type t = types.get(typeBox.getSelectedIndex());
            Map<String, String> m = collect(t, comps);
            m.put("__type", t.id);
            m.put("__archive", String.valueOf(rbArchive.isSelected()));
            m.put("__enc", String.valueOf(encrypt.isSelected()));
            m.put("__name", remoteName(nameField.getText(), rbArchive.isSelected(), encrypt.isSelected()));
            return m;
        };
        upload.addActionListener(e -> {
            RemoteSpec.Type t = types.get(typeBox.getSelectedIndex());
            Map<String, String> cfg = collect(t, comps);
            boolean wantArchive = rbArchive.isSelected(), wantEnc = encrypt.isSelected();
            Persist ps = Persist.current;
            if (wantEnc && (ps == null || !ps.unlocked())) {
                JOptionPane.showMessageDialog(d, "Encryption needs your BurpShot password.\nSet or enter it first under Storage... > Encrypt with a password...", "Password needed", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (!confirmRisk(d, t, cfg)) return;
            String useEdit = edited[0];
            if (useEdit != null) {
                if (!formState.get().equals(editedFor[0])) {
                    int ch = JOptionPane.showOptionDialog(d, "The form was changed after you edited the request.", "Edited request", JOptionPane.DEFAULT_OPTION,
                            JOptionPane.QUESTION_MESSAGE, null, new Object[]{"Use the edited request anyway", "Discard the edit and use the form", "Cancel"}, "Discard the edit and use the form");
                    if (ch == 1) {
                        edited[0] = null;
                        updateEdited.run();
                        useEdit = null;
                    } else if (ch != 0) {
                        return;
                    }
                }
                String nowHost = useEdit == null ? "" : hostOf(useEdit);
                if (useEdit != null && useEdit.contains("{{secret:") && editedHost[0] != null && !nowHost.equals(editedHost[0])) {
                    if (JOptionPane.showConfirmDialog(d, "The edited request goes to a different host (" + (nowHost.isEmpty() ? "unknown" : nowHost) + ") than the destination you configured (" + editedHost[0]
                            + "),\nand it contains your credentials. Send them there?", "Different host", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) return;
                }
            }
            final String sendEdit = useEdit;
            String name = remoteName(nameField.getText(), wantArchive, wantEnc);
            log.setText("Preparing " + name + " ...\n");
            busyOn.run();
            if (remember.isSelected() && profileBox.getSelectedIndex() > 0) {
                Map<String, String> sec = new HashMap<>();
                for (RemoteSpec.Field f : t.fields) if (f.secret && cfg.get(f.key) != null && !cfg.get(f.key).isEmpty()) sec.put(f.key, cfg.get(f.key));
                if (!sec.isEmpty()) SESSION_SECRETS.put(String.valueOf(profileBox.getSelectedItem()), sec);
            }
            new Thread(() -> {
                try {
                    Prepared pr = prepareCached(cache, image, snap, wantArchive, wantEnc);
                    RemoteClient.Result r = sendEdit != null ? RemoteClient.sendRequestText(t.id, cfg, sendEdit, pr.data, ui)
                            : RemoteClient.upload(t.id, cfg, name, pr.data, pr.contentType, ui);
                    SwingUtilities.invokeLater(() -> {
                        lastLocation[0] = r.location;
                        copyLoc.setEnabled(r.location != null && !r.location.isEmpty());
                        log.append("Uploaded: " + r.location + "\n" + r.detail + "\n");
                    });
                } catch (RemoteClient.RemoteException ex) {
                    SwingUtilities.invokeLater(() -> log.append("FAILED: " + ex.getMessage() + "\n"));
                } catch (Throwable ex) {
                    final String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    SwingUtilities.invokeLater(() -> log.append("FAILED: " + m + "\n"));
                } finally {
                    SwingUtilities.invokeLater(busyOff);
                }
            }, "BurpShot-share-upload").start();
        });
        viewReq.addActionListener(e -> {
            RemoteSpec.Type t = types.get(typeBox.getSelectedIndex());
            Map<String, String> cfg = collect(t, comps);
            boolean wantArchive = rbArchive.isSelected(), wantEnc = encrypt.isSelected();
            Persist ps = Persist.current;
            if (wantEnc && (ps == null || !ps.unlocked())) {
                JOptionPane.showMessageDialog(d, "Encryption needs your BurpShot password.\nSet or enter it first under Storage... > Encrypt with a password...", "Password needed", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            final String name = remoteName(nameField.getText(), wantArchive, wantEnc);
            final Map<String, String> state = formState.get();
            log.setText("Preparing the request ...\n");
            busyOn.run();
            new Thread(() -> {
                try {
                    Prepared pr = prepareCached(cache, image, snap, wantArchive, wantEnc);
                    RemoteClient.RequestPreview pv = RemoteClient.previewRequest(t.id, cfg, name, pr.data.length, pr.contentType);
                    SwingUtilities.invokeLater(() -> {
                        log.setText("");
                        String again = edited[0] != null && state.equals(editedFor[0]) ? edited[0] : null;
                        String text = RequestDialog.open(d, pv, cfg, again);
                        if (text != null) {
                            edited[0] = text;
                            editedFor[0] = state;
                            editedHost[0] = hostOf(pv.text);
                            updateEdited.run();
                        }
                    });
                } catch (RemoteClient.RemoteException ex) {
                    SwingUtilities.invokeLater(() -> log.setText("FAILED: " + ex.getMessage() + "\n"));
                } catch (Throwable ex) {
                    final String m = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                    SwingUtilities.invokeLater(() -> log.setText("FAILED: " + m + "\n"));
                } finally {
                    SwingUtilities.invokeLater(busyOff);
                }
            }, "BurpShot-share-preview").start();
        });
        copyLoc.addActionListener(e -> {
            if (lastLocation[0] != null) Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(lastLocation[0]), null);
        });
        close.addActionListener(e -> d.dispose());

        // ---- layout
        JPanel what = new JPanel(new GridBagLayout());
        what.setBorder(BorderFactory.createTitledBorder("What to send"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 6, 2, 6);
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        what.add(rbPng, c);
        c.gridy = 1;
        what.add(rbArchive, c);
        c.gridy = 2;
        what.add(encrypt, c);
        c.gridy = 3;
        c.gridwidth = 1;
        c.weightx = 0;
        what.add(new JLabel("File name"), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        what.add(nameField, c);
        c.gridy = 4;
        what.add(nameHint, c);

        JPanel dest = new JPanel(new GridBagLayout());
        dest.setBorder(BorderFactory.createTitledBorder("Destination"));
        c = new GridBagConstraints();
        c.insets = new Insets(2, 6, 2, 6);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        dest.add(new JLabel("Saved destination"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        JPanel pr = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pr.add(profileBox);
        pr.add(saveProfile);
        pr.add(delProfile);
        dest.add(pr, c);
        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        c.weightx = 0;
        dest.add(new JLabel("Type"), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        JPanel tp = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tp.add(typeBox);
        dest.add(tp, c);
        c.gridx = 0;
        c.gridy = 2;
        c.gridwidth = 2;
        dest.add(typeInfo, c);
        c.gridy = 3;
        dest.add(form, c);
        c.gridy = 4;
        dest.add(remember, c);
        c.gridy = 5;
        dest.add(warn, c);
        c.gridy = 6;
        JPanel editRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        editRow.add(editedInfo);
        editRow.add(discardEdit);
        dest.add(editRow, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(copyLoc);
        buttons.add(viewReq);
        buttons.add(test);
        buttons.add(upload);
        buttons.add(close);
        JPanel center = new JPanel();
        center.setLayout(new javax.swing.BoxLayout(center, javax.swing.BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        center.add(what);
        center.add(dest);
        center.add(new JScrollPane(log));
        JScrollPane scroll = new JScrollPane(center);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        d.getContentPane().add(scroll, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        nameHintUpdate.run();
        rebuild[0].run();
        d.pack();
        // never taller than the screen: small screens scroll instead of squashing the form
        java.awt.Rectangle scr = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        d.setSize(Math.min(d.getWidth() + 20, scr.width - 40), Math.min(d.getHeight(), scr.height - 60));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    // ------------------------------------------------------------------ helpers

    /** The bytes that will be sent and their content type. */
    private static final class Prepared {
        final byte[] data;
        final String contentType;

        Prepared(byte[] data, String contentType) {
            this.data = data;
            this.contentType = contentType;
        }
    }

    /** Builds the upload bytes once per combination of options (the viewer and the upload share them). Runs off the Swing thread. */
    private static Prepared prepareCached(Object[] cache, BufferedImage image, Persist.Snap snap, boolean archive, boolean enc) throws java.io.IOException {
        String key = archive + ":" + enc;
        synchronized (cache) {
            if (key.equals(cache[0])) return (Prepared) cache[1];
            byte[] png = Png.encode(image);
            Prepared p;
            if (archive) p = new Prepared(Persist.current.exportArchive(snap, png, enc), enc ? "application/octet-stream" : "application/zip");
            else if (enc) p = new Prepared(Persist.current.encryptBytes(png), "application/octet-stream");
            else p = new Prepared(png, "image/png");
            cache[0] = key;
            cache[1] = p;
            return p;
        }
    }

    /** Host of the request line of a request text ("PUT https://host/path HTTP/1.1"), lower case, or "". */
    static String hostOf(String requestText) {
        if (requestText == null) return "";
        String first = requestText.split("\\R", 2)[0].trim();
        String[] p = first.split("\\s+");
        if (p.length < 2) return "";
        try {
            String h = new java.net.URI(p[1].replace("{", "%7B").replace("}", "%7D")).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /** A file name that is safe on the remote side: the chosen base name plus the right extension. */
    static String remoteName(String base, boolean archive, boolean encrypted) {
        String b = base == null ? "" : base.trim();
        b = b.replaceAll("(?i)\\.(png|burpshot|bsv)$", "");
        b = b.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("^[. ]+", "");
        if (b.length() > 100) b = b.substring(0, 100);
        if (b.isEmpty()) b = "burpshot";
        if (archive) return b + ".burpshot";
        return b + (encrypted ? ".png.bsv" : ".png");
    }

    static String suggestedName(String title) {
        String t = title == null ? "" : title.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (t.length() > 30) t = t.substring(0, 30);
        return (t.isEmpty() ? "burpshot" : t) + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    private static Map<String, String> collect(RemoteSpec.Type t, Map<String, JComponent> comps) {
        Map<String, String> m = new LinkedHashMap<>();
        for (RemoteSpec.Field f : t.fields) {
            JComponent c = comps.get(f.key);
            String v = "";
            if (c instanceof JPasswordField) v = new String(((JPasswordField) c).getPassword());
            else if (c instanceof JTextField) v = f.secret ? ((JTextField) c).getText() : ((JTextField) c).getText().trim();
            else if (c instanceof JComboBox) v = String.valueOf(((JComboBox<?>) c).getSelectedItem());
            else if (c instanceof JCheckBox) v = String.valueOf(((JCheckBox) c).isSelected());
            else if (c instanceof JScrollPane) {
                JComponent a = comps.get(f.key + "#area");
                v = a instanceof JTextArea ? ((JTextArea) a).getText() : "";
            }
            if (f.kind == RemoteSpec.Kind.MULTILINE && comps.get(f.key + "#area") instanceof JTextArea) v = ((JTextArea) comps.get(f.key + "#area")).getText();
            m.put(f.key, v);
        }
        return m;
    }

    private static void setValue(JComponent c, JComponent area, String v) {
        if (c instanceof JPasswordField) ((JPasswordField) c).setText(v);
        else if (c instanceof JTextField) ((JTextField) c).setText(v);
        else if (c instanceof JComboBox) ((JComboBox<?>) c).setSelectedItem(v);
        else if (c instanceof JCheckBox) ((JCheckBox) c).setSelected("true".equals(v));
        else if (area instanceof JTextArea) ((JTextArea) area).setText(v);
    }

    /** Asks before sending over an unencrypted protocol or with certificate checks switched off. */
    private static boolean confirmRisk(Window parent, RemoteSpec.Type t, Map<String, String> cfg) {
        boolean insecure = "true".equals(cfg.get("insecureTls"));
        if (!t.plaintext && !insecure) return true;
        String why = (t.plaintext ? "- the protocol is not encrypted on the network\n" : "") + (insecure ? "- TLS certificate verification is switched off\n" : "");
        return JOptionPane.showConfirmDialog(parent, "Sending to this destination is not protected:\n" + why + "\nContinue?", "Unprotected transfer",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private static final class SimpleDoc implements javax.swing.event.DocumentListener {
        private final Runnable r;

        SimpleDoc(Runnable r) {
            this.r = r;
        }

        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            r.run();
        }

        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            r.run();
        }

        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            r.run();
        }
    }
}
