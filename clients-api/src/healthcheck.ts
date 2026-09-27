
const port = process.env['PORT'] ?? '8080';

fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(2_000) })
  .then((res) => process.exit(res.ok ? 0 : 1))
  .catch(() => process.exit(1));
