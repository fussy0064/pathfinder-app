# Security review (Phase 5)

Scope: this repo as of Phase 5. "Tested" = an automated test in this repo covers it. "Reviewed" = I read the code, no test.

## What is protected, and how
| Risk | Protection | Status |
|---|---|---|
| Corrupt or hostile `map_graph.bin` | Magic, version, exact size, sorted indexes, every edge target checked before use | Tested (fuzz: 1500 corrupted files, 0 crashes, also under ASan/UBSan) |
| Corrupt or hostile `.ch` index | Same checks + rank permutation + shortcut order + graph fingerprint; bad file = silent fallback to A* | Tested (fuzz: 1500 corrupted files, 0 crashes) |
| Shortcut unpacking loops | Step limit = 2 x nodes; fails safely | Tested + reviewed |
| Bad GPS input (NaN, out of range, null pointers) | Checked in Dart and again in C++ | Tested |
| SQL injection in search | Parameterized SQL, `%` `_` `\` escaped, 80 char limit, result limit | Tested (Python, same SQL) |
| Hostile download catalog | Fixed file names, regex-checked paths (no `..`, no `//`, no URLs), size limits, strict SHA-256 format | Tested (Python + Dart tests) |
| Man-in-the-middle / bad server data | https only, no redirects, size and SHA-256 verified before install, temp folder then move | Dart tests written (run them in CI or on your PC) |
| Half-installed data | Download to `.download/`, verify all, then move; graph moved last; stale `.ch` removed | Dart tests written |
| Location privacy | No network code in the routing/navigation path. Location never leaves the phone. | Reviewed |
| Network exposure | No INTERNET permission unless you want the downloader (see RELEASE.md). Cleartext off, backups off. | Reviewed |
| Native hardening | Stack protector, RELRO + BIND_NOW, hidden symbols (only the 20 `pf_*` functions exported), 16 KB pages | Reviewed |
| Concurrency | One mutex per engine; TSan run with 8 threads: clean | Tested |
| Secrets in git | `.gitignore` blocks keys, keystores, `.env`, `key.properties` | Reviewed |

## Known gaps (not fixed)
1. **The catalog is not signed.** Its integrity relies on HTTPS and on trusting your server. If the server is hacked, an attacker can publish a bad pack with matching hashes. Fix later: sign `catalog.json` (Ed25519) and pin the public key in the app.
2. **No certificate pinning.** Normal system trust store is used.
3. **SQLite files from the server are parsed by SQLite** (`places.db`, `tiles.mbtiles`). They are opened read-only and hash-checked, but a malicious file that passes your own hash check could target SQLite bugs. Only host packs you built yourself.
4. **mmap and file changes:** if another program truncates `map_graph.bin` while the app runs, the app can crash (SIGBUS). Normal for mmap.
5. **No tamper/root detection.** Not needed for a free offline app, but note it.
6. **Dart and Android code has not been compiled or run by me** (no SDK here). Security claims for Dart code are from review plus the tests I wrote; run them.

## Housekeeping found during this review
- Build folders (`build-san`, `build-tsan`) were accidentally committed in earlier phases. They are now removed from git and ignored. They remain in old git history (about 6 MB, no secrets).
- **Your GitHub token was pasted into chat several times. Revoke it when we finish, and create a new one for any future work.**
