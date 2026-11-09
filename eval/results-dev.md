Split `dev`: 24 companies, 792 forecasts, 400 paths, alert probability 0.50, dispersion 1.60, commit `34a8192`.

| Horizon | runway MAE (ILS) | Flat MAE | Last-30-days MAE | runway median AE | runway bias | runway MAE / monthly outflow | 10-90% band coverage |
|---|---:|---:|---:|---:|---:|---:|---:|
| 7 days | 30,152 | 63,311 | 75,620 | 15,258 | -3,208 | 10.9% | 90% |
| 30 days | 56,687 | 88,481 | 137,141 | 29,567 | 8,393 | 20.6% | 83% |
| 90 days | 114,526 | 143,535 | 336,874 | 84,526 | 22,081 | 41.2% | 77% |

Excluding owner loans and dividends made after the cutoff (owner reactions no forecast can know):

| Horizon | runway MAE (ILS) | Flat MAE | Last-30-days MAE | runway bias | 10-90% band coverage |
|---|---:|---:|---:|---:|---:|
| 7 days | 25,047 | 59,448 | 69,922 | -5,038 | 92% |
| 30 days | 36,602 | 75,468 | 120,383 | -245 | 91% |
| 90 days | 77,443 | 139,378 | 309,077 | -3,331 | 89% |

Shortfalls below minimum: 43 events (distinct company and first day below), seen at 139 of 744 eligible cutoffs.

| Method | Events warned at the first chance | Events warned at some cutoff | Median lead of first warning (days) | Cutoff-level recall | False-alarm share of alerts |
|---|---:|---:|---:|---:|---:|
| runway | 88% (38) | 98% (42) | 34 | 87% (121 of 139) | 35% (77 of 221) |
| lastThirtyDays | 51% (22) | 79% (34) | 34 | 47% (65 of 139) | 60% (119 of 200) |
| cushion | 88% (38) | 98% (42) | 30 | 78% (109 of 139) | 66% (260 of 392) |

Shortfalls below zero: 23 events (distinct company and first day below), seen at 85 of 778 eligible cutoffs.

| Method | Events warned at the first chance | Events warned at some cutoff | Median lead of first warning (days) | Cutoff-level recall | False-alarm share of alerts |
|---|---:|---:|---:|---:|---:|
| runway | 83% (19) | 96% (22) | 78 | 88% (75 of 85) | 39% (54 of 138) |
| lastThirtyDays | 48% (11) | 87% (20) | 52 | 52% (44 of 85) | 70% (123 of 175) |
| cushion | 78% (18) | 96% (22) | 76 | 80% (68 of 85) | 77% (251 of 328) |

95% intervals (company bootstrap, 1000 rounds):

- mae30ReductionVsFlat: 31% to 42%
- mae90ReductionVsFlat: 10% to 29%
- coverage90: 72% to 83%
- minimumRecall: 76% to 96%
- minimumFalseAlarmShare: 23% to 46%
- minimumEventsWarnedFirst: 76% to 97%

| Archetype | runway 30-day MAE | Flat | Last-30-days |
|---|---:|---:|---:|
| CONSULTANCY | 55,004 | 84,020 | 127,120 |
| AGENCY | 61,563 | 107,552 | 170,870 |
| RETAIL | 30,314 | 49,823 | 81,345 |
| CONTRACTOR | 79,865 | 112,529 | 169,230 |

Runtime: 18.7 s
