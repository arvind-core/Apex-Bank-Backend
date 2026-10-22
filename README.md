# Apex Bank — Backend

Backend application for **Apex Bank**, a simulated banking platform modeling customer accounts, intra-bank transfers, UPI, beneficiaries, money requests, scheduled payments, debit and credit cards, loans and EMIs, branch operations, manager review queues, double-entry accounting, and bank-wide administration.

> **Simulation Notice:** This system is a learning and engineering portfolio project. It does not connect to real banking rails, card networks, or real money.

## Technology Stack

- **Language & Runtime:** Java 25
- **Framework:** Spring Boot 4.1.1
- **Architecture:** Feature-first modular monolith (`com.apexbank`)
- **Primary Database:** PostgreSQL (Neon PostgreSQL in cloud deployment)
- **Persistence:** Hybrid Spring Data JPA (transactional writes, state transitions, concurrency locking) + Spring JDBC (dynamic multi-filter reads, cross-table read models, statement streaming)
- **Schema Migrations:** Flyway
- **Mapping & Boilerplate:** MapStruct + Lombok
- **Supporting Services:** Upstash Redis (cache, rate limiting, active OTP/token accelerator), Cloudflare R2 (profile photos and temporary statement files)
- **Target Cloud Runtime:** AWS Lambda (via AWS Lambda Web Adapter), Amazon API Gateway HTTP API, Amazon EventBridge Scheduler, Amazon SNS, Amazon SQS, AWS CloudWatch

## Local Development Prerequisites

- **JDK:** Java 25 (`25.0.4` or compatible)
- **Containers:** Docker (required for local PostgreSQL and Testcontainers integration tests)
- **Build Tool:** Use the committed Maven Wrapper (`./mvnw`)

## Repository Structure

This repository is the root of the Spring Boot Maven project:

```text
Apex-Bank-Backend/
├── .mvn/
├── src/
│   ├── main/
│   └── test/
├── .gitignore
├── mvnw
├── mvnw.cmd
├── pom.xml
└── README.md
```