// SPDX-License-Identifier: GPL-3.0-only
package com.termux.x11;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Resumable HTTPS downloads whose final bytes must match the bundled manifest. */
public final class CheckedDownload {
    private CheckedDownload() {}
    public interface Progress { void update(int percent); }

    public static File fetch(String url, String expectedSha, long expectedSize,
                             File target, Progress progress) throws Exception {
        if (!expectedSha.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Invalid download checksum in runtime manifest.");
        File directory = target.getParentFile();
        if (directory != null && !directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Cannot create the download directory.");
        if (target.isFile() && matches(target, expectedSha)) {
            progress.update(100);
            return target;
        }
        if (target.exists() && !target.delete())
            throw new IOException("Cannot replace " + target.getName());
        File partial = new File(target.getPath() + ".part");
        long offset = partial.isFile() ? partial.length() : 0;
        if (expectedSize > 0 && offset > expectedSize) {
            if (!partial.delete()) throw new IOException("Cannot reset partial download.");
            offset = 0;
        }

        if (offset > 0 && matches(partial, expectedSha)) {
            if (!partial.renameTo(target))
                throw new IOException("Cannot finalize the verified download.");
            progress.update(100);
            return target;
        }
        HttpURLConnection connection = open(url, offset);
        try {
            int status = connection.getResponseCode();
            if (status == 416) {
                partial.delete();
                throw new IOException("The partial download must be restarted. Please retry.");
            }
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL)
                throw new IOException("Download returned HTTP " + status + ": " + url);
            if (status == HttpURLConnection.HTTP_PARTIAL) {
                String range = connection.getHeaderField("Content-Range");
                if (range == null || !range.startsWith("bytes " + offset + "-"))
                    throw new IOException("Invalid resume response from download server.");
            } else {
                offset = 0;
            }
            long responseSize = connection.getContentLengthLong();
            long total = expectedSize > 0 ? expectedSize : responseSize > 0 ? offset + responseSize : -1;
            long done = offset;
            try (InputStream input = new BufferedInputStream(connection.getInputStream(), 65536);
                 FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedIOException("Download interrupted.");
                    output.write(buffer, 0, count);
                    done += count;
                    if (expectedSize > 0 && done > expectedSize)
                        throw new IOException("Download is larger than the pinned runtime image.");
                    progress.update(total > 0 ? (int) Math.min(100, 100 * done / total) : -1);
                }
                output.getFD().sync();
            }
        } finally {
            connection.disconnect();
        }

        if (expectedSize > 0 && partial.length() != expectedSize)
            throw new IOException("Download ended early; it can be resumed on the next launch.");
        progress.update(-1);
        if (!matches(partial, expectedSha)) {
            partial.delete();
            throw new IOException("Download checksum mismatch. Please retry.");
        }
        if (!partial.renameTo(target))
            throw new IOException("Cannot finalize the verified download.");
        progress.update(100);
        return target;
    }

    private static HttpURLConnection open(String initialUrl, long offset) throws IOException {
        String current = initialUrl;
        for (int redirects = 0; redirects < 8; redirects++) {
            URL url = new URL(current);
            if (!"https".equalsIgnoreCase(url.getProtocol()))
                throw new IOException("Runtime downloads must use HTTPS.");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "DesktopChromeAndroid/0.1");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0)
                connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status;
            try { status = connection.getResponseCode(); }
            catch (IOException e) { connection.disconnect(); throw e; }
            if (status >= 300 && status <= 399) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null)
                    throw new IOException("Download redirect has no location.");
                current = new URL(url, location).toString();
                continue;
            }
            return connection;
        }
        throw new IOException("Too many download redirects.");
    }

    public static boolean matches(File file, String expected) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new FileInputStream(file), 65536)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1)
                digest.update(buffer, 0, count);
        }
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest())
            actual.append(String.format(Locale.ROOT, "%02x", value & 255));
        return actual.toString().equalsIgnoreCase(expected);
    }
}
