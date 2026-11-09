# Design decisions

Short records of the main choices: what was chosen, what was rejected, and why.

## 1. Simulate flows by component instead of fitting a time series to the balance

**Chosen:** split history into components (document payments, recurring items, VAT, irregular
flows, owner financing), model each with what is known about it, and simulate 400 paths.

**Rejected:** ARIMA/Prophet-style models on the daily balance or daily net flow.

**Why:** the balance is driven by known future events: an invoice of ₪58,000 due on the 30th, VAT
on the 15th, salaries on the 9th. A time series model sees only their past shadows and smears them
out. Component models use the invoice itself, and they also make the forecast explainable. Each
alert can name the flows that cause it, which a statistical model of the balance cannot. The cost is
more code and more assumptions. The backtest against naive baselines checks whether those
assumptions pay off.

## 2. Kaplan–Meier for payment delays, shrunk toward the pooled curve

**Chosen:** each customer's delay distribution (paid date minus due date) is estimated with
Kaplan–Meier. Open invoices are right-censored observations ("more than N days so far"). Draws come
from the customer's own curve with probability n / (n + 3), otherwise from all customers pooled.
Draws are conditioned on what is known: a 40-day-overdue invoice is paid after day 40, or not at all.

**Rejected:** the empirical distribution of paid invoices only (the first version). Also rejected:
a parametric (log-normal) delay model.

**Why:** with the first version, invoice receipts over 90 days were over-forecast by about ₪33–37k
for agencies and contractors on the dev split. It learned only
from invoices that had been paid, which drops the slowest payers by construction (survivorship
bias). Kaplan–Meier is the standard fix for censored durations and needs no shape assumption. Mass
beyond the longest payment ever seen means "not expected to be paid", which also handles customers
who default. Shrinkage keeps a new customer from being judged on one invoice.

## 3. Common random numbers for scenarios

**Chosen:** every item (each invoice, each recurring pattern, the irregular-flow bootstrap) draws
from its own random stream, seeded from (seed, path, item key).

**Rejected:** one random generator per path.

**Why:** with one shared stream, adding a "new hire" shifts every later draw, so the scenario and the
baseline differ by noise as well as by the hire. With per-item streams, the paths are identical
except for what the scenario changes. A one-off ₪25,000 payment shows as exactly −₪25,000 (there is
an end-to-end test for this). The same property makes forecasts reproducible.

## 4. Calibrate the band by inflating path dispersion, fit on dev

**Chosen:** after simulation, move every path away from the per-day median path by a factor (1.6).
The factor was chosen on the dev split for 80% coverage of the 10–90% band.

**Rejected:** (a) no calibration: coverage was 57–71% on dev. (b) Widening only the displayed
percentiles.

**Why:** the simulation samples the randomness it knows about (delays, dates, weekly spending), but
not model error: a retainer ending, an employee leaving, a new large client. Gamma-distributed
arrival rates and a two-level bootstrap added parameter uncertainty but moved coverage only slightly.
Inflating at the path level keeps the band, the breach probabilities and the alerts consistent with
each other. Widening only the displayed band would show one thing and alert on another. The
honest caveat: on holdout the 90-day coverage is 71%, so one factor for all horizons does not
transfer perfectly.

## 5. Alerts on path-level breach probability, threshold chosen by F2 on dev

**Chosen:** an alert fires when at least 50% of paths dip below the threshold at any point in the
horizon. The alert date is when that share is reached. The "cash needed" is the amount that, added
today, would bring the share under 50%, computed exactly from the paths' minima.

**Rejected:** alerting when the daily median crosses the threshold (it misses risky paths that
recover). Also rejected: per-day probabilities, which do not answer "will I run short at all?".

**Why:** a business owner's question is "what's the chance I run short before the end of the
quarter, and how much do I need?". The threshold was swept on dev (0.3–0.6) and 0.5 had the best F2
score (recall weighted over precision): missing a shortfall costs more than a false alarm.

## 6. VAT from documents plus a learned residual

