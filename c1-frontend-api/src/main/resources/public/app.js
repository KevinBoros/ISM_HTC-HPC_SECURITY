const output = document.getElementById("output");
const uploadForm = document.getElementById("uploadForm");
const healthBtn = document.getElementById("healthBtn");
const changeBtn = document.getElementById("changeBtn");
const formPane = document.getElementById("formPane");
const greetingPane = document.getElementById("greetingPane");

const jobStatusBox = document.getElementById("jobStatusBox");
const jobStatusText = document.getElementById("jobStatusText");
const downloadLink = document.getElementById("downloadLink");

let currentPollInterval = null;

changeBtn.addEventListener("click", () => {
    formPane.style.display = "block";
    greetingPane.style.display = "none";
});

healthBtn.addEventListener("click", async () => {
    try {
        const response = await fetch("/api/health");
        const text = await response.text();
        output.textContent = "Health check:\n" + text;
    } catch (err) {
        output.textContent = "Health check failed:\n" + err;
    }
});

function extractJobId(uploadResponseText) {
    const match = uploadResponseText.match(/jobId=([^\n\r]+)/);
    return match ? match[1].trim() : null;
}

function showJobStatus(message) {
    jobStatusBox.style.display = "block";
    jobStatusText.textContent = message;
}

function showDownloadLink(jobId) {
    downloadLink.href = `/api/download/${jobId}`;
    downloadLink.textContent = "Download result";
    downloadLink.style.display = "inline-block";
}

async function checkJobStatus(jobId) {
    const response = await fetch(`/api/jobs/${jobId}`);
    const job = await response.json();

    if (!response.ok) {
        showJobStatus(`Could not read job status: ${job.error || response.statusText}`);
        return;
    }

    if (job.status === "pending") {
        showJobStatus(`Job ${jobId} is still pending...`);
        return;
    }

    if (job.status === "success") {
        showJobStatus(`Job ${jobId} finished successfully.`);
        showDownloadLink(jobId);

        if (currentPollInterval) {
            clearInterval(currentPollInterval);
            currentPollInterval = null;
        }

        return;
    }

    if (job.status === "failed") {
        showJobStatus(`Job ${jobId} failed: ${job.errorMessage || "Unknown error"}`);

        if (currentPollInterval) {
            clearInterval(currentPollInterval);
            currentPollInterval = null;
        }

        return;
    }

    showJobStatus(`Job ${jobId} status: ${job.status}`);
}

function startPollingJob(jobId) {
    if (currentPollInterval) {
        clearInterval(currentPollInterval);
    }

    downloadLink.style.display = "none";
    downloadLink.href = "#";

    showJobStatus(`Job ${jobId} was queued. Waiting for result...`);

    checkJobStatus(jobId);

    currentPollInterval = setInterval(() => {
        checkJobStatus(jobId).catch(err => {
            showJobStatus("Status check failed: " + err);
        });
    }, 2000);
}

function isValidAesHexKey(key) {
    return /^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{48}$|^[0-9a-fA-F]{64}$/.test(key);
}

uploadForm.addEventListener("submit", async (e) => {
    e.preventDefault();

    const formData = new FormData(uploadForm);

    const aesKey = formData.get("aesKey");

    if (!isValidAesHexKey(aesKey)) {
        output.textContent = "Invalid AES key. Use 32, 48, or 64 hex characters for AES-128, AES-192, or AES-256.";
        return;
    }

    output.textContent = "Uploading file...";
    jobStatusBox.style.display = "none";
    downloadLink.style.display = "none";

    try {
        const response = await fetch("/api/upload", {
            method: "POST",
            body: formData
        });

        const text = await response.text();
        output.textContent = text;

        if (!response.ok) {
            return;
        }

        const jobId = extractJobId(text);

        if (!jobId) {
            showJobStatus("Upload succeeded, but no jobId was found in the response.");
            return;
        }

        startPollingJob(jobId);

    } catch (err) {
        output.textContent = "Upload failed:\n" + err;
    }
});