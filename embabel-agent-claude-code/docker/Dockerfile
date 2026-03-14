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
        python3 \
        ripgrep \
        sudo \
        unzip && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

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
