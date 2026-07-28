import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FileClient
 * ----------
 * Usage:
 *   java FileClient upload <path-to-file>
 *   java FileClient download <filename> [output-path]
 *   java FileClient list
 */
public class FileClient {

    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8000;

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            printUsage();
            return;
        }

        switch (args[0]) {
            case "upload" -> {
                if (args.length < 2) { printUsage(); return; }
                upload(args[1]);
            }
            case "download" -> {
                if (args.length < 2) { printUsage(); return; }
                String outPath = args.length >= 3 ? args[2] : args[1];
                download(args[1], outPath);
            }
            case "list" -> list();
            default -> printUsage();
        }
    }

    private static void upload(String filePathArg) throws IOException {
        Path filePath = Paths.get(filePathArg);
        if (!Files.exists(filePath)) {
            System.out.println("File not found: " + filePath);
            return;
        }

        String fileName = filePath.getFileName().toString();
        long fileSize = Files.size(filePath);

        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
             PrintWriter commandOut = new PrintWriter(socket.getOutputStream(), true)) {

            commandOut.println("UPLOAD " + fileName + " " + fileSize);
            commandOut.flush();

            try (DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
                 FileInputStream fileIn = new FileInputStream(filePath.toFile())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = fileIn.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, read);
                }
                dataOut.flush();
            }

            System.out.println("Uploaded \"" + fileName + "\" (" + fileSize + " bytes).");
        }
    }

    private static void download(String fileName, String outPath) throws IOException {
        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
             PrintWriter commandOut = new PrintWriter(socket.getOutputStream(), true)) {

            commandOut.println("DOWNLOAD " + fileName);
            commandOut.flush();

            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            String status = dataIn.readUTF();

            if (status.equals("ERROR")) {
                System.out.println("Error: " + dataIn.readUTF());
                return;
            }

            long size = dataIn.readLong();
            try (BufferedOutputStream out = new BufferedOutputStream(
                    new FileOutputStream(outPath))) {
                byte[] buffer = new byte[8192];
                long remaining = size;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buffer.length, remaining);
                    int read = dataIn.read(buffer, 0, toRead);
                    if (read == -1) break;
                    out.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            System.out.println("Downloaded \"" + fileName + "\" (" + size + " bytes) -> " + outPath);
        }
    }

    private static void list() throws IOException {
        try (Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
             PrintWriter commandOut = new PrintWriter(socket.getOutputStream(), true)) {

            commandOut.println("LIST");
            commandOut.flush();

            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            String status = dataIn.readUTF();
            if (!status.equals("OK")) {
                System.out.println("Error listing files.");
                return;
            }
            int count = dataIn.readInt();
            System.out.println("Files available on server (" + count + "):");
            for (int i = 0; i < count; i++) {
                System.out.println("  - " + dataIn.readUTF());
            }
        }
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("  java FileClient upload <path-to-file>");
        System.out.println("  java FileClient download <filename> [output-path]");
        System.out.println("  java FileClient list");
    }
}
