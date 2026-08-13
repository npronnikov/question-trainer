# Local Codex Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make local startup load an explicit `CODEX_PATH`, reject a broken Codex executable before boot, and use the working ChatGPT-bundled binary for this workspace.

**Architecture:** `scripts/run-local.sh` remains the single local-entry point. It loads optional root `.env` values without overriding already-exported environment variables, resolves a Codex candidate only when `CODEX_PATH` is absent, validates the candidate with `--version`, and exposes a non-starting `--check-config` path for regression tests.

**Tech Stack:** Bash, Node.js built-in test runner, dotenv-style shell assignments, Git.

---

### Task 1: Specify startup configuration behavior

**Files:**
- Create: `scripts/tests/run-local.test.mjs`
- Modify: `README.md`

- [ ] **Step 1: Write the failing test**

Create a Node test that copies `scripts/run-local.sh` into a temporary project, writes a fake executable Codex path into `.env`, invokes `bash scripts/run-local.sh --check-config`, and asserts exit code `0` plus the resolved path. Add a second case whose fake binary exits non-zero and assert a non-zero exit with `Настроенный CODEX_PATH не запускается`.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
node --test scripts/tests/run-local.test.mjs
```

Expected: FAIL because the current script does not load `.env`, does not support `--check-config`, and only trusts `command -v codex`.

- [ ] **Step 3: Document the test suite command**

Change the README test command to:

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
```

### Task 2: Load and validate the configured Codex executable

**Files:**
- Modify: `scripts/run-local.sh`
- Modify: `.env.example`
- Modify locally, ignored by Git: `.env`

- [ ] **Step 1: Load root `.env` with exported environment priority**

Before defaults are resolved, source `$project_dir/.env` when present, then restore the exported variables captured before sourcing so explicit command-line environment values remain authoritative.

- [ ] **Step 2: Resolve and validate `CODEX_PATH`**

If `CODEX_PATH` is set, require it to be executable and require `"$CODEX_PATH" --version` to succeed. If it is unset, try the executable returned by `command -v codex`, then `/Applications/ChatGPT.app/Contents/Resources/codex`, accepting the first candidate whose `--version` succeeds. If no candidate works, exit with a message explaining how to set `CODEX_PATH`.

- [ ] **Step 3: Add configuration-only mode**

After printing the resolved Codex path, exit successfully when the sole argument is `--check-config`; reject other arguments with a usage error. This mode must not start Maven or Node.

- [ ] **Step 4: Record the workspace-local setting**

Add this ignored local setting without changing other `.env` values:

```dotenv
CODEX_PATH=/Applications/ChatGPT.app/Contents/Resources/codex
```

Add `CODEX_PATH=` and a short explanation to `.env.example` and README.

- [ ] **Step 5: Run the focused test and verify GREEN**

Run:

```bash
node --test scripts/tests/run-local.test.mjs
```

Expected: both valid and invalid configured-path cases pass.

### Task 3: Verify and activate the local configuration

**Files:**
- Verify only: repository and running processes

- [ ] **Step 1: Run all automated checks**

Run:

```bash
node --test frontend/tests/*.test.mjs scripts/tests/*.test.mjs
bash -n scripts/run-local.sh
./scripts/run-local.sh --check-config
git diff --check
```

Expected: all commands exit `0`; config output names `/Applications/ChatGPT.app/Contents/Resources/codex`.

- [ ] **Step 2: Restart local services**

Stop only the current `run-local.sh` process tree, then start `./scripts/run-local.sh` again. Confirm ports `8081` and `8090` are listening.

- [ ] **Step 3: Verify ACP with a real request**

Exercise the authenticated local application and wait for one ACP-backed response. Then verify `GET /api/system/status` reports `acpAvailable: true` and no `ENOENT` reason.

- [ ] **Step 4: Commit implementation**

```bash
git add scripts/run-local.sh scripts/tests/run-local.test.mjs .env.example README.md
git commit -m "fix: configure working local Codex binary"
```
