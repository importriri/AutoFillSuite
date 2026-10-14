package app.core;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Replaces a file whole or not at all: a sibling temp file is written and
 * forced, then renamed over the target (atomic where the filesystem allows,
 * plain replace otherwise) — the SettingsManager.save pattern. A crash at any
 * moment leaves either the old file intact or the new one complete, never a
 * file cut in half.
 */
public final class AtomicFile {

    /** Test hook: runs between the forced temp file and the rename (a power cut). */
    static Runnable beforeMove;
    /** Test hook: how many bytes of the temp file to write before "crashing" (-1 = all). */
    static int crashAfterBytes = -1;

    private AtomicFile() {}

    public static void write(Path target, byte[] bytes) throws IOException {
        File t = target.toFile();
        File dir = t.getAbsoluteFile().getParentFile();
        File tmp = new File(dir, t.getName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            int len = crashAfterBytes >= 0 ? Math.min(crashAfterBytes, bytes.length) : bytes.length;
            ByteBuffer buf = ByteBuffer.wrap(bytes, 0, len);
            while (buf.hasRemaining()) ch.write(buf);
            if (len < bytes.length) throw new IOException("simulated crash while writing");
            ch.force(true);
        }
        if (beforeMove != null) beforeMove.run();
        try {
            Files.move(tmp.toPath(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            // some filesystems cannot do atomic renames: plain replace
            Files.move(tmp.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
