# Contributing to StarChem

StarChem is proprietary software and is not currently accepting unsolicited outside code, asset, documentation, configuration, or other implementation contributions.

Bug reports and general feedback may be submitted through GitHub Issues. Do not include confidential information, third-party proprietary material, copied code, real server backups, authentication material, TLS private keys/passwords, moderation/observation data, or material that you do not have the right to share.

Do not open a pull request or submit implementation material unless the copyright owner has first approved the contribution in writing and the parties have agreed to ownership and licensing terms. Unrequested pull requests may be closed without review or use.

Submitting an issue, suggestion, discussion post, or pull request does not grant any license to StarChem and does not obligate the copyright owner to use, credit, compensate, or maintain the submission.

## Maintainer verification contract

Authorized repository work targets Java 17 and the Gradle/release workflow versions committed in the repository. Before a release-facing change is merged, maintainers should run or require CI to run:

```text
gradle clean check jar --no-daemon
bash validation/run-release-regressions.sh 'build/classes/java/main:build/resources/main'
```

Release-facing documentation is part of the compatibility contract. Changes to application version, multiplayer protocol, rules version, save format, authentication, TLS identity handling, command-line options, packaging, launchers, or persistent-server migration must update the corresponding documentation and pass the release metadata/docs validators.

The current publishing path is `.github/workflows/release.yml`. Do not re-enable historical one-shot release workflows or manually substitute artifacts for a validated tagged release.
