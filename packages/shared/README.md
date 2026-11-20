# @chainbank/shared

**Reserved, and currently empty.** It's kept in the workspace for shared TypeScript types, if the web app ever moves to types generated from the API.

Where those artefacts live today:
- **API contract:** [`docs/api/openapi.yaml`](../../docs/api/openapi.yaml), refreshed with `make openapi`. The web app's types are hand-written in `apps/web/src/lib/banking.ts`.
- **Contract ABIs:** exported by `pnpm --filter @chainbank/contracts export` into `services/api/src/main/resources/contracts/`.
