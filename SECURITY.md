# Security Policy

## Supported Versions

The `main` branch is the active development line unless the repository defines release branches.

## Reporting a Vulnerability

Please do not disclose security issues in public issues.

Report vulnerabilities privately to the repository maintainer. Include:

- Affected version or commit.
- Steps to reproduce.
- Impact and expected behavior.
- Relevant logs or sanitized snippets.

## Sensitive Data

This application is designed to parse local SQL files. Before publishing issues, examples, screenshots, or test fixtures, remove:

- Real database names, table names, column names, and procedure logic if they are confidential.
- Business SQL files from internal systems.
- SQLite runtime databases such as `metadata.sqlite`.
- Session cookies, passwords, access tokens, private keys, and internal network addresses.

## Default Accounts

The open-source project includes fixed demo/local accounts for simple offline deployments. For production or public deployments, review the authentication model and replace fixed credentials with your organization's identity provider or a stronger account-management process.
