#include "mpi.h"
#include <stdio.h>
#include <openssl/aes.h>
#include <string.h>
#include <stdlib.h>
#include <omp.h>

int main(int argc, char *argv[])  {
   int numTasks, rank;
   
   MPI_Init(&argc,&argv);
   MPI_Comm_rank(MPI_COMM_WORLD, &rank);
   MPI_Comm_size(MPI_COMM_WORLD, &numTasks);

   if (argc != 5) {
    MPI_Finalize();
    return 1;
    }

    const char *operation = argv[1];
    const char *hexKey = argv[2];
    const char *inputPath = argv[3];
    const char *outputPath = argv[4];

   
   if((strcmp("encrypt", operation) != 0) && (strcmp("decrypt", operation) != 0)) {
    MPI_Finalize();
    return 2;
   }

   if(rank == 0) {
        if((strlen(hexKey) != 64) && (strlen(hexKey) != 48) && (strlen(hexKey) != 32)) {
            MPI_Finalize();
            return 3;
        }
    }

    int aesKeyLen = strlen(hexKey) / 2;
    unsigned char aesKey[aesKeyLen];

    for(int i = 0, j = 0; j < aesKeyLen; i+=2, j++) {
        aesKey[j] = (hexKey[i] % 32 + 9) % 25 * 16 + (hexKey[i+1] % 32 + 9) % 25;
    }


    unsigned char* picturePixels = NULL;
    int pictureSize = 0;

    int *sendcounts = NULL;
    int *displs = NULL;
   if(rank == 0) {
        FILE* picturePixelsFile = fopen(inputPath, "rb");
        if(!picturePixelsFile)
        {
            MPI_Abort(MPI_COMM_WORLD, 4);
        }
        
        fseek(picturePixelsFile, 0 , SEEK_END);
        pictureSize = ftell(picturePixelsFile);
        printf("[root] pictureSize=%d\n", pictureSize);
        fflush(stdout);
        fseek(picturePixelsFile, 0, SEEK_SET);

        if(pictureSize % AES_BLOCK_SIZE != 0) {
            MPI_Abort(MPI_COMM_WORLD, 5);
        }

        picturePixels = (unsigned char*)malloc(pictureSize);
        if(!picturePixels) {
            fclose(picturePixelsFile);
            MPI_Abort(MPI_COMM_WORLD, 6);
        }
    

        int test = fread(picturePixels, 1, pictureSize, picturePixelsFile);
        fclose(picturePixelsFile);
        if(test != pictureSize) {
            MPI_Abort(MPI_COMM_WORLD, 7);
        }

        int totalBlocks = pictureSize / AES_BLOCK_SIZE;

        sendcounts = (int*)malloc(numTasks * sizeof(int));
        displs = (int*)malloc(numTasks * sizeof(int));

        int baseBlocks = totalBlocks / numTasks;
        int extraBlocks = totalBlocks % numTasks;

        int offset = 0;
        for (int r = 0; r < numTasks; r++) {
            int blocksForRank = baseBlocks + (r < extraBlocks ? 1 : 0);
            sendcounts[r] = blocksForRank * AES_BLOCK_SIZE;
            displs[r] = offset;
            offset += sendcounts[r];
            printf("[root] rank %d gets %d bytes at offset %d\n", r, sendcounts[r], displs[r]);
            fflush(stdout);
        }

   }

   int localBytes = 0;

   MPI_Scatter(sendcounts, 1, MPI_INT, &localBytes, 1, MPI_INT, 0, MPI_COMM_WORLD);
   printf("[rank %d/%d] localBytes=%d\n", rank, numTasks-1, localBytes);
   fflush(stdout);

   
   unsigned char *localIn = NULL;
   unsigned char *localOut = NULL;

   if (localBytes > 0) {
    localIn = (unsigned char *)malloc(localBytes);
    localOut = (unsigned char *)malloc(localBytes);
    }

    MPI_Scatterv(
        picturePixels,
        sendcounts,
        displs,
        MPI_UNSIGNED_CHAR,
        localIn,
        localBytes,
        MPI_UNSIGNED_CHAR,
        0,
        MPI_COMM_WORLD
    );

    int localBlocks = localBytes / AES_BLOCK_SIZE;
    AES_KEY keySchedule;
    if (strcmp(operation, "encrypt") == 0) {
        AES_set_encrypt_key(aesKey, aesKeyLen * 8, &keySchedule);

        printf("[rank %d] OpenMP max threads=%d\n", rank, omp_get_max_threads());
        fflush(stdout);
        #pragma omp parallel for schedule(static)
        for (int block = 0; block < localBlocks; block++) {
            int i = block * AES_BLOCK_SIZE;
            AES_ecb_encrypt(localIn + i, localOut + i, &keySchedule, AES_ENCRYPT);
        }
    } else {
        AES_set_decrypt_key(aesKey, aesKeyLen * 8, &keySchedule);

        printf("[rank %d] OpenMP max threads=%d\n", rank, omp_get_max_threads());
        fflush(stdout);
        #pragma omp parallel for schedule(static)
        for (int block = 0; block < localBlocks; block++) {
            int i = block * AES_BLOCK_SIZE;
            AES_ecb_encrypt(localIn + i, localOut + i, &keySchedule, AES_DECRYPT);
        }
    }

    unsigned char *allOut = NULL;
    if (rank == 0) {
        allOut = (unsigned char *)malloc(pictureSize);
    }

    MPI_Gatherv(
        localOut,
        localBytes,
        MPI_UNSIGNED_CHAR,
        allOut,
        sendcounts,
        displs,
        MPI_UNSIGNED_CHAR,
        0,
        MPI_COMM_WORLD
    );

    if(rank == 0) {

        FILE* outputFile = fopen(outputPath, "wb");
        if(!outputFile) {
            MPI_Abort(MPI_COMM_WORLD, 8);

        }

        int test = fwrite(allOut, 1, pictureSize, outputFile);
        fclose(outputFile);

        if(test != pictureSize) {
            MPI_Abort(MPI_COMM_WORLD, 9);

        }

        free(picturePixels);
        free(allOut);
        free(sendcounts);
        free(displs);
    }
    free(localIn);
    free(localOut);
    
   MPI_Finalize();

   return 0;
}