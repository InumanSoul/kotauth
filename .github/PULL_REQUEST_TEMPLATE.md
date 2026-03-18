## What does this PR do?

<!-- A concise description of the change and the motivation behind it. Link the related issue if one exists. -->

Closes #

## Type of change

<!-- Mark the relevant option with an [x] -->

- [ ] Bug fix
- [ ] New feature
- [ ] Refactor (no behaviour change)
- [ ] Documentation
- [ ] CI / tooling
- [ ] Other:

## How was this tested?

<!-- Describe how you verified the change works correctly. -->

- [ ] Added / updated unit tests
- [ ] Added / updated integration tests
- [ ] Tested manually — describe steps below

<!-- Manual test steps if applicable -->

## Checklist

- [ ] `./gradlew test` passes locally
- [ ] `./gradlew ktlintCheck` passes (or run `./gradlew ktlintFormat` to auto-fix)
- [ ] New domain logic has no framework imports (hexagonal architecture constraint)
- [ ] New database changes include a Flyway migration
- [ ] Public API changes are reflected in `src/main/resources/openapi/v1.yaml`
- [ ] Security-sensitive changes (auth flows, token handling, encryption) are noted in the description

## Notes for reviewers

<!-- Anything the reviewer should pay particular attention to, known limitations, or follow-up work. -->
