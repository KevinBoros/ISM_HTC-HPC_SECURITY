package ism.htc.hpc.kevin;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.lang.Thread;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;



class MpiLauncher {

    public static int runMpiJob(
            int processCount,
            String hostfile,
            String operation,
            String keyHex,
            String inputPath,
            String outputPath,
            String workerPath
    ) throws Exception {

        List<String> command = new ArrayList<>();
        command.add("mpirun");
        command.add("--allow-run-as-root");
        command.add("-np");
        command.add(String.valueOf(processCount));

        if (hostfile != null && !hostfile.isBlank()) {
            command.add("--hostfile");
            command.add(hostfile);
        }

        command.add(workerPath);
        command.add(operation.toLowerCase());
        command.add(keyHex);
        command.add(inputPath);
        command.add(outputPath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[MPI] " + line);
            }
        }

        return process.waitFor();
    }
}



public final class Consumer {

    public static final String RABBITMQ_QUEUE = "c1_uploads";
    public static final String RABBITMQ_HOST = System.getenv().getOrDefault("RABBITMQ_HOST", "c2-rabbitmq");
    public static final String RABBITMQTT_USER = System.getenv().getOrDefault("RABBITMQ_USER", "appuser");
    public static final String RABBITMQ_PASS = System.getenv().getOrDefault("RABBITMQ_PASS", "apppass");
    public static final int AESBlockSize = 16;
    public static final int MAX_MPI_PROCESSES = 4;

    private static int chooseProcessCount(int byteLength) {
        int totalBlocks = byteLength / AESBlockSize;
        if (totalBlocks <= 0) {
            return 1;
        }
        return Math.min(MAX_MPI_PROCESSES, totalBlocks);
    }


    public static void main(String[] args) throws Exception {
        
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RABBITMQ_HOST);
    factory.setUsername(RABBITMQTT_USER);
    factory.setPassword(RABBITMQ_PASS);

    Connection connection = null;
    Channel channel = null;

