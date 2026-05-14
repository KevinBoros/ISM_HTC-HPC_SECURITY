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
import java.io.FileWriter;
import java.io.IOException;
import java.lang.Thread;
import java.util.ArrayList;
import java.util.Arrays;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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
            String workerPath,
            StringBuilder mpiLogsSb
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
                 mpiLogsSb.append(line).append("\n");
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
    private static final List<String> MPI_HOSTS = List.of(
    "c3-consumer",
    "c4-worker-1",
    "c4-worker-2",
    "c4-worker-3"
    );
    public static final String C5_API_URL = System.getenv().getOrDefault("C5_API_URL", "http://c5-storage-api:3000");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static int chooseProcessCount(int byteLength) {
        int totalBlocks = byteLength / AESBlockSize;
        if (totalBlocks <= 0) {
            return 1;
        }
        return Math.min(MPI_HOSTS.size(), totalBlocks);
    }

    private static String writeHostfile(String jobId, int processCount) throws IOException {
    File hostfile = new File("/mpi-work/" + jobId + "_hosts");

    try (FileWriter writer = new FileWriter(hostfile)) {
        for (int i = 0; i < processCount; i++) {
            writer.write(MPI_HOSTS.get(i) + " slots=1\n");
        }
    }

    return hostfile.getAbsolutePath();
    }


    private static void markJobSuccess(String jobId, String filename, byte[] finalBmpBytes) throws Exception {
        JSONObject body = new JSONObject();
        body.put("filename", filename);
        body.put("contentType", "image/bmp");
        body.put("fileBase64", Base64.getEncoder().encodeToString(finalBmpBytes));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(C5_API_URL + "/api/jobs/" + jobId + "/success"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("C5 success update failed: " + response.body());
        }
    }

    private static void markJobFailed(String jobId, String error) {
        try {
            JSONObject body = new JSONObject();
            body.put("error", error);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(C5_API_URL + "/api/jobs/" + jobId + "/fail"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

            HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            e.printStackTrace();
        }
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
            String jobId = null;
            
            try 
            {
                String message = new String(delivery.getBody(), "UTF-8");
                JSONObject json = new JSONObject(message);
                String aesHexKey = json.getString("aesKey");
                String operation = json.getString("operation").toLowerCase();
                jobId = json.getString("jobId");
                System.out.println("Received message:");
                System.out.println("File: long JSON string with base64 content omitted for brevity");
                System.out.println("AES Key received, length = " + (aesHexKey.length() / 2) + " bytes");
                System.out.println("Operation: " + operation);
                System.out.println("Job ID: " + jobId);

                StringBuilder mpiLogs = new StringBuilder();

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

                    File paddedText = new File("/mpi-work/job_" + jobId + "_pixels_in.bin");

                    paddedText.createNewFile();

                    FileOutputStream fos = new FileOutputStream(paddedText);
                    fos.write(paddedPixels);
                    fos.close();

                    int exitCode = MpiLauncher.runMpiJob(
                            processCount,
                            writeHostfile(jobId, processCount),
                            "encrypt",
                            aesHexKey,
                            "/mpi-work/job_" + jobId + "_pixels_in.bin",
                            "/mpi-work/job_" + jobId + "_pixels_out.bin",
                            "/opt/mpi-app/bmp_mpi_worker",
                            mpiLogs
                    );

                    if (exitCode != 0) {
                        throw new RuntimeException("MPI failed with exit code " + exitCode + "\n" + mpiLogs.toString());
                    }

                    File MPIEncrypted = new File( "/mpi-work/job_" + jobId + "_pixels_out.bin");
                    if (!MPIEncrypted.exists()) {
                        throw new FileNotFoundException("Crypted file written by MPI not found!");
                    }
                    FileInputStream fis = new FileInputStream(MPIEncrypted);

                    fileOperatedOnBytes = fis.readAllBytes();

                    fis.close();

                    byte[] finalBmpBytes = new byte[header.length + fileOperatedOnBytes.length];
                    System.arraycopy(header, 0, finalBmpBytes, 0, header.length);
                    System.arraycopy(fileOperatedOnBytes, 0, finalBmpBytes, header.length, fileOperatedOnBytes.length);
                    String resultFilename = operation + "_" + jobId + ".bmp";
                    markJobSuccess(jobId, resultFilename, finalBmpBytes);

                    //TESTING BLOCK
                    File encryptedFile = new File("/data/output/encrypted_" + jobId + ".bmp");
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

                    File paddedText = new File("/mpi-work/job_" + jobId + "_pixels_in.bin");

                    paddedText.createNewFile();

                    FileOutputStream fos = new FileOutputStream(paddedText);
                    fos.write(actualPixels);
                    fos.close();


                    int exitCode = MpiLauncher.runMpiJob(
                            processCount,
                            writeHostfile(jobId, processCount),
                            "decrypt",
                            aesHexKey,
                            "/mpi-work/job_" + jobId + "_pixels_in.bin",
                            "/mpi-work/job_" + jobId + "_pixels_out.bin",
                            "/opt/mpi-app/bmp_mpi_worker",
                            mpiLogs
                    );


                    if (exitCode != 0) {
                        throw new RuntimeException("MPI failed with exit code " + exitCode + "\n" + mpiLogs.toString());
                    }

                    File paddedDecrypted = new File("/mpi-work/job_" + jobId + "_pixels_out.bin");
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

                    byte[] finalBmpBytes = new byte[header.length + fileOperatedOnBytes.length];
                    System.arraycopy(header, 0, finalBmpBytes, 0, header.length);
                    System.arraycopy(fileOperatedOnBytes, 0, finalBmpBytes, header.length, fileOperatedOnBytes.length);
                    String resultFilename = operation + "_" + jobId + ".bmp";
                    markJobSuccess(jobId, resultFilename, finalBmpBytes);

                    //TESTING BLOCK
                    File decryptedFile = new File("/data/output/decrypted_" + jobId + ".bmp");
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

            if (jobId != null) {
                markJobFailed(jobId, e.getMessage());
            }

            finalChannel.basicNack(tag, false, false);
        }

        };
        channel.basicConsume(RABBITMQ_QUEUE, false, deliverCallback, consumerTag -> { });
        Thread.currentThread().join();
    }
}