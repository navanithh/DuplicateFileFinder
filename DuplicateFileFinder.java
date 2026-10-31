import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/**
 * Duplicate File Finder
 * Usage: java DuplicateFileFinder <folder> [--delete]
 *
 * Step 1: walk the folder tree and group files by size.
 * Step 2: hash only files that share a size (SHA-256).
 * Step 3: report duplicate groups and wasted space; optionally delete extras.
 */
public class DuplicateFileFinder {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java DuplicateFileFinder <folder> [--delete]");
            return;
        }
        Path root = Paths.get(args[0]);
        boolean delete = args.length > 1 && args[1].equals("--delete");

        if (!Files.isDirectory(root)) {
            System.out.println("Not a valid folder: " + root);
            return;
        }

        // Step 1: group by size
        Map<Long, List<Path>> bySize = new HashMap<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && attrs.size() > 0) {
                    bySize.computeIfAbsent(attrs.size(), k -> new ArrayList<>()).add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                System.err.println("Skipped (no access): " + file);
                return FileVisitResult.CONTINUE;
            }
        });

        // Step 2: hash files that share a size
        List<List<Path>> duplicateGroups = new ArrayList<>();
        for (List<Path> sameSize : bySize.values()) {
            if (sameSize.size() < 2) continue;
            Map<String, List<Path>> byHash = new HashMap<>();
            for (Path p : sameSize) {
                try {
                    byHash.computeIfAbsent(hash(p), k -> new ArrayList<>()).add(p);
                } catch (Exception e) {
                    System.err.println("Could not hash: " + p);
                }
            }
            for (List<Path> g : byHash.values()) {
                if (g.size() > 1) duplicateGroups.add(g);
            }
        }

        // Step 3: report
        if (duplicateGroups.isEmpty()) {
            System.out.println("No duplicates found.");
            return;
        }

        long wasted = 0;
        int n = 1;
        for (List<Path> group : duplicateGroups) {
            long size = Files.size(group.get(0));
            wasted += size * (group.size() - 1);
            System.out.printf("%nGroup %d (%s each):%n", n++, readable(size));
            for (Path p : group) System.out.println("  " + p);
        }
        System.out.printf("%nDuplicate groups: %d | Wasted space: %s%n",
                duplicateGroups.size(), readable(wasted));

        if (delete) deleteExtras(duplicateGroups);
    }

    private static String hash(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) md.update(buf, 0, read);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Keeps the first file in each group, deletes the rest after confirmation. */
    private static void deleteExtras(List<List<Path>> groups) throws IOException {
        Scanner sc = new Scanner(System.in);
        System.out.print("\nDelete all extra copies (keeps first of each group)? (yes/no): ");
        if (!sc.nextLine().trim().equalsIgnoreCase("yes")) {
            System.out.println("Cancelled.");
            return;
        }
        int deleted = 0;
        for (List<Path> group : groups) {
            for (int i = 1; i < group.size(); i++) {
                Files.delete(group.get(i));
                deleted++;
            }
        }
        System.out.println("Deleted " + deleted + " files.");
    }

    private static String readable(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }
}
