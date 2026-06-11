package org.knime.serverconnector.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.knime.serverconnector.model.WorkflowNode;
import org.knime.serverconnector.model.WorkflowNode.Location;
import org.knime.serverconnector.model.WorkflowNode.NodeType;
import org.knime.serverconnector.util.WorkflowContentIndex;
import org.knime.serverconnector.util.WorkflowContentIndex.Field;
import org.knime.serverconnector.util.ZipHelper;

/**
 * Filesystem-backed {@link ServerBackend}. Treats a configured directory
 * as the "server root" -- browse lists subdirectories, upload unzips
 * into the root, download zips the named workflow back up. Multiple
 * KNIME installations pointing at the same mount can share workflows
 * via the local filesystem without any HTTP server in between.
 *
 * Directories containing a {@code workflow.knime} file are classified
 * as workflows (matching the Flask backend's heuristic).
 *
 * Paths are mount-relative and begin with '/'.
 */
public class LocalServerBackend implements ServerBackend {

    private final File mountRoot;

    public LocalServerBackend(String mountPath) {
        if (mountPath == null || mountPath.isEmpty()) {
            throw new IllegalArgumentException("Local mount path is required");
        }
        // Strip a leading file:// if the user pasted a file URI.
        String p = mountPath;
        if (p.startsWith("file://")) p = p.substring("file://".length());
        this.mountRoot = new File(p);
    }

    public File getMountRoot() {
        return mountRoot;
    }

    @Override
    public boolean isHealthy() {
        return mountRoot.isDirectory() && mountRoot.canRead();
    }

    @Override
    public Map<String, Object> testConnection() throws IOException {
        if (!mountRoot.exists()) {
            throw new IOException("Local path does not exist: " + mountRoot);
        }
        if (!mountRoot.isDirectory()) {
            throw new IOException("Local path is not a directory: " + mountRoot);
        }
        if (!mountRoot.canRead()) {
            throw new IOException("Local path is not readable: " + mountRoot);
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("status", "ok");
        info.put("mount", mountRoot.getAbsolutePath());
        info.put("writable", mountRoot.canWrite());
        return info;
    }

    @Override
    public List<WorkflowNode> browse(String path) throws IOException {
        File dir = resolve(path);
        if (!dir.isDirectory()) {
            throw new IOException("Not a directory: " + path);
        }
        File[] entries = dir.listFiles();
        if (entries == null) return new ArrayList<>();
        // Stable alphabetical ordering (case-insensitive), matching the
        // expectation set by the HTTP server.
        Arrays.sort(entries, Comparator.comparing(f -> f.getName().toLowerCase()));

        List<WorkflowNode> nodes = new ArrayList<>(entries.length);
        for (File entry : entries) {
            String name = entry.getName();
            if (name.startsWith(".")) continue; // hide dotfiles, mirroring server
            String relPath = (path.endsWith("/") ? path : path + "/") + name;
            if (!relPath.startsWith("/")) relPath = "/" + relPath;
            NodeType type;
            if (entry.isDirectory()) {
                type = isWorkflowDir(entry) ? NodeType.WORKFLOW : NodeType.DIRECTORY;
            } else {
                type = NodeType.FILE;
            }
            nodes.add(new WorkflowNode(
                    name,
                    relPath,
                    type,
                    Location.SERVER,
                    entry.length(),
                    entry.lastModified()));
        }
        return nodes;
    }

    @Override
    public byte[] download(String path) throws IOException {
        File source = resolve(path);
        if (!source.exists()) {
            throw new IOException("No such path: " + path);
        }
        if (!source.isDirectory()) {
            // Wrap a single file in a one-entry zip so the caller's unzip
            // semantics still work.
            return ZipHelper.zipDirectory(source.getParentFile());
        }
        return ZipHelper.zipDirectory(source);
    }

    @Override
    public void upload(String path, byte[] zipData) throws IOException {
        File target = resolve(path);
        if (target.exists()) {
            // Match server's overwrite semantics: nuke and replace.
            deleteRecursive(target);
        }
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("Could not create target directory: " + target);
        }
        ZipHelper.unzipToDirectory(zipData, target);
    }

