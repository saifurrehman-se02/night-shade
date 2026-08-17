<div align="center">

# ☽ Nightshade — LLM Anti-Scraping & Code Obfuscation Engine

**Poison your source code before LLMs do.**

> **Not to be confused with** the [UChicago Nightshade](https://github.com/Shawn-Shan/nightshade-release) image poisoning tool. This is Nightshade for **source code** — protecting Java, Python, and JavaScript from unauthorized AI training data scraping.

[![CI](https://github.com/devhms/nightshade/actions/workflows/ci.yml/badge.svg)](https://github.com/devhms/nightshade/actions/workflows/ci.yml)
[![CodeQL](https://github.com/devhms/nightshade/actions/workflows/codeql.yml/badge.svg)](https://github.com/devhms/nightshade/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/devhms/nightshade/badge)](https://securityscorecards.dev/viewer/?uri=github.com/devhms/nightshade)
[![SLSA 3](https://slsa.dev/images/gh-badge-level3.svg)](https://slsa.dev)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-21-blue?logo=openjdk)](https://adoptium.net/)
[![Maven](https://img.shields.io/badge/Built%20with-Maven-C71A36?logo=apachemaven)](https://maven.apache.org/)
[![Version](https://img.shields.io/badge/Version-3.5.0-brightgreen)](CHANGELOG.md)

</div>

---

**Nightshade** is an open-source LLM training data poisoning engine for source code. It applies 8 adversarial transformation strategies (5 enabled by default) to Java, Python, and JavaScript code before public release. The poisoned code compiles, passes tests, and runs identically — but when scraped for LLM training, it degrades model quality on your patterns. Evades MinHash/LSH deduplication, survives preprocessing, and integrates as a CLI tool, GitHub Action, or pre-commit hook.

```bash
java -jar nightshade.jar --input ./src --output ./poisoned
```

> :star: **If Nightshade protects your code, please star the repo — it helps others find this tool.**

---

## ✨ Features

| Capability | Description |
|---|---|
| **8 Poisoning Strategies** | Variable scrambling, dead code, comment poisoning, string encoding, whitespace disruption, semantic inversion, control flow flattening, watermark embedding |
| **Multi-Language** | Java (full), Python (full), JavaScript (full), TypeScript (via `.js`) |
| **Functional Integrity** | Poisoned code compiles and runs identically — guaranteed |
| **Deduplication Evasion** | MinHash/LSH filters cannot detect poisoned copies as near-duplicates |
| **Entropy Scoring** | Weighted 0.0–1.0 score with configurable early-exit threshold |
| **Compilation Verification** | Optional `--verify` flag runs `javac` post-obfuscation |
| **Supply Chain Security** | SLSA Level 3 provenance, Sigstore Cosign signatures, CycloneDX SBOM |
| **CI/CD Ready** | GitHub Action, pre-commit hook, Docker support |
| **GUI & CLI** | JavaFX desktop UI and headless CLI mode |

## 🚀 Quick Start

```bash
# 1. Clone
git clone https://github.com/devhms/nightshade.git && cd nightshade

# 2. Build
mvn clean package -q

# 3. Poison your code
java -jar target/nightshade-3.5.0-all.jar --input ./src --output ./poisoned
```

**Requirements:** JDK 21+, Maven 3.9+

## 🏗️ Architecture

```
Source Code → Lexer → Parser → Strategy Pipeline → Poisoned Code
                                   │
                            Entropy Score (0.0–1.0)
                                   │
                            Early exit ≥ threshold
```

### Strategy Pipeline

| ID | Strategy | Default | Weight | Mechanism | Research |
|---|---|---|---|---|---|
| **A** | Variable Entropy Scrambling | ✅ ON | 0.50 | Renames identifiers with deterministic SHA-256 hashes | arXiv:2512.15468 |
| **B** | Dead Code Injection | ✅ ON | 0.30 | Inserts unreachable but plausible code blocks | Preprocessing-proof |
| **C** | Comment Poisoning | ✅ ON | 0.20 | Replaces comments with semantically opposite text | Backdoor research |
| **D** | String Encoding | ✅ ON | 0.05* | Encodes string literals as char arrays | MinHash/LSH evasion |
| **E** | Whitespace Disruption | ✅ ON | 0.05* | Randomizes indentation, adds zero-width chars | BPE disruption |
| **F** | Semantic Inversion | ❌ OFF | — | Misleading domain-mismatch variable names | Semantic confusion |
| **G** | Control Flow Flattening | ❌ OFF | — | Switch-dispatch loop rewriting | Structure obfuscation |
| **H** | Watermark Encoder | ❌ OFF | — | Steganographic whitespace fingerprint | Copyright tracking |

*\*Bonus strategies — contribute to clamped final score.*

**Entropy Formula:**
```
entropy = (renamed/totalIdentifiers) × 0.5
        + (deadBlocks/totalMethods) × 0.3
        + (commentsPoisoned/totalComments) × 0.2
        + bonus (strings/whitespace)
```
Default threshold: **0.65**. Pipeline exits early once reached.

## 📦 Installation

### Pre-built JAR

Download from [GitHub Releases](https://github.com/devhms/nightshade/releases):

```bash
curl -LO https://github.com/devhms/nightshade/releases/download/v3.5.0/nightshade-3.5.0-all.jar
java -jar nightshade-3.5.0-all.jar --help
```

### From source

```bash
git clone https://github.com/devhms/nightshade.git
cd nightshade
mvn clean package
# Fat JAR at: target/nightshade-3.5.0-all.jar
```

### GitHub Action

```yaml
- name: Protect code with Nightshade
  uses: devhms/nightshade@v3.5.0
  with:
    input-dir: './src'
    output-dir: './obfuscated-src'
    strategies: 'all'
    entropy-threshold: '0.65'
```

### Pre-commit Hook

```yaml
repos:
  - repo: https://github.com/devhms/nightshade
    rev: v3.5.0
    hooks:
      - id: nightshade
```

## 🖥️ CLI Reference

| Flag | Short | Default | Description |
|---|---|---|---|
| `--input <path>` | `-i` | required | Source file or directory |
| `--output <path>` | `-o` | `../_nightshade_output` | Output directory |
| `--strategies <list>` | `-s` | `all` | Comma-separated: `entropy,deadcode,comments,strings,whitespace,semantic,controlflow,watermark` |
| `--threshold <n>` | `-t` | `0.65` | Early-exit entropy threshold (0.0–1.0) |
| `--dry-run` | | `false` | Preview without writing files |
| `--verify` | | `false` | Run `javac` post-obfuscation verification |
| `--library-mode` | | `false` | Preserve public APIs, obfuscate internals |
| `--report` | `-r` | `false` | Generate Markdown report |
| `--verbose` | `-v` | `false` | Detailed processing logs |
| `--quiet` | `-q` | `false` | Errors and summary only |
| `--list-strategies` | | | Show all available strategies |
| `--version` | | | Print version |
| `--help` | `-h` | | Show help |

### Examples

```bash
# Basic usage
java -jar nightshade.jar -i ./src -o ./poisoned

# Selective strategies with verification
java -jar nightshade.jar -i ./src -s entropy,deadcode,comments --verify -v

# Custom threshold + dry-run
java -jar nightshade.jar -i ./src --threshold 0.8 --dry-run

# Library mode (preserves public APIs)
java -jar nightshade.jar -i ./src --library-mode

# Single file
java -jar nightshade.jar -i src/Main.java -o ./poisoned
```

## 📊 Output & Reports

Each run produces:
- **Obfuscated source files** in the output directory
- **`nightshade_run.log`** — per-file entropy breakdown
- **`nightshade_report.md`** (with `--report`) — full Markdown report

```
Output: ./poisoned/
├── com/example/
│   ├── Main.java        # Obfuscated
│   └── Helper.java      # Obfuscated
├── nightshade_run.log
└── nightshade_report.md (optional)
```

## 🔒 Supply Chain Security

| Measure | Status |
|---|---|
| **SLSA Level 3** | ✅ Every release has cryptographic provenance |
| **Sigstore Cosign** | ✅ Keyless OIDC signatures on all JARs |
| **CycloneDX SBOM** | ✅ Complete dependency manifest per release |
| **OpenSSF Scorecard** | ✅ Continuously monitored |

**Verify a release:**
```bash
# Verify signature
cosign verify-blob \
  --certificate-identity "https://github.com/devhms/nightshade/.github/workflows/release.yml@refs/tags/v3.5.0" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  --bundle nightshade.sig \
  nightshade-3.5.0-all.jar

# Verify SLSA provenance
slsa-verifier verify-artifact nightshade-3.5.0-all.jar \
  --provenance-path multiple.intoto.jsonl \
  --source-uri github.com/devhms/nightshade
```

## 🔬 Research Basis

| Reference | Finding | Applies To |
|---|---|---|
| **arXiv:2512.15468** (Yang et al., 2025) | Variable renaming causes **10.19% MI detection drop** with only **0.63% performance loss** | Strategy A |
| **OWASP LLM Top 10 — LLM04** | Training-data poisoning is a critical threat for code-generation models | All strategies |
| **Backdoor Attack Research (2024–2025)** | Poisoning effective with as little as **0.001% malicious samples** | B, C |
| **MinHash/LSH Dedup Research** | Near-duplicate detection fails when **≥15% of tokens differ** | D, E |

## 🗺️ Project Structure

```
nightshade/
├── src/main/java/com/nightshade/
│   ├── CLI.java                    # CLI entry point
│   ├── Main.java                   # Bootstrap (CLI/GUI router)
│   ├── engine/
│   │   ├── Lexer.java              # Language-aware tokenizer
│   │   ├── Parser.java             # Simplified AST builder
│   │   ├── Serializer.java         # Token-to-source reconstruction
│   │   ├── ObfuscationEngine.java  # Pipeline coordinator
│   │   ├── EntropyCalculator.java  # Weighted entropy scorer
│   │   ├── FileWalker.java         # Recursive directory scanner
│   │   ├── CompilationVerifier.java # Post-obfuscation javac check
│   │   └── PoisoningReport.java    # Markdown report generator
│   ├── model/
│   │   ├── ASTNode.java            # Composite-pattern AST
│   │   ├── SourceFile.java         # Raw + obfuscated lines
│   │   ├── SymbolTable.java        # Scope-aware identifier mapping
│   │   ├── ObfuscationResult.java  # Per-file result + stats
│   │   ├── Token.java              # Immutable lexical token
│   │   └── TokenType.java          # Token classification
│   ├── strategy/
│   │   ├── PoisonStrategy.java     # Plugin interface
│   │   ├── EntropyScrambler.java   # A — Variable renaming
│   │   ├── DeadCodeInjector.java   # B — Dead code
│   │   ├── CommentPoisoner.java    # C — Comment poisoning
│   │   ├── StringEncoder.java      # D — String encoding
│   │   ├── WhitespaceDisruptor.java # E — Whitespace
│   │   ├── SemanticInverter.java   # F — Semantic inversion
│   │   ├── ControlFlowFlattener.java # G — Control flow
│   │   └── WatermarkEncoder.java   # H — Watermark
│   └── util/
│       ├── FileUtil.java           # I/O helpers
│       ├── HashUtil.java           # FNV-1a hashing
│       └── LogService.java         # Observable log stream
├── scripts/evaluate.sh             # Evaluation harness
└── src/test/                       # JUnit 5 test suite
```

## 🌐 Supported Languages

| Language | Extension | Support |
|---|---|---|
| Java | `.java` | ✅ Full (all 8 strategies) |
| Python | `.py` | ✅ Full (A–E) |
| JavaScript | `.js` | ✅ Full (A–E) |
| TypeScript | `.ts` | 🔗 Via `.js` processing |
| C# | `.cs` | 🚧 Planned |
| Go | `.go` | 🚧 Planned |
| Rust | `.rs` | 🔬 Researching |

## 🤝 Contributing

1. Read [CONTRIBUTING.md](CONTRIBUTING.md) — Google Java Style, conventional commits
2. Check [good first issues](https://github.com/devhms/nightshade/issues?q=label%3A%22good+first+issue%22)
3. Fork → branch → PR

Please follow our [Code of Conduct](CODE_OF_CONDUCT.md).

## ❓ FAQ

**Does it break my code?** No. All 8 strategies are semantics-preserving. The `--verify` flag runs `javac` to confirm.

**Can I use commercially?** Yes — MIT License. No restrictions.

**How do I skip specific code blocks?** Add `// @nightshade:skip` and `// @nightshade:resume` comments.

**Does it protect against all AI scrapers?** No tool is 100% effective. Nightshade raises the cost of scraping your code to the point where most pipelines will filter it out or produce degraded results.

## 📜 License

MIT License — see [LICENSE](LICENSE) for full text.

## 👥 Authors

| Name | Role | Contact |
|---|---|---|
| Ibrahim Salman | Creator & Lead | [@devhms](https://github.com/devhms) |
| Saif-ur-Rehman | Co-Creator | — |

*University of Engineering and Technology Taxila*

---

<div align="center">

[![Star History Chart](https://api.star-history.com/svg?repos=devhms/nightshade&type=Date)](https://star-history.com/#devhms/nightshade&Date)

**If Nightshade protects your code, please ⭐ star the repo.**

</div>
# night-shade
