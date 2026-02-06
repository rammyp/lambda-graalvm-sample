# =============================================================================
# Multi-stage Dockerfile for AWS Lambda with GraalVM Native Image (Java 21)
# =============================================================================

# Stage 1: Build the native image using GraalVM
FROM ghcr.io/graalvm/native-image-community:21 AS builder

WORKDIR /build

# Install Maven
RUN microdnf install -y findutils && \
    curl -fsSL https://dlcdn.apache.org/maven/maven-3/3.9.12/binaries/apache-maven-3.9.12-bin.tar.gz | tar xz -C /opt && \
    ln -s /opt/apache-maven-3.9.12/bin/mvn /usr/local/bin/mvn

# Copy POM first for dependency caching
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the uber jar first, then native image
RUN mvn package -Pnative -DskipTests -B

# Verify the native binary was created
RUN ls -la /build/target/bootstrap

# --------------------------------------------------------------------------

# Stage 2: Create the Lambda deployment image
FROM public.ecr.aws/lambda/provided:al2023

# Copy the native binary as the bootstrap (Lambda custom runtime entry point)
COPY --from=builder /build/target/bootstrap ${LAMBDA_RUNTIME_DIR}/bootstrap

# Ensure bootstrap is executable
RUN chmod +x ${LAMBDA_RUNTIME_DIR}/bootstrap

# Not used by our custom runtime, but required by the base image
CMD ["handler"]
