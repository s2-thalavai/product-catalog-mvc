// Product Catalog REST API (Spring Boot / Spring MVC) - CI/CD pipeline
//
//   Checkout -> Build & Test (unit, IT, Testcontainers, OpenAPI contract, JaCoCo gate)
//   -> Docker image -> Trivy scan -> Publish (k3d import or registry push)
//   -> [prod: manual approval] -> Deploy (kustomize) -> Smoke test -> auto-rollback on failure
//
// Agent needs: JDK 21, Maven, Docker CLI + daemon, kubectl, kustomize, k3d (for k3d-import).
// infra/jenkins builds a controller image with all of them.
//
// Credentials (Jenkins > Manage > Credentials):
//   kubeconfig-k3s   Secret file  - created by JCasC from infra/jenkins/secrets/kubeconfig-k3s
//   kubeconfig-prod  Secret file  - add manually before deploying to prod
//   registry-creds   Username/password - only for IMAGE_DELIVERY=registry-push

pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
    }

    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['k3s', 'prod', 'none'],
               description: 'Where to deploy. "none" = build, test and scan only.')
        choice(name: 'IMAGE_DELIVERY', choices: ['k3d-import', 'registry-push'],
               description: 'k3d-import: load the image straight into the local k3d cluster. registry-push: push to REGISTRY.')
        string(name: 'REGISTRY', defaultValue: 'CHANGE_ME.azurecr.io',
               description: 'Registry host[/path] used when IMAGE_DELIVERY=registry-push')
        string(name: 'K3D_CLUSTER', defaultValue: 'catalog', description: 'k3d cluster name')
        booleanParam(name: 'RUN_TRIVY', defaultValue: true, description: 'Fail on CRITICAL image vulnerabilities')
    }

    environment {
        APP_NAME      = 'product-catalog-mvc'
        K8S_NAMESPACE = 'catalog-mvc'
        MAVEN_OPTS    = '-Xmx1g'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.GIT_SHA     = sh(returnStdout: true, script: 'git rev-parse --short=8 HEAD').trim()
                    env.IMAGE_TAG   = "${env.BUILD_NUMBER}-${env.GIT_SHA}"
                    env.LOCAL_IMAGE = "${env.APP_NAME}:${env.IMAGE_TAG}"
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.GIT_SHA}"
                }
            }
        }

        stage('Build & Test') {
            steps {
                // verify = compile, unit tests, integration tests (incl. PostgreSQL via Testcontainers),
                // OpenAPI contract check, JaCoCo coverage gate, fat jar
                sh 'mvn -B -ntp clean verify'
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: 'target/surefire-reports/*.xml, target/failsafe-reports/*.xml'
                    recordCoverage(tools: [[parser: 'JACOCO', pattern: 'target/site/jacoco/jacoco.xml']])
                    archiveArtifacts artifacts: 'target/contract/**', allowEmptyArchive: true
                }
                success {
                    archiveArtifacts artifacts: 'target/product-catalog-mvc.jar', fingerprint: true
                }
            }
        }

        stage('Docker Image') {
            steps {
                sh '''
                    docker build --pull \
                      --label org.opencontainers.image.revision="$GIT_SHA" \
                      --label org.opencontainers.image.version="$IMAGE_TAG" \
                      -t "$LOCAL_IMAGE" .
                '''
            }
        }

        stage('Image Scan') {
            when { expression { params.RUN_TRIVY } }
            steps {
                sh '''
                    docker run --rm \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      -v trivy-cache:/root/.cache/ \
                      aquasec/trivy:0.57.0 image \
                      --exit-code 1 --severity CRITICAL --ignore-unfixed --no-progress \
                      "$LOCAL_IMAGE"
                '''
            }
        }

        stage('Publish Image') {
            when { expression { params.DEPLOY_ENV != 'none' } }
            steps {
                script {
                    if (params.IMAGE_DELIVERY == 'k3d-import') {
                        env.DEPLOY_IMAGE = env.LOCAL_IMAGE
                        sh 'k3d image import "$LOCAL_IMAGE" --cluster "$K3D_CLUSTER"'
                    } else {
                        env.DEPLOY_IMAGE = "${params.REGISTRY}/${env.APP_NAME}:${env.IMAGE_TAG}"
                        withCredentials([usernamePassword(credentialsId: 'registry-creds',
                                                          usernameVariable: 'REG_USER',
                                                          passwordVariable: 'REG_PASS')]) {
                            sh '''
                                echo "$REG_PASS" | docker login "${REGISTRY%%/*}" -u "$REG_USER" --password-stdin
                                docker tag "$LOCAL_IMAGE" "$DEPLOY_IMAGE"
                                docker push "$DEPLOY_IMAGE"
                                docker logout "${REGISTRY%%/*}"
                            '''
                        }
                    }
                    echo "Image for deployment: ${env.DEPLOY_IMAGE}"
                }
            }
        }

        stage('Approve Production') {
            when { expression { params.DEPLOY_ENV == 'prod' } }
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: "Deploy ${env.DEPLOY_IMAGE} to PRODUCTION?", ok: 'Deploy'
                }
            }
        }

        stage('Deploy') {
            when { expression { params.DEPLOY_ENV != 'none' } }
            steps {
                script { env.DEPLOY_STARTED = 'true' }
                withCredentials([file(credentialsId: "kubeconfig-${params.DEPLOY_ENV}", variable: 'KUBECONFIG')]) {
                    sh '''
                        set -e
                        # Pin this build's image in the overlay (workspace copy only, not committed).
                        (cd "k8s/overlays/$DEPLOY_ENV" && kustomize edit set image "product-catalog-mvc=$DEPLOY_IMAGE")
                        kustomize build "k8s/overlays/$DEPLOY_ENV" > rendered-manifests.yaml
                        kubectl apply -f rendered-manifests.yaml
                        if [ "$DEPLOY_ENV" = "k3s" ]; then
                          kubectl -n "$K8S_NAMESPACE" rollout status statefulset/product-catalog-mvc-postgres --timeout=180s
                        fi
                        kubectl -n "$K8S_NAMESPACE" rollout status deployment/product-catalog-mvc --timeout=300s
                    '''
                }
            }
            post {
                always {
                    archiveArtifacts artifacts: 'rendered-manifests.yaml', allowEmptyArchive: true
                }
            }
        }

        stage('Smoke Test') {
            when { expression { params.DEPLOY_ENV != 'none' } }
            steps {
                withCredentials([file(credentialsId: "kubeconfig-${params.DEPLOY_ENV}", variable: 'KUBECONFIG')]) {
                    script {
                        // Production: read-only checks only, never write test data.
                        env.SMOKE_FLAGS = params.DEPLOY_ENV == 'prod' ? '--read-only' : ''
                    }
                    sh '''
                        kubectl -n "$K8S_NAMESPACE" port-forward svc/product-catalog-mvc 18080:80 > port-forward.log 2>&1 &
                        PF_PID=$!
                        trap 'kill $PF_PID 2>/dev/null || true' EXIT
                        for i in $(seq 1 30); do
                          curl -fsS -o /dev/null http://127.0.0.1:18080/actuator/health/readiness && break
                          sleep 1
                        done
                        # Writes need a token: mint one with the cluster's dev JWT secret (absent in prod -> read-only)
                        export JWT_HMAC_SECRET="$(kubectl -n "$K8S_NAMESPACE" get secret product-catalog-mvc-jwt \
                            -o jsonpath='{.data.hmac-secret}' 2>/dev/null | base64 -d || true)"
                        bash scripts/smoke-test.sh http://127.0.0.1:18080 $SMOKE_FLAGS
                    '''
                }
            }
        }
    }

    post {
        failure {
            script {
                if (env.DEPLOY_STARTED == 'true') {
                    echo 'Deployment or smoke test failed - rolling back to the previous ReplicaSet'
                    withCredentials([file(credentialsId: "kubeconfig-${params.DEPLOY_ENV}", variable: 'KUBECONFIG')]) {
                        sh '''
                            kubectl -n "$K8S_NAMESPACE" rollout undo deployment/product-catalog-mvc || true
                            kubectl -n "$K8S_NAMESPACE" rollout status deployment/product-catalog-mvc --timeout=180s || true
                        '''
                    }
                }
            }
        }
        always {
            sh 'docker image rm "$LOCAL_IMAGE" >/dev/null 2>&1 || true'
        }
    }
}