**Chosen:** the liability for each bimonthly period is the VAT on invoices issued in it minus the
VAT on bills, including simulated future documents. A residual is added: the median, over the last
three periods, of what was actually paid minus what documents explain.

**Rejected:** treating VAT as one more recurring payment.

**Why:** VAT is large, lumpy and known in advance from documents already issued. A recurring
pattern would average it. The residual covers VAT that never appears on an imported document:
output VAT in card sales, input VAT on card purchases. Refunds are not modelled (a listed
limitation).

## 7. Owner loans and dividends are not projected; the evaluation reports both views

**Chosen:** flows matching a configurable owner-financing pattern are excluded from the projection.
The backtest reports error against the real balance (headline) and against the balance without owner
financing.

**Why:** owner loans in the generator react to the cash position, as they do in real life: the owner
injects cash when the overdraft is deep. Early on, dividends taken monthly were detected as a
₪178k "recurring" payment and projected forward. A forecast should show what happens if nothing is
done; the owner's reaction is the decision the forecast is meant to inform. Reporting only the
adjusted metric would flatter the model, so both are shown.

## 8. The synthetic generator is richer than the model; dev and holdout are separate seeds

**Chosen:** the generator has hidden customer types, defaults, staff turnover, raises, seasonal
utilities, VAT refunds, tax advances and owner reactions, none of which the model knows directly.
Dev seeds 1–24 are used for every decision; holdout seeds 1001–1060 were run once, after freezing
the settings.

**Why:** a generator that mirrors the model's assumptions tests nothing. Two generator problems were
found and fixed on dev before the holdout run. Revenue was calibrated against costs that left out
card spending and raises, so most companies were deep in losses. Dividends were monthly and had
their description and counterparty swapped. Both were fixes to the simulated world, not tuning of
the model, and they happened before holdout was ever run.

## 9. Money is `BigDecimal` everywhere, and a string in JSON

**Chosen:** `BigDecimal` with scale 2 and banker's rounding in Java, `NUMERIC(16,2)` in
PostgreSQL, decimal strings in the API (`"1234.50"`). Doubles appear only for probabilities and
relative factors, converted with `BigDecimal.valueOf` before touching an amount.

**Rejected:** `long` agorot (exact, but easy to misuse at the boundaries). Also rejected: JSON
numbers, which most clients parse into binary floats.

**Why:** the brief requires it, and a forecast that is off by an agora on a bank balance looks
broken even when it is not. The Monte Carlo runs on `BigDecimal` too. A full forecast request (load,
fit, 400 paths × 90 days, JSON) takes about 100 ms on a laptop, so there was no reason to trade
exactness for speed.

## 10. CSV import: strict, all-or-nothing by default, idempotent

**Chosen:** strict UTF-8 decoding with a Windows-1255 fallback, binary files refused, bounded rows
and field lengths. Amounts with more than two decimals are rejected, not rounded. Any invalid row
rejects the whole file unless `skipInvalid=true`. Each bank row gets a SHA-256 fingerprint of its
content plus its occurrence number within the file. Documents are upserted by number.

**Why:** a silently half-imported bank statement produces a confident, wrong forecast. Israeli bank
exports are often Windows-1255, day-first, with debit and credit columns and trailing minus signs.
Re-importing an overlapping export is the normal workflow, so it must add nothing. The occurrence
number keeps two genuinely identical rows (two ₪50 coffees on the same day) instead of merging them.

## 11. JdbcClient over JPA; one module with ArchUnit over a multi-module build

**Chosen:** Spring's `JdbcClient` with explicit SQL (`ON CONFLICT` upserts). A single Maven module,
with an ArchUnit test that keeps `engine/` free of Spring, Jackson and the database.

**Why:** the forecast loads a company's whole history once and works in memory. There is no object
graph to map, and JPA would add lazy-loading and dirty-checking for nothing. A multi-module build
would enforce the engine boundary at compile time, but with more build plumbing. ArchUnit enforces
the same rule as a test.
