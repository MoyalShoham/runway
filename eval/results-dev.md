Split `dev`: 24 companies, 792 forecasts, 400 paths, alert probability 0.50, dispersion 1.60, commit `8d4267a`.

| Horizon | runway MAE (ILS) | Flat MAE | Last-30-days MAE | runway median AE | runway bias | runway MAE / monthly outflow | 10-90% band coverage |
|---|---:|---:|---:|---:|---:|---:|---:|
| 7 days | 31,729 | 62,884 | 73,507 | 18,280 | 598 | 11.4% | 88% |
| 30 days | 58,671 | 93,827 | 142,624 | 31,119 | 8,014 | 20.6% | 81% |
| 90 days | 107,196 | 137,280 | 325,299 | 79,963 | 23,393 | 37.9% | 78% |

Excluding owner loans and dividends made after the cutoff (owner reactions no forecast can know):

| Horizon | runway MAE (ILS) | Flat MAE | Last-30-days MAE | runway bias | 10-90% band coverage |
|---|---:|---:|---:|---:|---:|
| 7 days | 26,425 | 59,604 | 68,782 | -2,518 | 91% |
| 30 days | 38,311 | 80,720 | 123,279 | -5,593 | 90% |
| 90 days | 76,371 | 137,607 | 301,858 | -18,748 | 88% |

| Threshold | Real shortfalls | runway warned | runway false-alarm share | Last-30-days warned | Last-30-days false-alarm share | Median lead (days) |
|---|---:|---:|---:|---:|---:|---:|
| minimum | 100 of 748 | 95% (95) | 26% (42 of 163) | 52% (52) | 63% (120 of 192) | 34 |
| zero | 73 of 784 | 92% (67) | 29% (34 of 116) | 55% (40) | 68% (115 of 168) | 43 |

| Archetype | runway 30-day MAE | Flat | Last-30-days |
|---|---:|---:|---:|
| CONSULTANCY | 43,198 | 73,132 | 116,042 |
| AGENCY | 79,409 | 135,843 | 209,038 |
| RETAIL | 31,451 | 51,047 | 83,544 |
| CONTRACTOR | 80,624 | 115,285 | 161,875 |

Runtime: 19.5 s
