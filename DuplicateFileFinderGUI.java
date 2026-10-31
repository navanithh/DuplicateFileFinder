import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Duplicate File Finder (Swing GUI, multi-threaded hashing)
 *
 * Compile: javac DuplicateFileFinderGUI.java
 * Run:     java DuplicateFileFinderGUI
 *
 * Features:
 *  - Folder picker, minimum-size filter, extension filter
 *  - Files grouped by size first, then hashed in parallel (SHA-256)
 *  - Progress bar + cancel button
 *  - Results table with checkboxes
 *  - "Auto-select" keeps the first file of each group
 *  - Safe delete: move to a trash folder (default) or delete permanently
 *  - Export results to CSV
 */
public class DuplicateFileFinderGUI extends JFrame {

    private static final String TRASH_DIR = "_DuplicateTrash";

    private final JTextField folderField = new JTextField(30);
    private final JTextField minSizeField = new JTextField("1", 5);
    private final JTextField extField = new JTextField(8);
    private final JButton scanBtn = new JButton("Scan");
    private final JButton cancelBtn = new JButton("Cancel");
    private final JButton autoSelectBtn = new JButton("Auto-select (keep first)");
    private final JButton clearBtn = new JButton("Clear selection");
    private final JButton deleteBtn = new JButton("Remove selected");
    private final JButton exportBtn = new JButton("Export CSV");
    private final JCheckBox trashBox = new JCheckBox("Move to trash folder (safe)", true);
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel status = new JLabel("Choose a folder and click Scan.");

    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Remove", "Group", "File", "Size"}, 0) {
        @Override public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : c == 1 ? Integer.class : String.class;
        }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
    };
    private final JTable table = new JTable(model);

    private ScanWorker worker;

    public DuplicateFileFinderGUI() {
        super("Duplicate File Finder");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));

        // Top: inputs
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton browse = new JButton("Browse...");
        top.add(new JLabel("Folder:"));
        top.add(folderField);
        top.add(browse);
        top.add(new JLabel("Min size (KB):"));
        top.add(minSizeField);
        top.add(new JLabel("Extension:"));
        top.add(extField);
        extField.setToolTipText("e.g. jpg (leave blank for all)");
        top.add(scanBtn);
        top.add(cancelBtn);
        cancelBtn.setEnabled(false);
        add(top, BorderLayout.NORTH);

        // Center: table
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(1).setMaxWidth(60);
        table.getColumnModel().getColumn(3).setMaxWidth(100);
        table.setAutoCreateRowSorter(false);
        add(new JScrollPane(table), BorderLayout.CENTER);

        // Bottom: actions + status
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(autoSelectBtn);
        actions.add(clearBtn);
        actions.add(trashBox);
        actions.add(deleteBtn);
        actions.add(exportBtn);
        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(progress, BorderLayout.CENTER);
        bottom.add(status, BorderLayout.SOUTH);
        bottom.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        add(bottom, BorderLayout.SOUTH);

        // Listeners
        browse.addActionListener(e -> chooseFolder());
        scanBtn.addActionListener(e -> startScan());
        cancelBtn.addActionListener(e -> { if (worker != null) worker.cancel(true); });
        autoSelectBtn.addActionListener(e -> autoSelect());
        clearBtn.addActionListener(e -> setAllChecked(false));
        deleteBtn.addActionListener(e -> removeSelected());
        exportBtn.addActionListener(e -> exportCsv());

        setSize(950, 560);
        setLocationRelativeTo(null);
    }

    // ---------- UI actions ----------

    private void chooseFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            folderField.setText(fc.getSelectedFile().getAbsolutePath());
        }
    }

    private void startScan() {
        Path root = Paths.get(folderField.getText().trim());
        if (!Files.isDirectory(root)) {
            JOptionPane.showMessageDialog(this, "Please choose a valid folder.");
            return;
        }
        long minBytes;
        try {
            minBytes = Math.max(0, Long.parseLong(minSizeField.getText().trim())) * 1024;
        } catch (NumberFormatException ex) {
            JOptionPane.showMessageDialog(this, "Min size must be a number.");
            return;
        }
        String ext = extField.getText().trim().toLowerCase();
        if (ext.startsWith(".")) ext = ext.substring(1);

        model.setRowCount(0);
        progress.setValue(0);
        setScanning(true);
        status.setText("Scanning files...");
        worker = new ScanWorker(root, minBytes, ext);
        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) progress.setValue((Integer) evt.getNewValue());
        });
        worker.execute();
    }

    private void setScanning(boolean scanning) {
        scanBtn.setEnabled(!scanning);
        cancelBtn.setEnabled(scanning);
    }

    private void autoSelect() {
        int lastGroup = -1;
        for (int r = 0; r < model.getRowCount(); r++) {
            int g = (Integer) model.getValueAt(r, 1);
            model.setValueAt(g == lastGroup, r, 0); // first row of each group stays unchecked
            lastGroup = g;
        }
    }

    private void setAllChecked(boolean value) {
        for (int r = 0; r < model.getRowCount(); r++) model.setValueAt(value, r, 0);
    }

    private void removeSelected() {
        List<Integer> rows = new ArrayList<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            if ((Boolean) model.getValueAt(r, 0)) rows.add(r);
        }
        if (rows.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No files selected.");
            return;
        }
        // Safety: never allow removing every copy in a group
        Map<Integer, Integer> total = new HashMap<>(), checked = new HashMap<>();
        for (int r = 0; r < model.getRowCount(); r++) {
            int g = (Integer) model.getValueAt(r, 1);
            total.merge(g, 1, Integer::sum);
            if ((Boolean) model.getValueAt(r, 0)) checked.merge(g, 1, Integer::sum);
        }
        for (Map.Entry<Integer, Integer> e : checked.entrySet()) {
            if (e.getValue().equals(total.get(e.getKey()))) {
                JOptionPane.showMessageDialog(this,
                        "Group " + e.getKey() + " has every copy selected.\nKeep at least one file per group.");
                return;
            }
        }
        boolean useTrash = trashBox.isSelected();
        int ok = JOptionPane.showConfirmDialog(this,
                (useTrash ? "Move " : "Permanently delete ") + rows.size() + " file(s)?",
                "Confirm", JOptionPane.YES_NO_OPTION);
        if (ok != JOptionPane.YES_OPTION) return;

        int done = 0;
        long freed = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            int r = rows.get(i);
            Path p = Paths.get((String) model.getValueAt(r, 2));
            try {
                long size = Files.size(p);
                if (useTrash) moveToTrash(p); else Files.delete(p);
                freed += size;
                done++;
                model.removeRow(r);
            } catch (IOException ex) {
                System.err.println("Failed: " + p + " (" + ex.getMessage() + ")");
            }
        }
        status.setText("Removed " + done + " file(s), freed " + readable(freed) + ".");
    }

    private void moveToTrash(Path file) throws IOException {
        Path root = Paths.get(folderField.getText().trim());
        Path trash = root.resolve(TRASH_DIR);
        Files.createDirectories(trash);
        Path target = trash.resolve(file.getFileName());
        int n = 1;
        while (Files.exists(target)) {
            target = trash.resolve((n++) + "_" + file.getFileName());
        }
        Files.move(file, target);
    }

    private void exportCsv() {
        if (model.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "Nothing to export.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("duplicates.csv"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        StringBuilder sb = new StringBuilder("group,file,size\n");
        for (int r = 0; r < model.getRowCount(); r++) {
            String path = ((String) model.getValueAt(r, 2)).replace("\"", "\"\"");
            sb.append(model.getValueAt(r, 1)).append(",\"").append(path).append("\",")
              .append(model.getValueAt(r, 3)).append('\n');
        }
        try {
            Files.writeString(fc.getSelectedFile().toPath(), sb.toString());
            status.setText("Exported to " + fc.getSelectedFile());
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage());
        }
    }

    // ---------- Background scan ----------

    private class ScanWorker extends SwingWorker<List<List<Path>>, Void> {
        private final Path root;
        private final long minBytes;
        private final String ext;

        ScanWorker(Path root, long minBytes, String ext) {
            this.root = root;
            this.minBytes = minBytes;
            this.ext = ext;
        }

        @Override
        protected List<List<Path>> doInBackground() throws Exception {
            // Phase 1: group by size
            Map<Long, List<Path>> bySize = new HashMap<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                    if (isCancelled()) return FileVisitResult.TERMINATE;
                    return dir.getFileName() != null && dir.getFileName().toString().equals(TRASH_DIR)
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) {
                    if (isCancelled()) return FileVisitResult.TERMINATE;
                    if (a.isRegularFile() && a.size() > 0 && a.size() >= minBytes
                            && (ext.isEmpty() || f.toString().toLowerCase().endsWith("." + ext))) {
                        bySize.computeIfAbsent(a.size(), k -> new ArrayList<>()).add(f);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    return FileVisitResult.CONTINUE; // skip unreadable files
                }
            });

            List<Path> candidates = new ArrayList<>();
            for (List<Path> g : bySize.values()) if (g.size() > 1) candidates.addAll(g);
            if (candidates.isEmpty()) return new ArrayList<>();

            // Phase 2: hash candidates in parallel
            int threads = Runtime.getRuntime().availableProcessors();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            AtomicInteger done = new AtomicInteger();
            int total = candidates.size();
            Map<String, List<Path>> byHash = new ConcurrentHashMap<>();
            List<Future<?>> futures = new ArrayList<>();

            for (Path p : candidates) {
                futures.add(pool.submit(() -> {
                    if (isCancelled()) return;
                    try {
                        String h = hash(p);
                        byHash.computeIfAbsent(h, k -> new CopyOnWriteArrayList<>()).add(p);
                    } catch (Exception ignored) { }
                    setProgress(done.incrementAndGet() * 100 / total);
                }));
            }
            pool.shutdown();
            for (Future<?> f : futures) {
                if (isCancelled()) { pool.shutdownNow(); break; }
                f.get();
            }

            List<List<Path>> groups = new ArrayList<>();
            for (List<Path> g : byHash.values()) {
                if (g.size() > 1) {
                    List<Path> sorted = new ArrayList<>(g);
                    sorted.sort(null); // stable order: first path alphabetically is "kept"
                    groups.add(sorted);
                }
            }
            // Biggest wasted space first
            groups.sort((a, b) -> Long.compare(wasted(b), wasted(a)));
            return groups;
        }

        private long wasted(List<Path> g) {
            try { return Files.size(g.get(0)) * (g.size() - 1); } catch (IOException e) { return 0; }
        }

        @Override
        protected void done() {
            setScanning(false);
            if (isCancelled()) {
                status.setText("Scan cancelled.");
                return;
            }
            try {
                List<List<Path>> groups = get();
                long wastedTotal = 0;
                int gi = 1;
                for (List<Path> g : groups) {
                    long size = Files.size(g.get(0));
                    wastedTotal += size * (g.size() - 1);
                    for (Path p : g) {
                        model.addRow(new Object[]{false, gi, p.toString(), readable(size)});
                    }
                    gi++;
                }
                progress.setValue(100);
                status.setText(groups.isEmpty()
                        ? "No duplicates found."
                        : groups.size() + " duplicate group(s) found. Wasted space: " + readable(wastedTotal));
            } catch (Exception ex) {
                status.setText("Scan failed: " + ex.getMessage());
            }
        }
    }

    // ---------- Helpers ----------

    private static String hash(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String readable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new DuplicateFileFinderGUI().setVisible(true));
    }
}
