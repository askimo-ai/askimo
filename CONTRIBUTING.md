# Contributing to Askimo

Thanks for considering a contribution! We welcome bug reports, feature requests, and pull requests.

## Ways to Contribute

- **Bug reports** — open an issue with steps to reproduce, expected vs. actual behavior, and your OS/version
- **Feature requests** — open an issue describing the use case and why it fits the project
- **Code contributions** — pick up an open issue (look for `good first issue` labels) or propose something new

Before opening a new issue, please search existing ones to avoid duplicates.

## Development Setup

Requirements: **JDK 21+**, **Gradle** (wrapper included).

```bash
# Clone your fork
git clone https://github.com/<your-username>/askimo.git
cd askimo

# Build everything
./gradlew build

# Run the desktop app
./gradlew :desktop:run
```

See [`AGENTS.md`](AGENTS.md) for a full breakdown of the module structure and key files.

## Code Style

Askimo uses **Spotless** (ktlint) and **Detekt** to enforce consistent formatting and static analysis. Run these before every commit:

```bash
# Auto-fix formatting
./gradlew spotlessApply

# Check only (what CI runs)
./gradlew spotlessCheck

# Static analysis
./gradlew detekt
```

### Optional: install the pre-commit hook

A pre-commit hook is provided that runs `spotlessApply` and `detekt` automatically on every `git commit`:

```bash
sh tools/git/pre-commit
```

This installs the hook into `.git/hooks/pre-commit`. You only need to run it once per clone.

Key style rules enforced by ktlint:
- 4-space indentation
- No wildcard imports
- Trailing newline on all files
- All source files must include the license header (`HEADER-SRC`)

PRs that fail `spotlessCheck` will not be merged.

## Running Tests

```bash
# Run all tests
./gradlew test

# Run tests for a specific module
./gradlew :shared:test
```

Please make sure all tests pass locally before opening a PR. If you are adding new functionality, include tests for it.

## Branch & PR Workflow

1. **Fork** the repository and create a branch from `main`:
   ```bash
   git checkout -b feat/my-feature
   ```
2. Make your changes, following the code style rules above.
3. Run `./gradlew spotlessApply` and `./gradlew test`.
4. Commit with a sign-off (see [DCO](#-developer-certificate-of-origin-dco) below).
5. Push your branch and open a Pull Request against `main`.
6. Fill in the PR description: what changed, why, and how to test it.

Keep PRs focused — one feature or fix per PR makes review faster.

## Commit Messages

We follow **Conventional Commits**:

```
<type>(<scope>): <short description>

[optional body]

Signed-off-by: Your Name <your.email@example.com>
```

Common types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`.

Examples:
```
feat(desktop): add DeepSeek provider support
fix(desktop): handle missing API key gracefully
docs: update CONTRIBUTING with test instructions
```

## 📝 Developer Certificate of Origin (DCO)

The DCO is a lightweight alternative to a CLA. By signing off your commits, you certify that:

> The contribution is your original work, or you have the right to submit it under the project's
> license, and you agree it can be distributed under the AGPLv3 License.

Full text: https://developercertificate.org/

### Signing off a commit

Add the `-s` flag when committing:

```bash
git commit -s -m "feat: add new feature"
```

This appends a `Signed-off-by` line to your commit message:

```
Signed-off-by: Your Name <your.email@example.com>
```

> **Note:** `-s` (DCO sign-off) is different from `-S` (GPG cryptographic signing). Only `-s` is required here.

### Forgot to sign off?

Single commit:
```bash
git commit --amend -s
git push --force-with-lease
```

Multiple commits:
```bash
git rebase --exec 'git commit --amend -s --no-edit' main
git push --force-with-lease
```

The **DCO GitHub App** automatically checks every commit in a PR. PRs without sign-offs will be blocked until all commits are signed.

## License

By contributing to Askimo, you agree that your contributions will be licensed under the
[GNU AGPLv3 License](LICENSE).

## Questions?

Open a [GitHub Discussion](https://github.com/askimo-ai/askimo/discussions) or file an issue — we're happy to help.