    @Override
    public void delete(String path) throws IOException {
        File target = resolve(path);
        if (!target.exists()) {
            throw new IOException("No such path: " + path);
        }
        deleteRecursive(target);
    }

    @Override
    public void mkdir(String path) throws IOException {
        File target = resolve(path);
        if (target.exists()) {
            if (!target.isDirectory()) {
                throw new IOException("Path exists and is not a directory: " + path);
            }
            return;
        }
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("Could not create directory: " + target);
        }
    }

    @Override
    public void move(String srcPath, String destPath) throws IOException {
        File src = resolve(srcPath);
        File dest = resolve(destPath);
        if (!src.exists()) {
            throw new IOException("No such path: " + srcPath);
        }
        if (dest.exists()) {
            throw new IOException("Destination already exists: " + destPath);
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public String urlFor(String path) {
        File f = resolve(path);
        return f.toURI().toString();
    }

    @Override
    public String stateSignature() throws IOException {
        if (!mountRoot.isDirectory()) return "";
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
        walkForSignature(mountRoot, "", md);
        byte[] hash = md.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private void walkForSignature(File dir, String relPath, MessageDigest md) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        Arrays.sort(entries, Comparator.comparing(File::getName));
        for (File entry : entries) {
            String name = entry.getName();
            if (name.startsWith(".")) continue;
            String rel = relPath.isEmpty() ? name : relPath + "/" + name;
            if (entry.isDirectory()) {
                md.update((rel + "|D|" + entry.lastModified() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                walkForSignature(entry, rel, md);
            } else {
                md.update((rel + "|F|" + entry.lastModified() + "|" + entry.length() + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    @Override
    public List<WorkflowNode> deepSearch(String query, EnumSet<Field> fields) {
        List<WorkflowNode> hits = new ArrayList<>();
        if (query == null || query.isEmpty() || fields == null || fields.isEmpty()) {
            return hits;
        }
        walkForMatches(mountRoot, "", query, fields, hits);
        return hits;
    }

    private void walkForMatches(File dir, String relPath, String query,
                                EnumSet<Field> fields, List<WorkflowNode> hits) {
        File[] entries = dir.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!entry.isDirectory() || entry.getName().startsWith(".")) continue;
            String childRel = relPath + "/" + entry.getName();
            if (isWorkflowDir(entry)) {
                if (WorkflowContentIndex.matches(entry, query, fields)) {
                    hits.add(new WorkflowNode(
                            entry.getName(), childRel, NodeType.WORKFLOW,
                            Location.SERVER, 0L, entry.lastModified()));
                }
                // Don't descend into workflow guts -- node directories
                // aren't browseable from the tree anyway.
                continue;
            }
            walkForMatches(entry, childRel, query, fields, hits);
        }
    }

    // ---- helpers ----

    /** Resolve mount-relative path to an absolute File, blocking traversal. */
    private File resolve(String relPath) {
        String clean = (relPath == null || relPath.isEmpty()) ? "/" : relPath;
        if (!clean.startsWith("/")) clean = "/" + clean;
        // Normalize and reject anything that escapes the mount root.
        Path base = mountRoot.toPath().toAbsolutePath().normalize();
        Path requested = base.resolve(clean.substring(1)).normalize();
        if (!requested.startsWith(base)) {
            throw new SecurityException("Path escapes mount root: " + relPath);
        }
        return requested.toFile();
    }

    private static boolean isWorkflowDir(File dir) {
        return new File(dir, "workflow.knime").isFile();
    }

    private static void deleteRecursive(File target) throws IOException {
        if (!target.exists()) return;
        Files.walkFileTree(target.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
