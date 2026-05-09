# ike-build-standards

**Documentation:** https://ike.network/ike-tooling/ike-build-standards/

Multi-classifier ZIP artifact carrying versioned reference material
for IKE Network projects: AI-assistant instruction files,
human-readable convention documents, shared build configuration,
AsciiDoc IDE-preview config, and the workspace scaffold manifest.

## Classifiers

| Classifier | Contents |
|---|---|
| `claude` | Markdown standards files unpacked to `.claude/standards/` per consumer at `validate` phase |
| `docs` | Human-readable convention documents in AsciiDoc |
| `config` | Shared static build config (editorconfig, checkstyle, stignore template) |
| `asciidoctorconfig` | Shared `.asciidoctorconfig` for IDE AsciiDoc preview |
| `scaffold` | Workspace scaffold manifest + template files |

See the [full module documentation](https://ike.network/ike-tooling/ike-build-standards/)
for the complete classifier inventory and how each is consumed.
