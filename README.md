To run the project:

1. Make sure Docker is running.
2. From the project root, run:

   Windows:
   run.bat 
   OR 
   run-detached.bat

   macOS/Linux:
   chmod +x run.sh
   ./run.sh
   OR
   ./run-detached.sh 

   The script removes old containers for this project, rebuilds the images, creates secrets if they do not exist, and starts all services attached or detached

3. Access localhost:7071 to test the encryption and decryption flow, then check the logs to see what happened behind

*Project Summary*

This project is a Docker-based application for encrypting and decrypting BMP images using AES. The user uploads a BMP image, an AES key, and chooses whether to encrypt or decrypt the image from a simple web interface. The request is sent to a Java backend, then passed through RabbitMQ to a processing container.

The actual image processing is done in a distributed way. MPI is used to split the image data between multiple containers, while OpenMP is used inside each worker to process blocks in parallel. The BMP header is kept unchanged so the output file can still be opened as an image. The encrypted/decrypted result is stored through a Node.js storage API into a MySQL database, and the frontend can later check the job status and download the result.

The project uses several technologies: Docker and Docker Compose for containerization, plain javascript + Javalin for the frontend API, RabbitMQ as a message broker, Java (POJO) as the consumer/orchestrator, C with OpenMPI and OpenMP for parallel AES processing, SSH for communication between MPI containers, Node.js/Express for the storage API, and MySQL for storing job information and output files.

Known limitations: 
-the project uses a simple javascript C1 frontend instead of something like react/angular
-it employs a simple POJO as a consumer instead of something more complex like EJB/TomEE
-it lacks security you would definitely find in a real app: different SSH keys for the containers (currently one shared asymmetric key), HTTPS communication and safe AES key management
-no SNMP monitoring with MongoDB container storage