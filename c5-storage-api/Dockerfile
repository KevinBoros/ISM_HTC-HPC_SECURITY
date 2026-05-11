FROM node:22

WORKDIR /app

COPY package*.json ./

RUN npm install

COPY app.js app.js

EXPOSE 3000

CMD ["npm", "start"]

