import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * FileServer
 * ----------
 * A central file-sharing server supporting UPLOAD, DOWNLOAD, and LIST,
 * built to demonstrate two distributed-systems concepts:
 *
 *   1. REPLICATION — every uploaded file is written to a "primary"
 *      folder and copied to a "replica" folder, simulating a basic
 *      replication strategy. Downloads fall back to the replica if
 *      the primary copy is ever missing.
 *
 *   2. CONSISTENCY — each filename gets its own ReadWriteLock. A
 *      download (read) can never happen while an upload (write) of
 *      the SAME file is in progress, so no client can ever download
 *      a partially-written, corrupted file. Multiple simultaneous
 *      downloads of the SAME file are still allowed (readers don't
 *      block other readers) — only a writer blocks everyone.
 */
public class FileServer {

    private static final int PORT = 8000;
    private static final String PRIMARY_DIR = "primary_storage";
    private static final String REPLICA_DIR = "replica_storage";

    // One lock per filename, created on first access. ReadWriteLock
    // allows many simultaneous readers OR exactly one writer, never both.
    private static final ConcurrentHashMap<String, ReadWriteLock> fileLocks =
            new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        Files.createDirectories(Paths.get(PRIMARY_DIR));
        Files.createDirectories(Paths.get(REPLICA_DIR));

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("========================================");
            System.out.println("  Central File-Sharing Server");
            System.out.println("  Listening on port " + PORT);
            System.out.println("========================================");

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }
        }
    }

    private static ReadWriteLock lockFor(String fileName) {
        return fileLocks.computeIfAbsent(fileName, k -> new ReentrantReadWriteLock());
    }

    private static void handleClient(Socket socket) {
        String remote = socket.getRemoteSocketAddress().toString();
        try {
            // IMPORTANT: we read the command line MANUALLY, byte by
            // byte, directly off the raw InputStream — NOT through a
            // BufferedReader/InputStreamReader. Those classes read
            // ahead in large chunks for efficiency, which can silently
            // swallow the file's binary bytes (sent right after the
            // command line) into their own internal buffer. A fresh
            // stream created afterward can never recover those bytes.
            // Reading manually guarantees the stream's cursor stops
            // exactly at the end of the command line, byte-accurate,
            // so any binary data that follows is still there to read.
            java.io.InputStream rawIn = socket.getInputStream();
            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());

            String commandLine = readLine(rawIn);
            if (commandLine == null || commandLine.isEmpty()) return;

            String[] parts = commandLine.split(" ", 3);
            String command = parts[0];

            switch (command) {
                case "UPLOAD" -> handleUpload(rawIn, remote, parts);
                case "DOWNLOAD" -> handleDownload(dataOut, remote, parts);
                case "LIST" -> handleList(dataOut);
                default -> System.out.println("[" + remote + "] Unknown command: " + command);
            }

        } catch (IOException e) {
            System.out.println("[" + remote + "] Error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Reads a single line manually, one byte at a time, directly from
     * the raw socket stream — see the comment above for why this
     * matters. Stops at '\n', ignores '\r' (in case a client sends
     * CRLF line endings).
     */
    private static String readLine(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        boolean readAny = false;
        while ((b = in.read()) != -1) {
            readAny = true;
            if (b == '\n') break;
            if (b == '\r') continue;
            sb.append((char) b);
        }
        return readAny ? sb.toString() : null;
    }

    private static void handleUpload(java.io.InputStream rawIn, String remote, String[] parts) throws IOException {
        String fileName = sanitize(parts[1]);
        long fileSize = Long.parseLong(parts[2]);

        ReadWriteLock lock = lockFor(fileName);
        // WRITE lock: exclusive. No reader or other writer can touch
        // this filename until the upload fully completes.
        lock.writeLock().lock();
        try {
            // Reuse the SAME stream the command line was read from —
            // this is what actually fixes the bug (see handleClient).
            DataInputStream dataIn = new DataInputStream(rawIn);
            Path primaryPath = Paths.get(PRIMARY_DIR, fileName);

            try (BufferedOutputStream out = new BufferedOutputStream(
                    new FileOutputStream(primaryPath.toFile()))) {
                byte[] buffer = new byte[8192];
                long remaining = fileSize;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = dataIn.read(buffer, 0, toRead);
                    if (read == -1) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }

            // Replicate: copy the just-written file to the replica
            // folder. Done while still holding the write lock, so
            // the file is guaranteed fully consistent before we
            // replicate it — we never replicate a partial file.
            Path replicaPath = Paths.get(REPLICA_DIR, fileName);
            Files.copy(primaryPath, replicaPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            System.out.println("[" + remote + "] Uploaded and replicated: "
                    + fileName + " (" + fileSize + " bytes)");

        } finally {
            lock.writeLock().unlock();
        }
    }

    private static void handleDownload(DataOutputStream dataOut, String remote, String[] parts) throws IOException {
        String fileName = sanitize(parts[1]);
        ReadWriteLock lock = lockFor(fileName);

        // READ lock: shared. Multiple clients can download the SAME
        // file at once safely, but must wait if an upload (write) of
        // this file is currently in progress.
        lock.readLock().lock();
        try {
            Path primaryPath = Paths.get(PRIMARY_DIR, fileName);
            Path servePath = primaryPath;

            if (!Files.exists(primaryPath)) {
                // Fallback: primary missing, try the replica instead.
                Path replicaPath = Paths.get(REPLICA_DIR, fileName);
                if (Files.exists(replicaPath)) {
                    System.out.println("[" + remote + "] WARNING: primary copy of "
                            + fileName + " missing — serving from replica.");
                    servePath = replicaPath;
                } else {
                    dataOut.writeUTF("ERROR");
                    dataOut.writeUTF("File not found: " + fileName);
                    return;
                }
            }

            long size = Files.size(servePath);
            dataOut.writeUTF("OK");
            dataOut.writeLong(size);

            try (FileInputStream in = new FileInputStream(servePath.toFile())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, read);
                }
            }
            dataOut.flush();
            System.out.println("[" + remote + "] Downloaded: " + fileName);

        } finally {
            lock.readLock().unlock();
        }
    }

    private static void handleList(DataOutputStream dataOut) throws IOException {
        List<String> files = Arrays.stream(
                        Paths.get(PRIMARY_DIR).toFile().listFiles())
                .map(java.io.File::getName)
                .toList();

        dataOut.writeUTF("OK");
        dataOut.writeInt(files.size());
        for (String name : files) {
            dataOut.writeUTF(name);
        }
        dataOut.flush();
    }

    private static String sanitize(String fileName) {
        return Paths.get(fileName).getFileName().toString();
    }
}
