package ptshot;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Drives the real Storage dialog like a user: choose "A folder", type a path, set an encryption password, press OK - then checks that
 * nothing was reverted, in the live state and in the saved preferences. (Needs a display; it opens real dialogs.)
 */
public class StorageDialogSelfTest {
    static int fails = 0;

    static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + name);
        if (!ok) fails++;
    }

    static <T extends Component> List<T> find(Container c, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (Component x : c.getComponents()) {
            if (type.isInstance(x)) out.add(type.cast(x));
            if (x instanceof Container) out.addAll(find((Container) x, type));
        }
        return out;
    }

    static JDialog dialogTitled(String title) {
        for (Window w : Window.getWindows()) if (w instanceof JDialog && w.isShowing() && title.equals(((JDialog) w).getTitle())) return (JDialog) w;
        return null;
    }

    /** Any showing dialog other than the Storage dialog (a JOptionPane). */
    static JDialog otherDialog() {
        for (Window w : Window.getWindows()) if (w instanceof JDialog && w.isShowing() && !"Storage and encryption".equals(((JDialog) w).getTitle())) return (JDialog) w;
        return null;
    }

    static AbstractButton button(Container c, String text) {
        for (AbstractButton b : find(c, AbstractButton.class)) if (text.equals(b.getText())) return b;
        return null;
    }

    static void later(int ms, Runnable r) {
        Timer t = new Timer(ms, e -> {
            try {
                r.run();
            } catch (Throwable ex) {
                ex.printStackTrace();
                fails++;
            }
        });
        t.setRepeats(false);
        t.start();
    }

    public static void main(String[] a) throws Exception {
        final Path tmp = Files.createTempDirectory("burpshot-storage-ui");
        final CoreSelfTest.FakeEnv env = new CoreSelfTest.FakeEnv("My Project");
        final Persist store = new Persist(env);
        final CountDownLatch finished = new CountDownLatch(1);
        final boolean[] dialogStillFolder = {false};
        final String[] fieldAfter = {null};

        SwingUtilities.invokeAndWait(() -> {
            MainPanel mp = new MainPanel(new Settings(), s -> { }, c -> { }, store);
            JFrame f = new JFrame();
            f.add(mp);
            f.setSize(900, 600);
            f.setLocation(-2400, 0);
            f.setVisible(true);

            later(600, () -> { // the user picks "A folder", types the path and clicks "Encrypt with a password..."
                JDialog d = dialogTitled("Storage and encryption");
                for (JRadioButton r : find(d, JRadioButton.class)) if ("A folder:".equals(r.getText())) r.doClick();
                find(d, JTextField.class).get(0).setText(tmp.toString());
                button(d, "Encrypt with a password...").doClick(); // opens the modal password prompt
            });
            later(1400, () -> { // ... types the password twice and presses OK in the prompt
                JDialog prompt = otherDialog();
                List<JPasswordField> pws = find(prompt, JPasswordField.class);
                pws.get(0).setText("correct horse battery");
                pws.get(1).setText("correct horse battery");
                button(prompt, "OK").doClick();
            });
            later(3600, () -> { // the password was set and the dialog refreshed itself: the pending folder choice must still be there
                JDialog d = dialogTitled("Storage and encryption");
                for (JRadioButton r : find(d, JRadioButton.class)) if ("A folder:".equals(r.getText())) dialogStillFolder[0] = r.isSelected();
                fieldAfter[0] = find(d, JTextField.class).get(0).getText();
                button(d, "OK").doClick(); // now apply the location
            });
            later(5200, () -> { // "Captures are now saved to ..." message
                JDialog info = otherDialog();
                if (info != null) button(info, "OK").doClick();
            });
            later(6000, finished::countDown);
            StorageDialog.open(mp, store); // modal: returns when the dialog closes
            f.dispose();
        });
        finished.await(30, TimeUnit.SECONDS);
        Thread.sleep(300);

        check("after setting the password the folder choice was NOT reverted (radio)", dialogStillFolder[0]);
        check("after setting the password the folder path was NOT reverted (text field)", tmp.toString().equals(fieldAfter[0]));
        check("OK applied the location: mode is folder", store.mode() == Persist.Mode.DIRECTORY);
        check("OK applied the location: folder is the chosen one", tmp.toString().equals(store.dir()));
        check("encryption is on and unlocked", store.encrypt() && store.unlocked());
        check("saved preferences: mode", "DIRECTORY".equals(env.prefs.get("ptshot.store.mode")));
        check("saved preferences: folder", tmp.toString().equals(env.prefs.get("ptshot.store.dir")));
        check("saved preferences: encryption flag", "true".equals(env.prefs.get("ptshot.store.encrypt")));
        check("the project folder exists inside the chosen folder", Files.isDirectory(tmp.resolve(DirectoryBackend.safeName("My Project"))));

        // a "new Burp session" with the same saved preferences starts in the same place, with encryption still on
        Persist again = new Persist(env);
        check("restart: location and encryption survive", again.mode() == Persist.Mode.DIRECTORY && tmp.toString().equals(again.dir()) && again.encrypt() && !again.unlocked());
        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        System.exit(fails == 0 ? 0 : 1);
    }
}
