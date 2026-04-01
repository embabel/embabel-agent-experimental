# Embabel Claude Code Sandbox
#
# A sandboxed environment for running Claude Code with GitHub integration.
# Includes Claude Code CLI, GitHub CLI, git, and common dev tools.
#
# Build:
#   docker build -t embabel/claude-code-sandbox:latest ./docker
#
# Required environment variables (propagated from host via SandboxConfig):
#   ANTHROPIC_API_KEY  - for Claude Code API access
#   GITHUB_TOKEN       - for gh CLI and git push

FROM node:20-slim

ENV DEBIAN_FRONTEND=noninteractive

# Install system packages
RUN apt-get update && \
    apt-get install -yy --no-install-recommends \
        ca-certificates \
        curl \
        git \
        jq \
        make \
        gnupg \
        python3 \
        ripgrep \
        sudo \
        unzip \
        wget && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Java 21 (Temurin)
RUN curl -fsSL https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor -o /usr/share/keyrings/adoptium.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
        > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && \
    apt-get install -yy --no-install-recommends temurin-21-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/temurin-21-jdk

# Ensure JAVA_HOME resolves regardless of architecture
RUN if [ ! -d "$JAVA_HOME" ]; then ln -s /usr/lib/jvm/temurin-21-jdk-* "$JAVA_HOME"; fi

# Install Maven
RUN curl -fsSL https://archive.apache.org/dist/maven/maven-3/3.9.8/binaries/apache-maven-3.9.8-bin.tar.gz \
        -o /tmp/maven.tar.gz && \
    tar -xzf /tmp/maven.tar.gz -C /opt && \
    ln -s /opt/apache-maven-3.9.8/bin/mvn /usr/local/bin/mvn && \
    rm /tmp/maven.tar.gz

ENV MAVEN_HOME=/opt/apache-maven-3.9.8

# Install GitHub CLI
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
        -o /usr/share/keyrings/githubcli-archive-keyring.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list && \
    apt-get update && \
    apt-get install -yy --no-install-recommends gh && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Install Claude Code CLI
RUN npm install -g @anthropic-ai/claude-code

# Use the existing node user (UID 1000) — add sudo and workspace dirs
RUN usermod -aG sudo node && \
    echo "node ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers && \
    mkdir -p /input /output /workspace && \
    chown node:node /input /output /workspace

USER node
WORKDIR /workspace

# Configure git for the agent
RUN git config --global user.name "Embabel Agent" && \
    git config --global user.email "agent@embabel.com" && \
    git config --global init.defaultBranch main

CMD ["/bin/bash"]
