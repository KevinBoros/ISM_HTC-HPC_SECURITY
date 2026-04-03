package ism.htc.hpc.kevin;

import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;

public final class App {
    private static final int PORT = 7071;

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

                String fakeJobId = "job-123";
                String fakeDownloadUrl = "/api/download/" + fakeJobId;

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