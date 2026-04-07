package ism.htc.hpc.kevin;

import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Channel;
import org.json.JSONObject;
import java.util.Base64;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

public final class App {
    private static final int PORT = 7071;
    private static final String RABBITMQ_QUEUE = "c1_uploads";
    private static final String RABBITMQ_HOST = System.getenv().getOrDefault("RABBITMQ_HOST", "c2-rabbitmq");
    private static final String RABBITMQ_USER = System.getenv().getOrDefault("RABBITMQ_USER", "appuser");
    private static final String RABBITMQ_PASS = System.getenv().getOrDefault("RABBITMQ_PASS", "apppass");

    private App() {
    }

    public static void main(String[] args) {
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/public";
                staticFiles.location = Location.CLASSPATH;
            });

            config.routes.get("/api/health", ctx -> {
                ctx.result("OK");
            });

            config.routes.post("/api/upload", ctx -> {
                UploadedFile picture = ctx.uploadedFile("picture");
                String aesKey = ctx.formParam("aesKey");
                String operation = ctx.formParam("operation");

                if (picture == null) {
                    ctx.status(400).result("No picture uploaded");
                    return;
                }

                if (aesKey == null || aesKey.isBlank()) {
                    ctx.status(400).result("AES key is missing");
                    return;
                }

                if (operation == null || operation.isBlank()) {
                    ctx.status(400).result("Operation is missing");
                    return;
                }

                ConnectionFactory factory = new ConnectionFactory();
                factory.setHost(RABBITMQ_HOST);
                factory.setUsername(RABBITMQ_USER);
                factory.setPassword(RABBITMQ_PASS);
                try (Connection connection = factory.newConnection();
                     Channel channel = connection.createChannel()) {
                    channel.queueDeclare(RABBITMQ_QUEUE, true, false, false, null);

                    JSONObject message = new JSONObject();
                    String pictureBase64 = Base64.getEncoder().encodeToString(picture.content().readAllBytes());
                    message.put("pictureBase64", pictureBase64);
                    message.put("aesKey", aesKey);
                    message.put("operation", operation);

                    String jobId = UUID.randomUUID().toString();
                    String fakeDownloadUrl = "/api/download/" + jobId;
                    message.put("jobId", jobId);

                    byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);
                    channel.basicPublish("", RABBITMQ_QUEUE, null, messageBytes);
                    System.out.println("Sent message to RabbitMQ");
                } catch (Exception e) {
                    ctx.status(500).result("Failed to send message to RabbitMQ");
                    return;
                }
                System.out.println("Received upload");
                String fakeDownloadUrl = "/api/download/";

                String response =
                    "Upload received\n" +
                    "file=" + picture.filename() + "\n" +
                    "operation=" + operation + "\n" +
                    "downloadUrl=" + fakeDownloadUrl;

                ctx.result(response);
            });

            config.routes.get("/api/download/{jobId}", ctx -> {
                String jobId = ctx.pathParam("jobId");
                ctx.result("Fake download for " + jobId);
            });
        }).start(PORT);

        System.out.println("C1 running on http://localhost:" + PORT);
    }
}