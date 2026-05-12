# Polymarket Public Leaderboard Source Contract

## Source

- Provider: Polymarket Data API
- Official docs: https://docs.polymarket.com/api-reference/core/get-trader-leaderboard-rankings
- Endpoint: `GET https://data-api.polymarket.com/v1/leaderboard`
- First implementation source enum: `PUBLIC_LEADERBOARD`

## Request

The first production client uses a conservative single-page request:

```text
GET /v1/leaderboard?category=OVERALL&timePeriod=MONTH&orderBy=PNL&limit=25
```

Supported configuration keys:

- `leader.research.public-leaderboard.enabled`: default `true`
- `leader.research.public-leaderboard.category`: default `OVERALL`
- `leader.research.public-leaderboard.time-period`: default `MONTH`
- `leader.research.public-leaderboard.order-by`: default `PNL`
- `leader.research.public-leaderboard.limit`: default `25`, clamped to `1..100`

## Response Fields

Observed and supported fields:

- `proxyWallet`: wallet used as candidate identity
- `rank`: source rank, parsed as integer when possible
- `pnl`: leaderboard profit metric
- `vol` or `volume`: volume metric
- `userName`, `pseudonym`, `name`, `xUsername`, `verifiedBadge`: display and evidence metadata

The client accepts missing display fields, but rejects entries without a normalizable wallet.

## Pagination And Limits

The first implementation does not paginate. It intentionally fetches one bounded page so a manual research run cannot create an unbounded intake spike. If a later API version exposes stable offset or cursor pagination, pagination should stay behind a separate per-run page cap.

## Failure Semantics

- Config disabled maps to source status `DISABLED` and is shown as an expected limitation.
- Non-2xx response, empty body, parsing failure, timeout, or network error maps to source `FAILURE`.
- Backfill failures after candidate discovery map the source to `DEGRADED`, preserving discovered candidates and surfacing backfill errors in source health.
- Missing optional fields do not fail the source.
- Missing or invalid wallet excludes that row from intake.

## Sample Payload Shape

```json
[
  {
    "proxyWallet": "0x0000000000000000000000000000000000000000",
    "rank": "1",
    "pnl": 1234.56,
    "vol": 98765.43,
    "userName": "example",
    "verifiedBadge": true
  }
]
```

## Quality Gate Notes

The leaderboard is only a discovery source. It does not by itself create a Leader Pool item, enable copy trading, or imply a candidate is safe. Candidates still need existing intake, paper trading, scoring, valuation checks, and `TRIAL_READY` state before trial configuration can be created. Real-money Autopilot additionally requires explicit user enablement and a successful decision-service check.

## Sample Pull Verification

Checked on 2026-05-11 with:

```text
GET https://data-api.polymarket.com/v1/leaderboard?category=OVERALL&timePeriod=MONTH&orderBy=PNL&limit=25
```

Observed result:

- Returned 25 rows.
- All 25 rows included a normalizable `proxyWallet`.
- All 25 rows included `rank`, `pnl`, and `vol`.
- Several rows included user/profile display metadata such as `userName`, `xUsername`, `verifiedBadge`, and `profileImage`.
- The response did not include enough per-wallet recent activity fields by itself to prove copyability, such as complete trade history, market mix, drawdown, or paper-trading sample quality.

Offline quality conclusion:

- The source is suitable for candidate discovery because it provides unique wallet identities plus rank/profit/volume evidence.
- The source is not sufficient for direct recommendation or real-money activation because leaderboard PnL can be non-repeatable and may not match what a follower can copy.
- First-version intake therefore treats leaderboard rows as `DISCOVERED` candidates only. They must still pass wallet normalization, dedupe, backfilled trade activity, paper-trading quality, risk flags, shortlist grouping, and Autopilot decision checks before any trial configuration can be created.
- Missing `trades`, `winRate`, or `lastTradeTime` should not fail source ingestion, but should keep recommendation confidence dependent on backfilled activity and paper results rather than raw leaderboard rank.
