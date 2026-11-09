Split `holdout`: 60 companies, 1980 forecasts, 400 paths, alert probability 0.50, dispersion 1.60, commit `34a8192`.

| Horizon | runway MAE (ILS) | Flat MAE | Last-30-days MAE | runway median AE | runway bias | runway MAE / monthly outflow | 10-90% band coverage |
|---|---:|---:|---:|---:|---:|---:|---:|
| 7 days | 32,031 | 62,100 | 73,554 | 18,309 | -1,131 | 11.8% | 88% |
| 30 days | 62,105 | 94,059 | 147,774 | 36,292 | 6,694 | 22.2% | 82% |
| 90 days | 120,245 | 144,790 | 348,661 | 86,900 | 25,497 | 42.0% | 72% |

Excluding owner loans and dividends made after the cutoff (owner reactions no forecast can know):

| Horizon | runway MAE (ILS) | Flat MAE | Last-30-days MAE | runway bias | 10-90% band coverage |
|---|---:|---:|---:|---:|---:|
| 7 days | 26,406 | 59,482 | 68,177 | -3,426 | 90% |
| 30 days | 40,581 | 81,882 | 131,044 | 113 | 91% |
| 90 days | 73,235 | 145,714 | 324,322 | 5,395 | 90% |

Shortfalls below minimum: 113 events (distinct company and first day below), seen at 402 of 1834 eligible cutoffs.

| Method | Events warned at the first chance | Events warned at some cutoff | Median lead of first warning (days) | Cutoff-level recall | False-alarm share of alerts |
|---|---:|---:|---:|---:|---:|
| runway | 84% (95) | 96% (109) | 42 | 85% (341 of 402) | 23% (124 of 531) |
| lastThirtyDays | 55% (62) | 88% (100) | 35 | 51% (206 of 402) | 51% (272 of 533) |
| cushion | 81% (91) | 99% (112) | 48 | 82% (328 of 402) | 59% (562 of 956) |

Shortfalls below zero: 76 events (distinct company and first day below), seen at 302 of 1930 eligible cutoffs.

| Method | Events warned at the first chance | Events warned at some cutoff | Median lead of first warning (days) | Cutoff-level recall | False-alarm share of alerts |
|---|---:|---:|---:|---:|---:|
| runway | 75% (57) | 100% (76) | 55 | 86% (259 of 302) | 26% (110 of 418) |
| lastThirtyDays | 43% (33) | 93% (71) | 35 | 53% (160 of 302) | 58% (285 of 490) |
| cushion | 70% (53) | 97% (74) | 50 | 82% (247 of 302) | 62% (491 of 787) |

95% intervals (company bootstrap, 1000 rounds):

- mae30ReductionVsFlat: 30% to 38%
- mae90ReductionVsFlat: 9% to 24%
- coverage90: 66% to 77%
- minimumRecall: 79% to 90%
- minimumFalseAlarmShare: 15% to 33%
- minimumEventsWarnedFirst: 78% to 89%

| Archetype | runway 30-day MAE | Flat | Last-30-days |
|---|---:|---:|---:|
| CONSULTANCY | 47,828 | 67,805 | 109,816 |
| AGENCY | 79,845 | 130,872 | 205,512 |
| RETAIL | 50,052 | 68,905 | 109,725 |
| CONTRACTOR | 70,695 | 108,655 | 166,044 |

Runtime: 33.4 s
