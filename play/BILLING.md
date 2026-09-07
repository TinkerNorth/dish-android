# Tips through Google Play

The Play build sells tips through Google Play's billing system, because the
Play Payments policy requires it of any Play-distributed app and forbids
linking Play users to another payment method. The GitHub build keeps its
external links. This file is the operator side: what exists in Play
Console, how it gets there, and how to test it. The code side is described
in `docs/design-system.md` under "Distribution flavors".

## Catalog

Product ids are permanent once created and name a tier, not an amount. The
app reads titles and prices from Play at runtime, so every price below can
be changed in Play Console without shipping an update. Default prices are
in CAD, the merchant account's currency; Play converts them per country.

One-time tips (consumable managed products):

| Product id | Default price (CAD) | Title |
|---|---|---|
| `tip_5` | 5.00 | Small tip |
| `tip_10` | 10.00 | Medium tip |
| `tip_25` | 25.00 | Large tip |
| `tip_50` | 50.00 | Big tip |
| `tip_100` | 100.00 | Huge tip |
| `tip_max` | 999.99 | Legendary tip |

`tip_max` is meant to sit at Play's price ceiling. The spec sets 999.99 so
the create call cannot be refused; raise it in Play Console to whatever the
console shows as the maximum for CAD.

Monthly supporter (one subscription, `supporter_monthly`, one base plan per
amount, all `P1M` with a 30-day grace period):

| Base plan id | Price per month (CAD) |
|---|---|
| `monthly-1` | 1.00 |
| `monthly-3` | 3.00 |
| `monthly-5` | 5.00 |
| `monthly-10` | 10.00 |
| `monthly-25` | 25.00 |
| `monthly-50` | 50.00 |
| `monthly-100` | 100.00 |

A user holds one base plan at a time; picking another amount in the app is
a plan change with time-based proration, not a second subscription.

The authoritative copy of this catalog is `play/products.json`. The app's
copy of the ids is `TipCatalog` in `app/src/play`.

## Play Console prerequisites, in order

1. Merchant payments profile linked to the developer account, with the
   Canadian bank account and tax info filled in.
2. 15% service-fee tier enrolment (Associated developer accounts page).
3. A build that declares the `com.android.vending.BILLING` permission
   uploaded to any track. The `play` flavor's Billing Library dependency
   merges the permission in; until such a build exists, the One-time
   products and Subscriptions pages stay locked and the API refuses to
   create products.
4. License testers added under Settings, License testing, so purchases can
   be exercised with test cards at no cost.

## Creating the products

Either run the `Play Products Sync` workflow (`play-products.yml`) or the
script it wraps:

```bash
PLAY_KEY_FILE=path/to/service-account.json python scripts/play_products.py --dry-run
PLAY_KEY_FILE=path/to/service-account.json python scripts/play_products.py
```

The workflow defaults to a dry run, which prints every payload and writes
nothing; rerun it with `dry_run` off to apply. The script is idempotent:
tips are inserted or replaced, the subscription is created or patched, base
plans still in draft are activated, and base plans Play already holds keep
their prices, since changing a live subscription price is a migration and
stays a deliberate Play Console action.

Regional subscription prices come from Play's own converter
(`pricing:convertRegionPrices`) applied to the CAD base price, so they
match what the console would have generated. The script pins Play's
regions version to `2022/02`; pass `--regions-version` if Play announces a
newer one.

The same catalog can be entered by hand in Play Console. If you do, keep
the ids exactly as listed or the app will show no tiers.

## Testing

License testers see a test-purchase notice in Google's purchase sheet and
are never charged. Useful checks:

- Buy a tip with the always-approves test card: a success banner appears
  and the tip is consumed, so it can be bought again.
- Buy a tip with the slow test card: a pending banner appears; the thanks
  arrives when Play completes the charge, or on the next visit to the
  screen if the app was closed in between.
- Subscribe, then pick a different amount: Play shows a plan-change sheet.
  Test subscriptions renew every five minutes, so cancellation and
  re-subscription can be exercised quickly.
- Kill the app mid-purchase, reopen the screen: the purchase is settled on
  entry. Play refunds anything left unacknowledged for three days, which is
  why the screen reconciles on every visit.

Instrumented tests do not cover billing: the CI emulator has no Play Store,
and the connected suite runs the github flavor. The Play flavor's unit
tests (`app/src/testPlay`) cover the catalog, reconciliation, purchase
events, and plan changes against a fake gateway.

## Policy notes

- The Play build must never show or mention an external payment method.
  Everything with a URL to a payment rail lives in `src/github`.
- The subscription card states the price, the monthly renewal, and where to
  cancel, and links to Play's manage-subscriptions page, as the
  Subscriptions policy requires.
- Purchases grant nothing; the app must never gate a feature behind one.
- Payment details never reach the app, so the Data Safety form declares no
  financial data (see `DATA_SAFETY.md`).
