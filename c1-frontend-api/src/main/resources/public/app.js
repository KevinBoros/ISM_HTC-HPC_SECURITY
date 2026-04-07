const output = document.getElementById("output");
const uploadForm = document.getElementById("uploadForm");
const healthBtn = document.getElementById("healthBtn");
const changeBtn = document.getElementById("changeBtn");
const formPane = document.getElementById("formPane");
const greetingPane = document.getElementById("greetingPane");

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

uploadForm.addEventListener("submit", async (e) => {
    e.preventDefault();

    const formData = new FormData(uploadForm);

    try {
        const response = await fetch("/api/upload", {
            method: "POST",
            body: formData
        });

        const text = await response.text();
        output.textContent = text;
    } catch (err) {
        output.textContent = "Upload failed:\n" + err;
    }
});