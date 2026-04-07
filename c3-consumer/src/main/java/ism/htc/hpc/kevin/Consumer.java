package ism.htc.hpc.kevin;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import org.json.JSONObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.Thread;


public final class Consumer {

    public static final String RABBITMQ_QUEUE = "c1_uploads";
    public static final String RABBITMQ_HOST = System.getenv().getOrDefault("RABBITMQ_HOST", "c2-rabbitmq");
    public static final String RABBITMQTT_USER = System.getenv().getOrDefault("RABBITMQ_USER", "appuser");
    public static final String RABBITMQ_PASS = System.getenv().getOrDefault("RABBITMQ_PASS", "apppass");


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

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), "UTF-8");
            JSONObject json = new JSONObject(message);
            System.out.println("Received message:");
            System.out.println("File: long JSON string with base64 content omitted for brevity");
            System.out.println("AES Key: " + json.getString("aesKey"));
            System.out.println("Operation: " + json.getString("operation"));

            File outputFile = new File("resources/bmpBase64_" + System.currentTimeMillis() + ".txt");
            if(!outputFile.getParentFile().exists()) {
                outputFile.getParentFile().mkdirs();
            }
            if(!outputFile.exists()) {
                try {
                    outputFile.createNewFile();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(json.getString("pictureBase64"));
                System.out.println("Saved picture to: " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        };
        channel.basicConsume(RABBITMQ_QUEUE, true, deliverCallback, consumerTag -> { });
        Thread.currentThread().join();
    }
}