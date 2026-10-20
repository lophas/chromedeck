// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

/** Preserve Unix links and modes without ever writing through archive-created symlinks. */
public final class RootfsArchive {
    private RootfsArchive() {}
    public interface Progress { void update(long bytes); }
    private static final class Link {
        final String name, target;
        Link(String name, String target) { this.name = name; this.target = target; }
    }
    private static final class DirectoryMode {
        final File file;
        final int mode;
        DirectoryMode(File file, int mode) { this.file = file; this.mode = mode; }
    }

    public static void unpack(File archive, File destination, long expectedBytes, Progress progress) throws Exception {
        if (!destination.isDirectory() && !destination.mkdirs())
            throw new IOException("Cannot create the Debian installation directory.");
        File root = destination.getCanonicalFile();
        List<Link> hardLinks = new ArrayList<>();
        List<Link> symbolicLinks = new ArrayList<>();
        List<DirectoryMode> modes = new ArrayList<>();
        long total = 0;
        long limit = Math.max(1024L * 1024 * 1024, expectedBytes * 2);
        byte[] buffer = new byte[65536];
        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive), 65536);
             GZIPInputStream gzip = new GZIPInputStream(raw, 65536);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedIOException("Debian installation interrupted.");
                File output = safe(root, entry.getName());
                if (entry.isSymbolicLink()) {
                    symbolicLinks.add(new Link(entry.getName(), entry.getLinkName()));
                } else if (entry.isLink()) {
                    hardLinks.add(new Link(entry.getName(), entry.getLinkName()));
                } else if (entry.isDirectory()) {
                    mkdir(output);
                    modes.add(new DirectoryMode(output, entry.getMode() & 0777));
                } else if (entry.isFile()) {
                    if (!tar.canReadEntryData(entry))
                        throw new IOException("Unsupported Debian archive entry: " + entry.getName());
                    mkdir(output.getParentFile());
                    long written = 0;
                    try (FileOutputStream file = new FileOutputStream(output)) {
                        int count;
                        while ((count = tar.read(buffer)) != -1) {
                            file.write(buffer, 0, count);
                            written += count;
                            total += count;
                            if (total > limit)
                                throw new IOException("Debian archive exceeds its expected installed size.");
                            progress.update(total);
                        }
                    }
                    if (written != entry.getSize())
                        throw new IOException("Incomplete Debian archive entry: " + entry.getName());
                    Os.chmod(output.getAbsolutePath(), entry.getMode() & 0777);
                    output.setLastModified(entry.getLastModifiedDate().getTime());
                } else if (!(entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO())) {
                    throw new IOException("Unexpected Debian archive entry: " + entry.getName());
                }
                // Device nodes are supplied by the /dev bind at runtime.
            }
        }

        while (!hardLinks.isEmpty()) {
            boolean changed = false;
            Iterator<Link> iterator = hardLinks.iterator();
            while (iterator.hasNext()) {
                Link link = iterator.next();
                File source = safe(root, link.target);
                File output = safe(root, link.name);
                if (!source.isFile()) continue;
                mkdir(output.getParentFile());
                Os.link(source.getAbsolutePath(), output.getAbsolutePath());
                iterator.remove();
                changed = true;
            }
            if (!changed) throw new IOException("Unresolved hard links in Debian archive.");
        }
        // Creating these last allows absolute guest links (/run, /usr/...) to stay intact
        // without letting Java's filesystem operations follow them out of the staging tree.
        for (Link link : symbolicLinks) {
            File output = safe(root, link.name);
            mkdir(output.getParentFile());
            Os.symlink(link.target, output.getAbsolutePath());
        }
        modes.sort((a, b) -> Integer.compare(b.file.getPath().length(), a.file.getPath().length()));
        for (DirectoryMode mode : modes)
            Os.chmod(mode.file.getAbsolutePath(), mode.mode);
    }

    private static File safe(File root, String name) throws IOException {
        if (name.startsWith("/") || name.indexOf(0) >= 0)
            throw new IOException("Invalid path in Debian archive.");
        File output = new File(root, name).getCanonicalFile();
        if (!output.equals(root) && !output.getPath().startsWith(root.getPath() + File.separator))
            throw new IOException("Debian archive path escapes the installation directory.");
        return output;
    }

    private static void mkdir(File directory) throws IOException {
        if (directory != null && !directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create " + directory);
    }

    /** Only used for an unfinished staging tree, never the installed browser profile. */
    public static void deleteStaging(File file) throws Exception {
        final android.system.StructStat stat;
        try { stat = Os.lstat(file.getAbsolutePath()); }
        catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return;
            throw e;
        }
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            Os.chmod(file.getAbsolutePath(), 0700);
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot list incomplete installation.");
            for (File child : children) deleteStaging(child);
        }
        if (!file.delete())
            throw new IOException("Cannot remove incomplete installation: " + file);
    }
}
