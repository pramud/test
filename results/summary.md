# Summary (generated from results.csv)

`complete` = every byte delivered and the pattern verified. `truncated` = fewer bytes than expected, no corruption. Close codes / exceptions / RST senders are shown as value×count.

## E1

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| direct-hbOff | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 23.0 |

## E2

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| direct-hbOn | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 23.0 |

## E3

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| nginx-hbOff | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 17 | -×3<br>nginx(client-leg)×17 |  | 23.1 |

## E4

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| nginx-hbOn | 20 | 9 | 11 | 0 | 996352..1048580 | 1000×9<br>1006×11 | -×9<br>ClosedChannelException×11 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 |  | 22.5 |

## E5

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| nginx-linger-hbOn | 20 | 10 | 10 | 0 | 1006592..1048580 | 1000×10<br>1006×10 | -×10<br>ClosedChannelException×10 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 |  | 22.7 |

## E6

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| direct-P1 | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 | 1/20 | 21.9 |
| direct-P1early | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 | 20/20 | 22.0 |
| direct-P2 | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 | 20/20 | 21.8 |
| direct-P3 | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 | 20/20 | 22.1 |
| nginx-P1 | 20 | 1 | 19 | 0 | 180736..1048580 | 1000×1<br>1006×19 | -×1<br>ClosedChannelException×19 | 1006×20 | ClosedChannelException×20 | 19 | -×1<br>nginx(client-leg)×19 | 1/20 | 5.2 |
| nginx-P1early | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 17 | -×3<br>nginx(client-leg)×17 | 20/20 | 21.8 |
| nginx-P2 | 20 | 0 | 20 | 0 | 106496..152064 | 1006×20 | ClosedChannelException×20 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 | 20/20 | 3.5 |
| nginx-P3 | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 17 | -×3<br>nginx(client-leg)×17 | 20/20 | 22.0 |

## E7

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| E2-clientCbB | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 22.4 |
| E2-srvDefaultBufs | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 22.5 |
| E2-srvOut64k | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 22.6 |
| E4-clientCbB | 20 | 14 | 6 | 0 | 1008640..1048580 | 1000×14<br>1006×6 | -×14<br>ClosedChannelException×6 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 |  | 22.4 |
| E4-srvDefaultBufs | 20 | 14 | 6 | 0 | 1006592..1048580 | 1000×14<br>1006×6 | -×14<br>ClosedChannelException×6 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 |  | 22.3 |
| E4-srvOut64k | 20 | 15 | 5 | 0 | 1009664..1048580 | 1000×15<br>1006×5 | -×15<br>ClosedChannelException×5 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 |  | 22.2 |
| E6P2-direct-clientCbB | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 | 20/20 | 21.8 |
| E6P2-direct-srvDefaultBufs | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 | 20/20 | 21.8 |
| E6P2-direct-srvOut64k | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 | 20/20 | 22.0 |
| E6P2-nginx-clientCbB | 20 | 0 | 20 | 0 | 110080..230912 | 1006×20 | ClosedChannelException×20 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 | 20/20 | 3.8 |
| E6P2-nginx-srvDefaultBufs | 20 | 0 | 20 | 0 | 112128..164352 | 1006×20 | ClosedChannelException×20 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 | 20/20 | 3.9 |
| E6P2-nginx-srvOut64k | 20 | 0 | 20 | 0 | 109056..208896 | 1006×20 | ClosedChannelException×20 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 | 20/20 | 4.1 |

## E8

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| upload-in15MiB-srvCbDone | 20 | 0 | 0 | 20 | 5242880..5242880 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 2.6 |
| upload-in15MiB-srvCbNever | 20 | 0 | 0 | 20 | 5242880..5242880 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 2.5 |
| upload-in64KiB-srvCbDone | 20 | 20 | 0 | 0 | 5242880..5242880 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 2.4 |
| upload-in64KiB-srvCbNever | 20 | 20 | 0 | 0 | 5242880..5242880 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 2.4 |
| upload-in65544-srvCbDone | 20 | 0 | 0 | 20 | 5242880..5242880 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 2.5 |
| upload-in65544-srvCbNever | 20 | 0 | 0 | 20 | 5242880..5242880 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 2.5 |
| upload-inDefault-srvCbDone | 20 | 20 | 0 | 0 | 5242880..5242880 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 2.6 |
| upload-inDefault-srvCbNever | 20 | 20 | 0 | 0 | 5242880..5242880 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 2.6 |

## E9

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| direct-stall45s | 20 | 20 | 0 | 0 | 262148..262148 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 52.0 |

## E10

| variant | runs | complete | truncated | corrupt | delivered min..max | client close | client exception | server close | server error | RST runs | RST sender | ping phase valid | median s |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| direct-hbOn-4MiB | 20 | 20 | 0 | 0 | 4194308..4194308 | 1000×20 | -×20 | 1000×20 | -×20 | 0 | -×20 |  | 84.9 |
| nginx-P2-srvCbDone | 20 | 0 | 20 | 0 | 90624..312320 | 1006×20 | ClosedChannelException×20 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 | 20/20 | 3.1 |
| nginx-P2-srvFragmented | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 | 20/20 | 22.4 |
| nginx-hbOn-4MiB | 20 | 0 | 20 | 0 | 1052672..2124288 | 1006×20 | ClosedChannelException×20 | 1006×20 | ClosedChannelException×20 | 20 | nginx(client-leg)×20 |  | 41.8 |
| nginx-hbSmart | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 16 | -×4<br>nginx(client-leg)×16 |  | 21.8 |
| nginx-hbSmart-4MiB | 20 | 20 | 0 | 0 | 4194308..4194308 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 18 | -×2<br>nginx(client-leg)×18 |  | 84.9 |
| nginx-linger-P2 | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 19 | -×1<br>nginx(client-leg)×19 | 20/20 | 21.8 |
| nginx-lingerLong-P2 | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 0 | -×20 | 20/20 | 21.8 |
| nginx-lingerLong-hbOn | 20 | 20 | 0 | 0 | 1048580..1048580 | 1000×20 | -×20 | 1006×20 | ClosedChannelException×20 | 0 | -×20 |  | 21.8 |
