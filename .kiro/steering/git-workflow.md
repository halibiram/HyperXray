# Git Workflow and Branching Strategy

## Branch Naming Convention
- Feature branches: `feature/description-of-feature`
- Bug fixes: `fix/description-of-bug`
- Hotfixes: `hotfix/critical-issue-description`
- Releases: `release/version-number`
- Native builds: `native/go-library-changes`

## Commit Message Format
Follow conventional commits format:
```
type(scope): description

[optional body]

[optional footer]
```

Types: feat, fix, docs, style, refactor, test, chore, native, build

Examples:
- `feat(vpn): add WireGuard over Xray tunneling`
- `fix(dns): resolve cache invalidation issue`
- `native(bridge): update Go bridge for arm64`
- `build(gradle): upgrade AGP to 8.13.1`

## Pull Request Guidelines
- Create PR from feature branch to main/develop
- Include clear description of changes
- Link related issues using keywords (fixes #123)
- Ensure all tests pass before requesting review
- Squash commits when merging to keep history clean
- For native changes, include build verification on all architectures

## Code Review Process
- At least one approval required before merge
- Review for code quality, security, and performance
- Check that tests cover new functionality
- Verify documentation is updated if needed
- For native Go code, verify memory safety and JNI compatibility
- Ensure no breaking changes without proper versioning
