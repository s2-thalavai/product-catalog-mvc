# Shortcuts for the common commands (HOW_TO_RUN.md lists the raw commands too).
IMAGE   ?= product-catalog-mvc:local
CLUSTER ?= catalog
URL     ?= http://localhost:8080

.PHONY: help build test run run-pg compose-db image compose-up compose-down k3d-up k3d-deploy k3d-down \
        jenkins-up token smoke bruno contract render clean

help:            ## List targets
	@grep -E '^[a-z0-9-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-14s %s\n", $$1, $$2}'

build:           ## Package the executable Spring Boot jar (skips tests)
	mvn -B -ntp clean package -DskipTests

test:            ## Unit + integration tests, contract check, coverage gate
	mvn -B -ntp clean verify

run:             ## Run locally: in-memory H2, demo data, dev JWT secret
	mvn -B -ntp spring-boot:run

run-pg:          ## Run locally against the Compose PostgreSQL (make compose-db first)
	SPRING_PROFILES_ACTIVE=postgres SEED_DATA=true mvn -B -ntp spring-boot:run

compose-db:      ## Start only PostgreSQL from Compose (port 5432)
	docker compose up -d postgres

token:           ## Print a dev write token (scope catalog.write, 1h)
	@./scripts/dev-token.sh

image:           ## Build the Docker image
	docker build -t $(IMAGE) .

compose-up:      ## PostgreSQL + API in Docker Compose
	docker compose up -d --build

compose-down:    ## Stop Compose and delete the database volume
	docker compose down -v

k3d-up:          ## Create the local k3s cluster (k3d)
	./infra/k3d/create-cluster.sh

k3d-deploy:      ## Build, import and deploy to the k3d cluster
	./infra/k3d/deploy-local.sh

k3d-down:        ## Delete the k3d cluster
	./infra/k3d/delete-cluster.sh

jenkins-up:      ## Start the local Jenkins (after k3d-up)
	./infra/jenkins/start-jenkins.sh

smoke:           ## Smoke test URL (e.g. make smoke URL=http://localhost:8080)
	./scripts/smoke-test.sh $(URL)

bruno:           ## Run the Bruno collection (needs: npm i -g @usebruno/cli)
	cd api-collections/bruno/product-catalog-mvc && bru run --env local

contract:        ## Regenerate the OpenAPI baseline (intended changes only)
	./scripts/update-contract.sh

render:          ## Print the k3s manifests without applying them
	kubectl kustomize k8s/overlays/k3s

clean:
	mvn -B -ntp -q clean
