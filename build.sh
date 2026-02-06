#!/usr/bin/env bash
# =============================================================================
# Build & Deploy script for AWS Lambda + Java 21 + GraalVM Native Image
# =============================================================================
set -euo pipefail

PROJECT_NAME="product-api-graalvm"
REGION="${AWS_REGION:-us-east-1}"
STACK_NAME="product-api-graalvm-stack"
S3_BUCKET="${SAM_S3_BUCKET:-}"  # Set this or SAM will create one

echo "============================================"
echo " AWS Lambda + Java 21 + GraalVM Native Image"
echo "============================================"

# -------------------------------------------------------
# Step 1: Build with Maven (JVM jar - for testing)
# -------------------------------------------------------
build_jar() {
    echo "▶ Building uber JAR..."
    mvn clean package -DskipTests
    echo "✅ JAR built: target/lambda-graalvm-sample-1.0.0.jar"
}

# -------------------------------------------------------
# Step 2: Build Native Image with Docker
# -------------------------------------------------------
build_native_docker() {
    echo "▶ Building GraalVM native image via Docker..."
    echo "  (This will take 3-5 minutes on first run)"

    docker build -t "${PROJECT_NAME}:latest" .

    echo "✅ Docker image built: ${PROJECT_NAME}:latest"

    # Show image size
    docker images "${PROJECT_NAME}:latest" --format "   Image size: {{.Size}}"
}

# -------------------------------------------------------
# Step 3: Build zip deployment (alternative)
# -------------------------------------------------------
build_zip() {
    echo "▶ Building native image zip for Lambda..."
    DOCKER_BUILDKIT=1 docker build --output=. -f Dockerfile.zip .
    echo "✅ Deployment zip: function.zip ($(du -h function.zip | cut -f1))"
}

# -------------------------------------------------------
# Step 4: Test locally with SAM
# -------------------------------------------------------
test_local() {
    echo "▶ Testing locally with Docker..."
    echo ""
    echo "  Starting Lambda container on http://localhost:9000"
    echo ""
    echo "  In another terminal, test with:"
    echo '    curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" -d '"'"'{"httpMethod":"GET","path":"/health"}'"'"' | jq'
    echo '    curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" -d '"'"'{"httpMethod":"GET","path":"/products"}'"'"' | jq'
    echo '    curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" -d '"'"'{"httpMethod":"GET","path":"/products/prod-001"}'"'"' | jq'
    echo '    curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" -d '"'"'{"httpMethod":"GET","path":"/products","queryStringParameters":{"category":"Electronics"}}'"'"' | jq'
    echo '    curl -s -XPOST "http://localhost:9000/2015-03-31/functions/function/invocations" -d '"'"'{"httpMethod":"POST","path":"/products","body":"{\"name\":\"Webcam\",\"price\":49.99,\"category\":\"Electronics\"}"}'"'"' | jq'
    echo ""

    docker run --rm -p 9000:8080 "${PROJECT_NAME}:latest"
}

# -------------------------------------------------------
# Step 5: Deploy to AWS
# -------------------------------------------------------
deploy() {
    echo "▶ Deploying to AWS (region: ${REGION})..."

    SAM_ARGS="--stack-name ${STACK_NAME} --region ${REGION} --resolve-image-repos --resolve-s3 --capabilities CAPABILITY_IAM --no-confirm-changeset"

    if [[ -n "${S3_BUCKET}" ]]; then
        SAM_ARGS="${SAM_ARGS} --s3-bucket ${S3_BUCKET}"
    fi

    sam deploy ${SAM_ARGS}

    echo ""
    echo "✅ Deployed! Getting API URL..."
    aws cloudformation describe-stacks \
        --stack-name "${STACK_NAME}" \
        --region "${REGION}" \
        --query 'Stacks[0].Outputs[?OutputKey==`ApiUrl`].OutputValue' \
        --output text
}

# -------------------------------------------------------
# Step 6: Benchmark cold start
# -------------------------------------------------------
benchmark() {
    local API_URL="${1:-}"
    if [[ -z "${API_URL}" ]]; then
        echo "Usage: $0 benchmark <API_URL>"
        echo "Example: $0 benchmark https://abc123.execute-api.us-east-1.amazonaws.com/prod"
        exit 1
    fi

    echo "▶ Benchmarking cold start..."
    echo "  Updating function to force cold start..."

    aws lambda update-function-configuration \
        --function-name "${PROJECT_NAME}" \
        --region "${REGION}" \
        --environment "Variables={BENCHMARK_RUN=$(date +%s)}" \
        --query 'LastModified' --output text

    sleep 5

    echo "  Invoking function..."
    curl -s -o /dev/null -w "  HTTP Status: %{http_code}\n  Total Time: %{time_total}s\n  Connect: %{time_connect}s\n  TTFB: %{time_starttransfer}s\n" \
        "${API_URL}/health"
}

# -------------------------------------------------------
# Cleanup
# -------------------------------------------------------
cleanup() {
    echo "▶ Deleting stack ${STACK_NAME}..."
    sam delete --stack-name "${STACK_NAME}" --region "${REGION}" --no-prompts
    echo "✅ Stack deleted"
}

# -------------------------------------------------------
# Main
# -------------------------------------------------------
case "${1:-help}" in
    jar)        build_jar ;;
    native)     build_native_docker ;;
    zip)        build_zip ;;
    test)       test_local ;;
    deploy)     deploy ;;
    benchmark)  benchmark "${2:-}" ;;
    cleanup)    cleanup ;;
    all)
        build_native_docker
        deploy
        ;;
    *)
        echo "Usage: $0 {jar|native|zip|test|deploy|benchmark|cleanup|all}"
        echo ""
        echo "Commands:"
        echo "  jar        Build JVM uber jar (for testing)"
        echo "  native     Build GraalVM native image Docker container"
        echo "  zip        Build native image as Lambda zip deployment"
        echo "  test       Test locally with Docker"
        echo "  deploy     Deploy to AWS with SAM"
        echo "  benchmark  Benchmark cold start (pass API URL as arg)"
        echo "  cleanup    Delete the CloudFormation stack"
        echo "  all        Build native + deploy"
        ;;
esac
