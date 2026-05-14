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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class App {
    private static final int PORT = 7071;
    private static final String RABBITMQ_QUEUE = "c1_uploads";
    private static final String RABBITMQ_HOST = System.getenv().getOrDefault("RABBITMQ_HOST", "c2-rabbitmq");
    private static final String RABBITMQ_USER = System.getenv().getOrDefault("RABBITMQ_USER", "appuser");
    private static final String RABBITMQ_PASS = System.getenv().getOrDefault("RABBITMQ_PASS", "apppass");
    private static final String C5_API_URL = System.getenv().getOrDefault("C5_API_URL", "http://c5-storage-api:3000");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static void createPendingJob(String jobId, String operation, String filename) throws Exception {
    JSONObject body = new JSONObject();
    body.put("jobId", jobId);
    body.put("operation", operation);
    body.put("filename", filename);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(C5_API_URL + "/api/jobs"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        .build();

    HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new RuntimeException("C5 failed to create job: " + response.body());
    }
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

                String jobId = UUID.randomUUID().toString();
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
                    message.put("filename", picture.filename());
                    message.put("jobId", jobId);

                    try {
                        createPendingJob(jobId, operation, picture.filename());
                    } catch (Exception e) {
                        ctx.status(500).result("Failed to create pending job in C5: " + e.getMessage());
                        return;
                    }

                    byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);
                    channel.basicPublish("", RABBITMQ_QUEUE, null, messageBytes);
                    System.out.println("Sent message to RabbitMQ");
                } catch (Exception e) {
                    ctx.status(500).result("Failed to send message to RabbitMQ");
                    return;
                }
                System.out.println("C1 received upload from C3 for jobId=" + jobId + ", file=" + picture.filename() + ", operation=" + operation);
                
                String response =
                    "Upload received\n" +
                    "jobId=" + jobId + "\n" +
                    "file=" + picture.filename() + "\n" +
                    "operation=" + operation + "\n" +
                    "statusUrl=/api/jobs/" + jobId + "\n" +
                    "downloadUrl=/api/download/" + jobId;

                ctx.result(response);
            });

            config.routes.get("/api/jobs/{jobId}", ctx -> {
                String jobId = ctx.pathParam("jobId");

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(C5_API_URL + "/api/jobs/" + jobId))
                    .GET()
                    .build();

                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());

                ctx.status(response.statusCode());
                ctx.contentType("application/json");
                ctx.result(response.body());
            });

            config.routes.get("/api/download/{jobId}", ctx -> {
                String jobId = ctx.pathParam("jobId");

                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(C5_API_URL + "/api/jobs/" + jobId + "/file"))
                    .GET()
                    .build();

                HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());

                ctx.status(response.statusCode());

                String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("application/octet-stream");

                String contentDisposition = response.headers()
                    .firstValue("Content-Disposition")
                    .orElse("attachment; filename=\"result.bmp\"");

                ctx.contentType(contentType);
                ctx.header("Content-Disposition", contentDisposition);
                ctx.result(response.body());
            });

        }).start(PORT);

        System.out.println("C1 running on http://localhost:" + PORT);
    }
}