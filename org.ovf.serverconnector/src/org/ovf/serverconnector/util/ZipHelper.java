package org.ovf.serverconnector.util;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.zip.*;

/**
 * Utility for zipping/unzipping workflow directories for server transfer.
 */
public final class ZipHelper {

    private ZipHelper() {}

    /**
     * Zips a directory into a byte array, with the directory name as root entry.
     * E.g., dir "MyWorkflow" produces entries: MyWorkflow/workflow.knime, etc.
     */
    public static byte[] zipDirectory(File dir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Path basePath = dir.toPath();
            String rootName = dir.getName();
            Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    String entryName = rootName + "/" + basePath.relativize(file).toString();
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    String entryName = rootName + "/" + basePath.relativize(dir).toString();
                    if (!entryName.equals(rootName + "/")) {
                        zos.putNextEntry(new ZipEntry(entryName + "/"));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return baos.toByteArray();
    }

    /**
     * Zips the CONTENTS of a directory (no wrapping directory entry).
     * E.g., dir "MyWorkflow" containing workflow.knime produces entries:
     * workflow.knime, settings.xml, etc. (no MyWorkflow/ prefix).
     * Used for upload where the server creates the target directory.
     */
    public static byte[] zipDirectoryContents(File dir) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            Path basePath = dir.toPath();
            Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    String entryName = basePath.relativize(file).toString();
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                    String rel = basePath.relativize(dir).toString();
                    if (!rel.isEmpty()) {
                        zos.putNextEntry(new ZipEntry(rel + "/"));
                        zos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return baos.toByteArray();
    }

    /**
     * Unzips a byte array into a target directory.
     */
    public static void unzipToDirectory(byte[] zipData, File targetDir) throws IOException {
        targetDir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(targetDir, entry.getName());
                if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator)
                        && !outFile.getCanonicalPath().equals(targetDir.getCanonicalPath())) {
                    throw new IOException("Zip entry outside target dir: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Unzips a byte array, stripping the first path component.
     * E.g., zip containing "WorkflowName/workflow.knime" extracts to
     * targetDir/workflow.knime (not targetDir/WorkflowName/workflow.knime).
     * Used for download where the server zip wraps files in a directory.
     */
    public static void unzipStripRoot(byte[] zipData, File targetDir) throws IOException {
        targetDir.mkdirs();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                // Strip first path component (e.g., "WorkflowName/file.xml" -> "file.xml")
                int slash = name.indexOf('/');
                if (slash >= 0 && slash < name.length() - 1) {
                    name = name.substring(slash + 1);
                } else if (slash >= 0) {
                    // This is just the root directory entry, skip it
                    zis.closeEntry();
                    continue;
                }
                if (name.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }

                File outFile = new File(targetDir, name);
                if (!outFile.getCanonicalPath().startsWith(targetDir.getCanonicalPath() + File.separator)
                        && !outFile.getCanonicalPath().equals(targetDir.getCanonicalPath())) {
                    throw new IOException("Zip entry outside target dir: " + name);
                }
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    outFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = zis.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }
}
