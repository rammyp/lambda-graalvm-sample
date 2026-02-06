# AWS Lambda + Java 21 + GraalVM Native Image — Step-by-Step Guide

> ⚠️ **Disclaimer:** This project is a **demo/sample application for learning purposes only**. It uses broad IAM permissions and an in-memory data store. For production-grade deployments, always follow the **principle of least privilege** for IAM, use proper data persistence (e.g., DynamoDB), enable authentication/authorization, and follow AWS Well-Architected Framework best practices.
>
> 💰 **Cost Warning:** Running this project on your personal AWS account **will incur charges** (Lambda, API Gateway, ECR, S3, CloudWatch). **Always clean up resources after testing** by running `./build.sh cleanup` to delete the CloudFormation stack and associated resources. See [Step 10 — Cleanup](#step-10--cleanup-when-done) for details.

A complete walkthrough for building, testing, and deploying a REST API on AWS Lambda using Java 21 compiled to a GraalVM native image for ultra-fast cold starts (~100-200ms vs 3-6s on JVM).

---

## Architecture Overview

```
Client → API Gateway → Lambda (GraalVM Native Image) → In-Memory Store
                         ↓
                    ~100-200ms cold start
                    ~5-15ms warm invocations
```

### Tech Stack

| Component        | Technology                                  |
|------------------|---------------------------------------------|
| Language         | Java 21 (records, pattern matching, text blocks) |
| Runtime          | GraalVM Native Image (CE 21)                |
| Lambda Runtime   | `provided.al2023` (custom runtime)          |
| Bootstrap        | Custom runtime (direct Lambda Runtime API)  |
| Build            | Maven + native-maven-plugin                 |
| Infrastructure   | AWS SAM (Serverless Application Model)      |
| Container        | Multi-stage Docker build                    |

### Key Design Decisions

This project uses a **custom Lambda runtime** (`CustomRuntime.java`) that communicates directly with the [Lambda Runtime API](https://docs.aws.amazon.com/lambda/latest/dg/runtimes-api.html) using Java's built-in `HttpClient`. This avoids the `aws-lambda-java-runtime-interface-client` library, which relies heavily on internal reflection that is extremely difficult to configure with GraalVM native image.

All JSON serialization uses **Map-based conversion** instead of direct POJO serialization. Jackson can serialize/deserialize `Map<String, Object>` natively without requiring GraalVM reflection configuration, making the build reliable and predictable.

---

## Project Structure

```
lambda-graalvm-sample/
├── pom.xml                          # Maven config with native profile
├── Dockerfile                       # Container image deployment
├── Dockerfile.zip                   # Zip deployment (alternative)
├── template.yaml                    # AWS SAM template
├── build.sh                         # Build & deploy helper script
├── .gitignore
└── src/
    ├── main/
    │   ├── java/com/example/lambda/
    │   │   ├── CustomRuntime.java           # Custom Lambda runtime (event loop)
    │   │   ├── handler/
    │   │   │   └── ProductApiHandler.java   # REST API routing + business logic
    │   │   ├── model/
    │   │   │   ├── Product.java             # Java 21 record
    │   │   │   └── ApiResponse.java         # Response wrapper record
    │   │   └── service/
    │   │       └── ProductService.java      # In-memory product store
    │   └── resources/
    │       ├── log4j2.xml
    │       └── META-INF/native-image/
    │           ├── reflect-config.json      # Minimal GraalVM reflection metadata
    │           ├── serialization-config.json
    │           ├── resource-config.json
    │           └── native-image.properties
    └── test/
        └── java/com/example/lambda/
            └── ProductApiHandlerTest.java
```

---

## Prerequisites

Before starting, install the following tools on your Mac.

### 1. Java 21

Required for local development and running tests.

**macOS (Homebrew):**
```bash
brew install openjdk@21
```

**Using SDKMAN (recommended):**
```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 21-graalce
```

> **Tip:** Run `sdk list java | grep graalce` to see all available GraalVM versions.

**Verify:**
```bash
java -version
```

### 2. Maven 3.9+

```bash
brew install maven
```

**Verify:**
```bash
mvn -version
```

### 3. Docker Desktop

The native image compilation happens inside a Docker container.

- Download from: https://www.docker.com/products/docker-desktop
- Pick the **Apple Silicon** version if you're on an M1/M2/M3/M4 Mac
- **Important:** Allocate at least **6 GB of memory** to Docker (Settings → Resources → Memory)

**Verify:**
```bash
docker --version
```

### 4. AWS CLI v2

```bash
brew install awscli
```

**Configure credentials:**
```bash
aws configure
```

Enter your AWS Access Key ID, Secret Access Key, region (e.g. `us-east-1`), and output format (`json`).

**Required IAM permissions** — your IAM user needs these policies:
- `AWSCloudFormationFullAccess`
- `AWSLambda_FullAccess`
- `AmazonAPIGatewayAdministrator`
- `AmazonEC2ContainerRegistryFullAccess`
- `IAMFullAccess`
- `AmazonS3FullAccess`

Or for quick development, attach `AdministratorAccess`:
```bash
aws iam attach-user-policy \
  --user-name YOUR_USERNAME \
  --policy-arn arn:aws:iam::aws:policy/AdministratorAccess
```

> ⚠️ **Security Notice:** The IAM permissions above are intentionally broad for **demo/learning purposes only**. For production accounts, always follow the **principle of least privilege** — create a dedicated IAM role with only the specific permissions required for deployment. Never use `AdministratorAccess` in production environments.

**Verify:**
```bash
aws sts get-caller-identity
```

### 5. AWS SAM CLI

```bash
brew install aws-sam-cli
```

**Verify:**
```bash
sam --version
```

### 6. jq (optional, for pretty-printing JSON)

```bash
brew install jq
```

---

## Step-by-Step Instructions

### Step 1 — Download and Enter the Project

Download the project files and navigate into the project directory:

```bash
cd lambda-graalvm-sample
chmod +x build.sh
```

---

### Step 2 — Verify the Project Compiles (JVM Mode)

```bash
mvn clean package -DskipTests
```

**Expected output:**
```
[INFO] BUILD SUCCESS
```

---

### Step 3 — Run Unit Tests

```bash
mvn test
```

**Expected output:**
```
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

### Step 4 — Build the GraalVM Native Image

**Make sure Docker Desktop is running first** (check for the whale icon in your menu bar).

```bash
./build.sh native
```

**What happens behind the scenes:**

1. Docker pulls the `ghcr.io/graalvm/native-image-community:21` image
2. Maven downloads dependencies and builds the uber JAR
3. GraalVM's `native-image` tool performs Ahead-of-Time (AOT) compilation
4. The resulting binary (`bootstrap`) is packaged into an Amazon Linux 2023 Lambda container

**Timing:**
- First run: **5-8 minutes** (downloads + compilation)
- Subsequent runs: **3-5 minutes** (Docker layer caching)

**Expected output:**
```
✅ Docker image built: product-api-graalvm:latest
   Image size: ~90MB
```

---

### Step 5 — Test Locally with Docker

Start the Lambda container locally:

```bash
./build.sh test
```

This starts the Lambda Runtime Interface Emulator on `http://localhost:9000`.

**Open a new terminal** and test the endpoints:

```bash
# Health check
curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" \
  -d '{"httpMethod":"GET","path":"/health"}' | jq

# List all products
curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" \
  -d '{"httpMethod":"GET","path":"/products"}' | jq

# Get a specific product
curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" \
  -d '{"httpMethod":"GET","path":"/products/prod-001"}' | jq

# Search by category
curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" \
  -d '{"httpMethod":"GET","path":"/products","queryStringParameters":{"category":"Electronics"}}' | jq

# Create a new product
curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" \
  -d '{"httpMethod":"POST","path":"/products","body":"{\"name\":\"Webcam\",\"price\":49.99,\"category\":\"Electronics\"}"}' | jq

# Delete a product
curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" \
  -d '{"httpMethod":"DELETE","path":"/products/prod-003"}' | jq
```

**Sample response (health check):**
```json
{
  "statusCode": 200,
  "headers": {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, Authorization"
  },
  "body": "{\"success\":true,\"message\":\"Service is healthy\",\"data\":{\"status\":\"healthy\",\"runtime\":\"GraalVM Native Image\",\"java\":\"21.0.2\",\"timestamp\":\"2026-02-06T21:49:52.179Z\"}}"
}
```

Press `Ctrl+C` in the Docker terminal to stop the container.

> **Note:** When testing locally via Docker, the request format is the Lambda invocation API (POST with JSON event body). Once deployed to AWS with API Gateway, you'll use normal HTTP requests (GET, POST, etc.).

---

### Step 6 — Deploy to AWS

First, build the image using SAM:

```bash
sam build
```

Then deploy:

```bash
./build.sh deploy
```

**What happens:**

1. SAM creates an **S3 bucket** and **ECR repository** automatically
2. SAM pushes the Docker image to ECR
3. SAM creates a **CloudFormation stack** with the Lambda function + API Gateway
4. SAM outputs the live **API URL**

**Expected output:**
```
Successfully created/updated stack - product-api-graalvm-stack in us-east-1
```

Get the API URL:

```bash
aws cloudformation describe-stacks \
  --stack-name product-api-graalvm-stack \
  --region us-east-1 \
  --query 'Stacks[0].Outputs[?OutputKey==`ApiUrl`].OutputValue' \
  --output text
```

Save this URL for the next steps.

---

### Step 7 — Test the Live API

```bash
export API_URL="https://YOUR_API_ID.execute-api.us-east-1.amazonaws.com/Prod"

# Health check
curl -s "$API_URL/health" | jq

# List all products
curl -s "$API_URL/products" | jq

# Get a specific product
curl -s "$API_URL/products/prod-002" | jq

# Search by category
curl -s "$API_URL/products?category=Electronics" | jq

# Create a product
curl -s -X POST "$API_URL/products" \
  -H "Content-Type: application/json" \
  -d '{"name":"Monitor","description":"27 inch 4K","price":399.99,"category":"Electronics"}' | jq

# Delete a product
curl -s -X DELETE "$API_URL/products/prod-005" | jq
```

---

### Step 8 — Benchmark Cold Start Performance

Force a cold start and measure response time:

```bash
./build.sh benchmark $API_URL
```

**Expected output:**
```
  HTTP Status: 200
  Total Time: 0.521s
  Connect: 0.089s
  TTFB: 0.521s
```

**Actual benchmark from our deployment:**

| Metric       | Value     |
|--------------|-----------|
| HTTP Status  | 200       |
| Total Time   | 0.522s    |
| Connect      | 0.089s    |
| TTFB         | 0.521s    |

> **521ms total cold start** including network latency — this is the full round-trip from client to a freshly initialized Lambda function and back.

**Performance comparison (actual measured vs typical JVM):**

| Metric           | Standard JVM (`java21`) | GraalVM Native (this project) | Lambda SnapStart |
|------------------|-------------------------|-------------------------------|------------------|
| Cold start       | 3-6 seconds             | ~8.4s (container init)*       | 200-400ms        |
| Warm invocation  | 5-15ms                  | **1.6-2.2ms** ✅               | 5-15ms           |
| Memory usage     | 120-180MB               | **51-52MB** ✅                  | 120-180MB        |
| Billed (warm)    | 5-15ms                  | **2-3ms** ✅                    | 5-15ms           |
| Package size     | ~15MB JAR               | ~90MB image                   | ~15MB JAR        |

> \* The 8.4s init duration is the one-time container image cold start. The native binary itself starts in milliseconds — the overhead is Lambda pulling and initializing the container image. This can be reduced with **Provisioned Concurrency** or by using **zip deployment** instead of container images.

---

### Step 9 — Monitor in AWS Console

1. Open **AWS Console** → **Lambda** → **product-api-graalvm**
2. Go to the **Monitor** tab for invocation metrics
3. Click **View CloudWatch Logs** for detailed logs

**Actual CloudWatch Logs from deployment:**

![CloudWatch Logs](docs/cloudwatch-logs.png)

**Real performance numbers from our deployment (256 MB, us-east-1, arm64):**

| Metric                  | Value              |
|-------------------------|--------------------|
| Init Duration (cold)    | ~8.4s (container init, one-time) |
| Warm Duration           | 1.6 - 2.2 ms      |
| Billed Duration (warm)  | 2 - 3 ms           |
| Max Memory Used         | 51 - 52 MB         |
| Memory Allocated        | 256 MB             |

> **Note:** The 8.4s init duration is the container image cold start (pulling + initializing). Subsequent invocations are **under 3ms**. To reduce cold starts further, consider increasing memory to 512MB+ (which gives more CPU) or using Provisioned Concurrency.

---

### Step 10 — Cleanup (When Done)

> 🚨 **Important:** If you're using a personal AWS account, **always clean up after testing** to avoid unexpected charges. ECR image storage, CloudWatch logs, and S3 buckets will accumulate costs over time even without active invocations.

Delete all AWS resources:

```bash
./build.sh cleanup
```

This deletes the CloudFormation stack, Lambda function, API Gateway, and IAM role.

**Additionally, manually clean up these resources that SAM may leave behind:**

```bash
# Delete ECR images
aws ecr delete-repository \
  --repository-name product-api-graalvm-stack --force \
  --region us-east-1 2>/dev/null

# Delete SAM-managed S3 bucket (list first, then delete)
aws s3 ls | grep aws-sam-cli-managed
# aws s3 rb s3://BUCKET_NAME_FROM_ABOVE --force

# Delete CloudWatch log group
aws logs delete-log-group \
  --log-group-name /aws/lambda/product-api-graalvm \
  --region us-east-1 2>/dev/null
```

**Verify everything is cleaned up:**
```bash
aws cloudformation list-stacks \
  --region us-east-1 \
  --query 'StackSummaries[?StackName==`product-api-graalvm-stack` && StackStatus!=`DELETE_COMPLETE`]'
```

This should return an empty list `[]`.

---

## API Reference

| Method   | Path                     | Description                 |
|----------|--------------------------|-----------------------------|
| `GET`    | `/health`                | Health check + runtime info |
| `GET`    | `/products`              | List all products           |
| `GET`    | `/products?category=X`   | Filter products by category |
| `GET`    | `/products/{id}`         | Get product by ID           |
| `POST`   | `/products`              | Create a new product        |
| `DELETE`  | `/products/{id}`         | Delete a product            |

---

## How It Works

### Custom Lambda Runtime

Instead of using the `aws-lambda-java-runtime-interface-client` (which uses deep internal reflection that breaks under GraalVM), this project implements a lightweight custom runtime in `CustomRuntime.java`. It does three things in a loop:

1. **GET** the next event from `http://${AWS_LAMBDA_RUNTIME_API}/2018-06-01/runtime/invocation/next`
2. **Deserialize** the JSON event into a `Map<String, Object>`, then manually construct an `APIGatewayProxyRequestEvent`
3. **POST** the handler's response back to `http://${AWS_LAMBDA_RUNTIME_API}/2018-06-01/runtime/invocation/{requestId}/response`

### Map-Based JSON Serialization

GraalVM native image requires explicit reflection configuration for any class that Jackson serializes/deserializes. AWS event classes and custom model classes all need entries in `reflect-config.json`, which is fragile and error-prone.

Instead, this project:
- **Deserializes** incoming events to `Map<String, Object>` (always works, no reflection needed)
- **Serializes** outgoing responses by converting POJOs to `Map<String, Object>` first
- Jackson handles `Map`/`List`/`String`/`Number` natively without any reflection

---

## Build Script Reference

```bash
./build.sh jar        # Build JVM uber JAR (for testing)
./build.sh native     # Build GraalVM native image Docker container
./build.sh zip        # Build native image as Lambda zip deployment
./build.sh test       # Test locally with Docker
./build.sh deploy     # Deploy to AWS with SAM (run sam build first)
./build.sh benchmark  # Benchmark cold start (pass API URL as arg)
./build.sh cleanup    # Delete the CloudFormation stack
./build.sh all        # Build native + deploy (one command)
```

---

## Troubleshooting

### Build Issues

| Problem | Solution |
|---------|----------|
| Docker build out of memory | Increase Docker Desktop memory to 6GB+ (Settings → Resources) |
| `Docker daemon not running` | Open Docker Desktop app and wait for it to fully start |
| Maven download 404 in Docker | Maven version may have been archived; update version in Dockerfile to latest from https://maven.apache.org/download.cgi |
| `invalid value for option Optimize` | Use `-O2` not `-Os` (GraalVM CE only supports `-Ob`, `-O0`, `-O1`, `-O2`) |
| `string templates are a preview feature` | Don't use `STR."..."` — use standard string concatenation instead |

### Deployment Issues

| Problem | Solution |
|---------|----------|
| `S3 Bucket not specified` | Run `sam build` first, then deploy. The `--resolve-s3` flag is included in `build.sh` |
| `Unable to upload artifact` / `Image not found` | Run `sam build` before `./build.sh deploy` — SAM needs to build the image with its own tagging |
| `sam deploy` auth / AccessDenied | Attach required IAM policies to your user (see Prerequisites section) |
| ECR push hangs | Login: `aws ecr get-login-password \| docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com` |
| Lambda timeout | Increase timeout to 30s and memory to 512MB+ |

### Runtime Issues

| Problem | Solution |
|---------|----------|
| Jackson `cannot deserialize` / `cannot construct instance` | Deserialize to `Map.class` instead of a POJO class |
| Jackson `no serializer found` / `no properties discovered` | Convert the object to a `Map` before serializing |
| `NoSuchFieldException: logger` | Don't use `aws-lambda-java-runtime-interface-client` — use the custom runtime approach |
| `ClassNotFoundException` | Add the class to `reflect-config.json` with `allDeclaredConstructors`, `allDeclaredMethods`, `allDeclaredFields` |
| SSL/HTTPS errors | Ensure `--enable-url-protocols=https` is in the native-image build args (already included in `pom.xml`) |

---

## Extending the Project

1. **Add DynamoDB** — Replace the in-memory `ProductService` with AWS SDK v2 DynamoDB client
2. **Add authentication** — Use API Gateway Lambda authorizers or Amazon Cognito
3. **Add CI/CD** — GitHub Actions workflow to automate native image builds
4. **Switch to Quarkus** — Quarkus handles GraalVM reflection automatically with `@RegisterForReflection`
5. **Use SnapStart instead** — If GraalVM complexity is too high, Lambda SnapStart with standard `java21` runtime gives ~200-400ms cold starts with zero native compilation

---

## Lessons Learned

Building Java Lambda functions with GraalVM native image is powerful but comes with challenges:

1. **Avoid `aws-lambda-java-runtime-interface-client`** — It uses deep reflection (`ReflectUtil.setStaticField`) that is nearly impossible to configure for GraalVM. Write a custom runtime instead (~100 lines of code).

2. **Use Map-based serialization** — Don't rely on Jackson's POJO serialization in native images. Convert objects to `Map<String, Object>` before serializing. This eliminates 90% of reflection configuration headaches.

3. **GraalVM CE vs Oracle GraalVM** — Some optimization flags (like `-Os`) only work with Oracle GraalVM. Use `-O2` with Community Edition.

4. **String Templates removed** — Java 21's `STR."..."` string templates were a preview feature that was later removed. Use standard concatenation.

5. **Docker memory matters** — Native image compilation is memory-intensive. Allocate at least 6GB to Docker Desktop.

6. **Test locally first** — Use `docker run --rm -p 9000:8080` with the Lambda base image to test before deploying. Much faster than deploying to AWS for each iteration.

7. **Run `sam build` before deploy** — SAM needs to build and tag the Docker image itself. Running `docker build` alone isn't enough; SAM uses its own image naming convention.

8. **IAM permissions** — SAM deploy needs broad permissions (CloudFormation, Lambda, API Gateway, ECR, S3, IAM). For development, `AdministratorAccess` is easiest.
