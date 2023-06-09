package dev.hickel;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.util.concurrent.locks.LockSupport;


public class FileSender implements Runnable {
    private final String fileName;
    private final long fileSize;
    private final File file;
    private final int chunkSize;
    private final int blockSize;
    private final Socket socket;
    private final int transferLimit;

    public FileSender(File file, String address, int port, int transferLimit) throws IOException {
        fileSize = file.length();
        fileName = file.getName();
        this.file = file;
        chunkSize = Settings.chunkSize;
        blockSize = Settings.blockSize;
        socket = new Socket(address, port);
        socket.setSoTimeout(120_000);
        socket.setTrafficClass(24);
        this.transferLimit = transferLimit < 1 ? -1 : transferLimit;
    }

    @Override
    public void run() {
        try (DataOutputStream socketOut = new DataOutputStream(socket.getOutputStream());
             DataInputStream socketIn = new DataInputStream(socket.getInputStream());
             FileInputStream inputFile = new FileInputStream(file)) {

            // Send file info
            socketOut.writeUTF(fileName);
            socketOut.writeLong(fileSize);
            socketOut.flush();

            boolean accepted = socketIn.readBoolean();
            if (!accepted) {
                Main.activeTransfers.remove(fileName);
                System.out.println("No space for, file already exists, or all paths in use: " + fileName
                                           + " will retry in: " + Settings.fileCheckInterval + " Seconds");
                return;
            }

            System.out.println("Started transfer of file: " + fileName);

            int bytesRead;
            byte[] buffer = new byte[blockSize];
            while ((bytesRead = inputFile.read(buffer, 0, blockSize)) != -1) {
                socketOut.writeInt(bytesRead);
                socketOut.write(buffer, 0, bytesRead);
                socketOut.flush();
                if (transferLimit != -1) {
                    LockSupport.parkNanos(transferLimit - 50);
                }
            }
            socketOut.writeInt(-1);
            socketOut.flush();

            boolean success = socketIn.readBoolean(); // wait for servers last write, to avoid an exception on quick disconnect
            if (success) {
                System.out.println("Finished transfer for file: " + fileName);
            } else {
                System.out.println("Error during finalization of file transfer");
                Main.activeTransfers.remove(fileName);
                throw new IllegalStateException("Server responded to end of transfer as failed");
            }
            if (Settings.deleteAfterTransfer) {
                Files.delete(file.toPath());
                System.out.println("Deleted file: " + file);
            }
            Main.activeTransfers.remove(fileName);

        } catch (IOException e) {
            Main.activeTransfers.remove(fileName);
            System.out.println("Error in file transfer, most likely connection was lost.");
            e.printStackTrace();
            try { socket.close(); } catch (IOException ee) { System.out.println("Error closing socket"); }

        } catch (Exception e) {
            Main.activeTransfers.remove(fileName);
            e.printStackTrace();
            try { socket.close(); } catch (IOException ee) { System.out.println("Error closing socket"); }
        } finally {
            System.gc();
            if (socket != null) {
                try { socket.close(); } catch (IOException e) { System.out.println("Error closing socket"); }
            }

        }
    }
}
