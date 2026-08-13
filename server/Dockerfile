FROM node:22-alpine
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci --omit=dev
COPY server.js ./
EXPOSE 8090
ENV PORT=8090
CMD ["node", "server.js"]
