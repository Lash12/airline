# Small Server Deployment Guide

This document covers the steps to deploy the airline application in a small-server environment using Docker Compose.

## Prerequisites
- Docker installed
- Docker Compose installed

## Deployment Steps

1. **Navigate to project directory**
   ```bash
   cd /path/to/airline-project
   ```

2. **Start the application**
   ```bash
   docker-compose -f docker-compose.small.yaml up -d
   ```

3. **Verify startup**
   ```bash
   docker logs airline-app
   ```
   Look for the message: `Server started on port 9000`

4. **Check MySQL connection**
   ```bash
   docker exec -it airline-db mysql -uairline -pairlinepass airline -e "SHOW TABLES;"
   ```

## Important Notes

- Elasticsearch is not included in this configuration
- Features requiring Elasticsearch will be disabled
- The app is exposed on port 9000