# Local evaluation data

`datasets/` is intentionally local-only. It contains shallow clones of public upstream benchmark
repositories used to build and run evaluation adapters; no Docker images, model weights, or
private data are downloaded here.

See `datasets/manifest.md` for the intended use and the exact upstream source of each category.
Before publishing any result, pin the upstream commit, record its license, and save the evaluated
task IDs and configuration alongside the result.
