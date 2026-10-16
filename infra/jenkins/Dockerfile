# syntax=docker/dockerfile:1.7
# Jenkins controller for the local lab, with everything the Jenkinsfile calls:
# JDK 21, Maven, Docker CLI (+buildx), kubectl, kustomize and k3d.
# Plugins and the pipeline job are configured as code (plugins.txt, casc.yaml).

FROM docker:27-cli AS dockercli

FROM jenkins/jenkins:lts-jdk21

USER root
ARG TARGETARCH
ARG MAVEN_VERSION=3.9.9
ARG KUBECTL_VERSION=v1.31.2
ARG KUSTOMIZE_VERSION=v5.5.0
ARG K3D_VERSION=v5.7.4

COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins /usr/local/libexec/docker/cli-plugins

RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
      | tar -xz -C /opt; \
    ln -s "/opt/apache-maven-${MAVEN_VERSION}/bin/mvn" /usr/local/bin/mvn; \
    curl -fsSLo /usr/local/bin/kubectl "https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/${TARGETARCH}/kubectl"; \
    chmod +x /usr/local/bin/kubectl; \
    curl -fsSL "https://github.com/kubernetes-sigs/kustomize/releases/download/kustomize%2F${KUSTOMIZE_VERSION}/kustomize_${KUSTOMIZE_VERSION}_linux_${TARGETARCH}.tar.gz" \
      | tar -xz -C /usr/local/bin kustomize; \
    curl -fsSLo /usr/local/bin/k3d "https://github.com/k3d-io/k3d/releases/download/${K3D_VERSION}/k3d-linux-${TARGETARCH}"; \
    chmod +x /usr/local/bin/k3d; \
    mvn -v; docker --version; kubectl version --client; kustomize version; k3d version

USER jenkins
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
COPY --chown=jenkins:jenkins casc.yaml /usr/share/jenkins/ref/casc.yaml

ENV CASC_JENKINS_CONFIG=/usr/share/jenkins/ref/casc.yaml \
    JAVA_OPTS="-Djenkins.install.runSetupWizard=false"
