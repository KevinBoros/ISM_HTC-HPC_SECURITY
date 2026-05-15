const express = require('express');
const mysql = require('mysql2/promise');

const app = express();
app.use(express.json({ limit: "150mb" }));

app.use((req, res, next) => {
  const start = Date.now();

  res.on("finish", () => {
    console.log(
      `[C5] ${req.method} ${req.originalUrl} -> ${res.statusCode} (${Date.now() - start}ms)`
    );
  });

  next();
});


const port = 3000;


const pool = mysql.createPool({
    host: process.env.MYSQL_HOST || 'c5-mysql',
    port: Number(process.env.MYSQL_PORT) || 3306,
    database: process.env.MYSQL_DATABASE || 'bmpdbb',
    user: process.env.MYSQL_USER || 'kevinuser',
    password: process.env.MYSQL_PASSWORD || 'kevinpass',
    waitForConnections: true,
    connectionLimit: 10,
    queueLimit: 0
});


async function waitForDatabase() {

  for(let attempt = 1; attempt <= 30; attempt++) {

    try {

      await pool.query('SELECT 1');
      console.log('Connected to MySQL');
      return;
    } catch {
      console.log(`MySQL not available yet (attempt ${attempt}/30), retrying in 2 seconds...`);
      await new Promise(resolve => setTimeout(resolve, 2000));
    }
  }
  throw new Error('Unable to connect to MySQL after 30 attempts');
}

app.get('/api/health', async (req, res) => {  
  try {
    await pool.query('SELECT 1');
    res.json({ status: 'ok' });
  } catch (err) {
    res.status(500).json({ status: 'error', message: err || 'Database connection failed' });
  }
});

app.post('/api/jobs', async (req, res) => {

  try {

    const { jobId, operation, filename } = req.body;

    if(!req.body || !req.body.jobId || !req.body.operation || !req.body.filename) {
        return res.status(400).json({ error: 'Missing jobId or operation type or filename in body' });
    }

    await pool.execute(`INSERT INTO jobs (job_id, status, operation, original_filename) VALUES (?, 'pending', ?, ?)`, [jobId, operation, filename]);

    console.log(`[C5] Creating pending job ${jobId}`);
    res.status(201).json({
      jobId,
      status: "pending"
    });

  } catch (err) {
    console.error(err);

    if (err.code === "ER_DUP_ENTRY") {
      return res.status(409).json({ error: "Job already exists" });
    }

    res.status(500).json({ error: err.message });
  }

});


app.post('/api/jobs/:jobId/success', async (req, res) => {

  try {
    const { jobId } = req.params;
    const { filename, contentType, fileBase64} = req.body;

    if(!filename || !contentType || !fileBase64) {
      return res.status(400).json({ error: 'Missing filename or contentType or fileBase64 in body' });
    }

    const fileBuffer = Buffer.from(fileBase64, 'base64');

    const [result] = await pool.execute(`UPDATE jobs SET status = 'success', 
                                                         result_filename = ?,
                                                         content_type = ?,
                                                         result_blob = ?,
                                                         error_message = NULL
                                                  WHERE job_id = ?`, 
                                                  [filename, contentType, fileBuffer, jobId]
                                       );

    if(result.affectedRows === 0) {
      return res.status(404).json({ error: 'Job not found' });
    }

    console.log(`[C5] Marking job ${jobId} as success, file=${filename}`);
    res.json({
      jobId,
      status: "success",
      downloadUrl: `/api/jobs/${jobId}/file`
    })

  } catch (err) {
    console.error(err);
    res.status(500).json({ error: err.message });
  }
});


app.post("/api/jobs/:jobId/fail", async (req, res) => {
  try {
    const { jobId } = req.params;
    const { error } = req.body;

    if (!error) {
      return res.status(400).json({ error: "Missing error message" });
    }

    const [result] = await pool.execute(
      `UPDATE jobs
       SET status = 'failed',
           error_message = ?
       WHERE job_id = ?`,
      [error, jobId]
    );

    if (result.affectedRows === 0) {
      return res.status(404).json({ error: "Job not found" });
    }

    console.log(`[C5] Marking job ${jobId} as failed: ${error}`);
    res.json({
      jobId,
      status: "failed"
    });
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: err.message });
  }
});


app.get("/api/jobs/:jobId", async (req, res) => {
  try {
    const { jobId } = req.params;

    const [rows] = await pool.execute(
      `SELECT job_id, status, operation, original_filename,
              result_filename, content_type, error_message,
              created_at, updated_at
       FROM jobs
       WHERE job_id = ?`,
      [jobId]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: "Job not found" });
    }

    const job = rows[0];

    const response = {
      jobId: job.job_id,
      status: job.status,
      operation: job.operation,
      originalFilename: job.original_filename,
      resultFilename: job.result_filename,
      contentType: job.content_type,
      errorMessage: job.error_message,
      createdAt: job.created_at,
      updatedAt: job.updated_at
    };

    if (job.status === "success") {
      response.downloadUrl = `/api/jobs/${jobId}/file`;
    }

    res.json(response);
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: err.message });
  }
});

app.get("/api/jobs/:jobId/file", async (req, res) => {
  try {
    const { jobId } = req.params;

    const [rows] = await pool.execute(
      `SELECT result_filename, content_type, result_blob
       FROM jobs
       WHERE job_id = ? AND status = 'success'`,
      [jobId]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: "Result file not found" });
    }

    const file = rows[0];

    res.setHeader("Content-Type", file.content_type || "application/octet-stream");
    res.setHeader(
      "Content-Disposition",
      `attachment; filename="${file.result_filename || "result.bmp"}"`
    );

    res.send(file.result_blob);
  } catch (err) {
    console.error(err);
    res.status(500).json({ error: err.message });
  }
});

waitForDatabase()
  .then(() => {
    app.listen(port, () => {
      console.log(`Storage API listening on port ${port}`);
    });
  })
  .catch(err => {
    console.error("Failed to start C5 Storage API:", err);
    process.exit(1);
  });