    int maxRetries = 20;
    int delayMillis = 3000;
    
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
    try {
        System.out.println("Trying to connect to RabbitMQ... attempt " + attempt);
        connection = factory.newConnection();
        channel = connection.createChannel();
        System.out.println("Connected to RabbitMQ.");
        break;
    } catch (Exception e) {
        System.out.println("RabbitMQ not ready yet. Attempt " + attempt + " failed.");
        if (attempt == maxRetries) {
            throw e;
        }
        Thread.sleep(delayMillis);
    }
    }
        channel.queueDeclare(RABBITMQ_QUEUE, true, false, false, null);
        System.out.println("Waiting for messages...");

        final Channel finalChannel = channel;

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {

            long tag = delivery.getEnvelope().getDeliveryTag();
            
            try 
            {
                String message = new String(delivery.getBody(), "UTF-8");
                JSONObject json = new JSONObject(message);
                String aesHexKey = json.getString("aesKey");
                String operation = json.getString("operation").toLowerCase();
                System.out.println("Received message:");
                System.out.println("File: long JSON string with base64 content omitted for brevity");
                System.out.println("AES Key: " + aesHexKey);
                System.out.println("Operation: " + operation);

                byte[] pictureBytes = Base64.getDecoder().decode(json.getString("pictureBase64"));
                
                if (pictureBytes[0] != 0x42 || pictureBytes[1] != 0x4D) {
                    throw new IllegalArgumentException("Not a bitmap image");
                }

                int pictureArrayOffset = (pictureBytes[10] & 0xFF) | ((pictureBytes[11] & 0xFF) << 8) | ((pictureBytes[12] & 0xFF) << 16) | ((pictureBytes[13] & 0xFF) << 24);

                byte[] header = Arrays.copyOf(pictureBytes, pictureArrayOffset);

                byte[] actualPixels = Arrays.copyOfRange(pictureBytes, pictureArrayOffset, pictureBytes.length);


                byte[] keyBytes;
                try {
                    keyBytes = HexFormat.of().parseHex(aesHexKey);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid hex string: " + e.getMessage());
                }

                int length = keyBytes.length;
                if (length != 16 && length != 24 && length != 32) {
                    throw new IllegalArgumentException(
                            "Invalid AES key length: " + (length * 8) + " bits. Must be 128, 192, or 256 bits."
                    );
                }
            
                byte[] fileOperatedOnBytes;

                if ("encrypt".equals(operation)) {


                    int paddingBytes = AESBlockSize - (actualPixels.length % AESBlockSize);

                    byte[] paddedPixels = new byte[actualPixels.length + paddingBytes];
                    System.arraycopy(actualPixels, 0, paddedPixels, 0, actualPixels.length);
                    Arrays.fill(paddedPixels, actualPixels.length, paddedPixels.length, (byte) paddingBytes);


                    int processCount = chooseProcessCount(paddedPixels.length);
                    System.out.println("Encrypt processCount = " + processCount);

                    File paddedText = new File("/tmp/pixels_in.bin");

                    paddedText.createNewFile();

                    FileOutputStream fos = new FileOutputStream(paddedText);
                    fos.write(paddedPixels);
                    fos.close();

                    int exitCode = MpiLauncher.runMpiJob(
                            processCount,
                            //"/home/c3-consumer/mpi/hosts",
                            null,
                            "encrypt",
                            aesHexKey,
                            "/tmp/pixels_in.bin",
                            "/tmp/pixels_out.bin",
                            "/home/c3-consumer/mpi/bmp_mpi_worker"
                    );

                    if (exitCode != 0) {
                        throw new RuntimeException("MPI job failed with exit code " + exitCode);
                    }

                    File MPIEncrypted = new File("/tmp/pixels_out.bin");
                    if (!MPIEncrypted.exists()) {
                        throw new FileNotFoundException("Crypted file written by MPI not found!");
                    }
                    FileInputStream fis = new FileInputStream(MPIEncrypted);

                    fileOperatedOnBytes = fis.readAllBytes();

                    fis.close();

                    //TESTING BLOCK
                    File encryptedFile = new File("/data/output/encrypted.bmp");
                    fos = new FileOutputStream(encryptedFile);
                    fos.write(header);
                    fos.write(fileOperatedOnBytes);
                    fos.close();


                } else if ("decrypt".equals(operation)) {

                    if (actualPixels.length % AESBlockSize != 0) {
                        throw new IllegalArgumentException("Encrypted pixel data length is not a multiple of 16 bytes.");
                    }

                    int processCount = chooseProcessCount(actualPixels.length);
                    System.out.println("Decrypt processCount = " + processCount);

                    File paddedText = new File("/tmp/pixels_in.bin");

                    paddedText.createNewFile();

                    FileOutputStream fos = new FileOutputStream(paddedText);
                    fos.write(actualPixels);
                    fos.close();


                    int exitCode = MpiLauncher.runMpiJob(
                            processCount,
                            //"/home/c3-consumer/mpi/hosts",
                            null,
                            "decrypt",
                            aesHexKey,
                            "/tmp/pixels_in.bin",
                            "/tmp/pixels_out.bin",
                            "/home/c3-consumer/mpi/bmp_mpi_worker"
                    );


                    if (exitCode != 0) {
                        throw new RuntimeException("MPI job failed with exit code " + exitCode);
                    }

                    File paddedDecrypted = new File("/tmp/pixels_out.bin");
                    if (!paddedDecrypted.exists()) {
                        throw new FileNotFoundException("Decrypted file written by MPI not found!");
                    }
                    FileInputStream fis = new FileInputStream(paddedDecrypted);
                    byte[] paddedCryptedBytes = fis.readAllBytes();
                    fis.close();

                    int paddingBytes = paddedCryptedBytes[paddedCryptedBytes.length - 1] & 0xFF;
                    if (paddingBytes > 16 || paddingBytes < 1) {
                        throw new RuntimeException("Encrypted file is not properly padded following PKCS7 standards.");
                    }
                    for (int i = 0; i < paddingBytes; i++) {
                        if (paddedCryptedBytes[paddedCryptedBytes.length - 1 - i] != paddingBytes) {
                            throw new RuntimeException("Encrypted file is not properly padded following PKCS7 standards.");
                        }
                    }

                    fileOperatedOnBytes = new byte[paddedCryptedBytes.length - paddingBytes];
                    System.arraycopy(paddedCryptedBytes, 0, fileOperatedOnBytes, 0, paddedCryptedBytes.length - paddingBytes);

                    //TESTING BLOCK
                    File decryptedFile = new File("/data/output/decrypted.bmp");
                    fos = new FileOutputStream(decryptedFile);
                    fos.write(header);
                    fos.write(fileOperatedOnBytes);
                    fos.close();

                } else {
                    throw new IllegalArgumentException(operation + " is not a valid parameter. Accepted parameters are 'encrypt' or 'decrypt.");
                }

                finalChannel.basicAck(tag, false);

        }   
         catch(Exception e) {
        e.printStackTrace();
        finalChannel.basicNack(tag, false, false);
        }

        };
        channel.basicConsume(RABBITMQ_QUEUE, false, deliverCallback, consumerTag -> { });
        Thread.currentThread().join();
    }
}