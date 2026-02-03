# Embabel Agent Skills Sandbox
#
# A sandboxed environment for executing skill scripts safely.
# Includes Python, Node.js, and common development tools.
#
# Build:
#   docker build -t embabel/agent-sandbox:latest ./docker
#
# Usage with DockerExecutionEngine:
#   val engine = DockerExecutionEngine(image = "embabel/agent-sandbox:latest")

ARG UBUNTU_VERSION=24.04

FROM ghcr.io/astral-sh/uv:latest AS uv

FROM ubuntu:${UBUNTU_VERSION} AS base

# Declare build platform args to ensure cache invalidation across platforms
ARG TARGETPLATFORM

ENV NPM_CONFIG_PREFIX=/usr/local/share/npm-global
ENV PATH=/home/agent/.local/bin:/usr/local/share/npm-global/bin:$PATH

WORKDIR /home/agent/workspace

# Install packages
RUN apt-get update && \
    apt-get install -yy --no-install-recommends \
        bc \
        ca-certificates \
        curl \
        dnsutils \
        git \
        jq \
        less \
        lsof \
        make \
        man-db \
        nodejs \
        npm \
        procps \
        psmisc \
        python3 \
        python3-pip \
        python3-venv \
        ripgrep \
        rsync \
        socat \
        sudo \
        unzip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Configure users and npm
# Remove existing ubuntu user (UID 1000) and create agent user
RUN userdel -r ubuntu 2>/dev/null || true && \
    useradd --create-home --uid 1000 --shell /bin/bash agent && \
    usermod -aG sudo agent && \
    echo "agent ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers && \
    mkdir -p /usr/local/share/npm-global && \
    chown -R agent:agent /usr/local/share/npm-global && \
    mkdir -p /input /output && \
    chown agent:agent /input /output && \
    chown -R agent:agent /home/agent

# Install uv for fast Python package management
COPY --link --from=uv /uv /usr/local/bin/uv

# Install common Python packages that skills might need (as root for system install)
RUN uv pip install --system --break-system-packages \
    pypdf \
    pdf2image \
    pillow \
    requests \
    pyyaml \
    jinja2

USER agent

# Default command
CMD ["/bin/bash"]
