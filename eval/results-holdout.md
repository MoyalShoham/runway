Split `holdout`: 60 companies, 1980 forecasts, 400 paths, alert probability 0.50, dispersion 1.60, commit `8d4267a`.

| Horizon | runway MAE (ILS) | Flat MAE | Last-30-days MAE | runway median AE | runway bias | runway MAE / monthly outflow | 10-90% band coverage |
|---|---:|---:|---:|---:|---:|---:|---:|
| 7 days | 34,346 | 64,494 | 77,088 | 17,941 | -1,878 | 12.4% | 87% |
| 30 days | 66,929 | 96,747 | 154,391 | 36,079 | 3,950 | 23.7% | 81% |
| 90 days | 129,075 | 145,476 | 366,044 | 85,237 | 11,752 | 44.5% | 71% |

Excluding owner loans and dividends made after the cutoff (owner reactions no forecast can know):

| Horizon | runway MAE (ILS) | Flat MAE | Last-30-days MAE | runway bias | 10-90% band coverage |
|---|---:|---:|---:|---:|---:|
| 7 days | 27,794 | 61,426 | 71,257 | -3,586 | 90% |
| 30 days | 42,002 | 83,196 | 137,007 | -3,673 | 91% |
| 90 days | 75,873 | 147,290 | 336,789 | -13,588 | 90% |

| Threshold | Real shortfalls | runway warned | runway false-alarm share | Last-30-days warned | Last-30-days false-alarm share | Median lead (days) |
|---|---:|---:|---:|---:|---:|---:|
| minimum | 296 of 1882 | 85% (251) | 31% (142 of 453) | 50% (148) | 60% (294 of 491) | 35 |
| zero | 213 of 1936 | 88% (187) | 31% (96 of 313) | 53% (112) | 67% (277 of 414) | 41 |

| Archetype | runway 30-day MAE | Flat | Last-30-days |
|---|---:|---:|---:|
| CONSULTANCY | 53,420 | 76,698 | 123,811 |
| AGENCY | 88,357 | 125,510 | 215,461 |
| RETAIL | 50,428 | 69,705 | 111,042 |
| CONTRACTOR | 75,510 | 115,076 | 167,251 |

Runtime: 32.1 s
