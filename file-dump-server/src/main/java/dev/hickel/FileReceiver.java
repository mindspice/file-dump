package dev.hickel;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;


public class FileReceiver implements Runnable {
    private final Socket socket;
    private final ActivePaths activePaths;
    private String fileName = "";

    public FileReceiver(Socket socket, ActivePaths activePaths) throws SocketException {
        this.socket = socket;
        this.activePaths = activePaths;
        socket.setSoTimeout(120_000);
        socket.setTrafficClass(24);
        if (Settings.socketBufferSize > 0) { socket.setReceiveBufferSize(Settings.socketBufferSize); }
    }


    @Override
    public void run() {
        long fileSize = 0;
        var startTime = System.currentTimeMillis();
        try (DataInputStream socketIn = new DataInputStream(socket.getInputStream());
             DataOutputStream socketOut = new DataOutputStream(socket.getOutputStream())) {

            fileName = socketIn.readUTF();
            fileSize = socketIn.readLong();

            // Check for free space, send boolean to client if space not available, or file exists
            Path freePath = activePaths.getNewPath(fileName, fileSize);
            if (freePath == null) {
                socketOut.writeBoolean(false);
                socketOut.flush();
                System.out.println("No space for, file already exists, or all paths in use: " + fileName);
                return;
            }

            // Inform client to begin
            File outputFile = freePath.toFile();
            System.out.println("Receiving file: " + fileName +" to: " + outputFile.getParentFile());
            socketOut.writeBoolean(true);

            try (FileOutputStream outFileStream = new FileOutputStream(outputFile);
                 BufferedOutputStream bufferStream = Settings.writeBufferSize < 0
                         ? new BufferedOutputStream(outFileStream)
                         : new BufferedOutputStream(outFileStream, Settings.writeBufferSize)) {

                while (true) {

                    // FLush buffer to disk, and clean up
                    int bytesReceived = socketIn.readInt();
                    if (bytesReceived == -1) {
                        bufferStream.flush();
                        activePaths.removeActiveTransfer(fileName);
                        File finalFile = new File(outputFile.getParent(), fileName);
                        outputFile.renameTo(finalFile);

                        if (!finalFile.exists() || finalFile.length() != fileSize) {
                            socketOut.writeBoolean(false); // Relay there as an issues
                            throw new IllegalStateException("Output file does not exist");
                        }
                        socketOut.writeBoolean(true); // Relay successful transfer
                        socketOut.flush();

                        long seconds = (System.currentTimeMillis() - startTime) / 1000;
                        String metrics = "Finished receiving file: " + fileName +" to: " + outputFile.getParentFile() +
                                "\tTime: " + seconds + " Sec" +
                                "\tSpeed: " + Math.round((double) fileSize / 1048576 / seconds) + " MiBs";
                        int[] stats = TransferStats.incStats(fileSize);
                        System.out.println(metrics);
                        System.out.println("Daily Stats | Count: " + stats[0] +" | Transferred: " + stats[1] + " GiB" );
                        return;
                    }

                    // Read buffer

                    try {
                        bufferStream.write(socketIn.readNBytes(bytesReceived), 0, bytesReceived);
                    } catch (Exception e) {
                        System.out.println("Error writing, assuming directory has improper privileges.");
                        System.out.println("Removed Path: " + activePaths.getExistingPath(fileName));
                        activePaths.removeActiveTransfer(fileName);
                        activePaths.removePathOfTransfer(fileName);
                    }
                }
            }
        } catch (IOException e) {
            activePaths.removeActiveTransfer(fileName);
            System.out.println("Error encountered aborting transfer of: " + fileName);
            e.printStackTrace();
            try { socket.close();
            } catch (IOException ee) { System.out.println("Error closing socket"); }
        } catch (Exception e) {
            activePaths.removeActiveTransfer(fileName);
            e.printStackTrace();
            try { socket.close();
            } catch (IOException ee) { System.out.println("Error closing socket"); }
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (IOException e) { System.out.println("Error closing socket"); }
            }
        }
    }
}
