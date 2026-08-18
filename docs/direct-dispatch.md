# Dispatch diretto degli eventi geofence (Android, dal 1.3.4)

Perché esiste, come funziona e cosa NON rompere quando si tocca questo codice.
Per il piano di validazione e la checklist di merge lato app vedi
`geofencing_test_2/docs/geofence-delivery-fix.md`.

## Il problema, misurato

Il percorso storico era:

```
Play Services ── broadcast ──> GeofencingBroadcastReceiver.onReceive
                                  └─> GeofencingService.enqueueWork   (JobIntentService)
                                        └─ [coda JobScheduler] ─> onHandleWork ─> isolate Dart
```

Su API 26+ `JobIntentService` è costruito sopra JobScheduler: il lavoro
accodato è un **job regolare**, che Doze e gli App Standby Bucket differiscono
proprio quando il device è fermo a schermo spento — cioè a ogni rientro a
casa. Misurato sul Galaxy Z Fold6 (Android 16), con i 4 timbri introdotti nel
1.3.3:

| Tratta | Uscita (device attivo) | Rientro (device fermo) |
|---|---|---|
| fix → receiver (`gms_ms`) | 17-113 ms | 17-113 ms |
| loader (`loader_ms`) | ~1-12 ms | ~1-12 ms |
| **coda JobScheduler (`queue_ms`)** | **16 ms** | **22 min, fino a 95 min** |
| avvio isolate (`engine_ms`) | ~3-23 ms | ~3-23 ms |

Play Services è scagionato. L'esenzione dall'ottimizzazione batteria non aiuta
(copre rete e wakelock, NON i job), un foreground service attivo nemmeno
(misurato il 12/08: campionatore vivo a 15 s durante 290 s di attesa).

## Il fix

Il receiver consegna l'evento a Dart **direttamente, nello stesso giro del
broadcast**, sotto `goAsync()`:

```
onReceive
  ├─ timbro 1 (RECEIVER_ENTRY_MS_KEY come extra sull'Intent)
  ├─ init FlutterLoader (sincrona)
  ├─ timbro 2 (PRE_ENQUEUE_MS_KEY)
  ├─ pendingResult = goAsync()          ← tiene vivi broadcast + wakelock OS
  ├─ watchdog: finishOnce ritardato di 8,5 s (il sistema chiuderebbe a ~10 s)
  ├─ GeofencingService.dispatchDirect(context, intent, finishOnce)
  │     ├─ ensureEngineStarted()   → engine + canale, validazione handle PRIMA
  │     ├─ buildEventPayload(path="direct")   → timbro 3 = presa in carico
  │     └─ deliverOrQueue()        → invoke subito, o coda se isolate in avvio
  ├─ se NON preso in carico → extra FALLBACK_REASON_KEY + enqueueWork (fallback)
  └─ promozione a foreground (IsolateHolderService), DOPO la presa in carico
```

### I contratti da non rompere

- **Presa in carico tutto-o-niente.** `dispatchDirect` ritorna `true` se e
  solo se l'evento è stato consegnato o accodato; solo allora `onDelivered`
  verrà invocato (al più una volta, sul main thread). Ritorna `false` senza
  aver toccato nulla → il fallback su `enqueueWork` non può mai produrre una
  doppia consegna. Qualsiasi modifica che introduca uno stato intermedio
  ("preso in carico a metà") rompe questa garanzia.
- **`finishOnce` è idempotente** (CAS su AtomicBoolean): viene invocato dalla
  consegna, dal watchdog e dal ramo di fallback, in qualsiasi ordine.
- **Il watchdog copre l'HANDOFF, non il callback.** `goAsync` si chiude quando
  l'evento è in mano a Dart; il lavoro lungo del callback (HTTP Blink, p50
  2,3 s / p90 5,6 s) è protetto dalla **promozione a foreground**, che in Doze
  è anche l'unica cosa che dà rete. La promozione va DOPO la presa in carico:
  spostarla in cima fa scadere la finestra dei 5 s di `startForeground` dietro
  l'init sincrona del loader (crash `ForegroundServiceDidNotStartInTimeException`
  osservato: evento 22:43:28, morte 22:43:33).
- **Stato condiviso nel companion.** Engine, canale, coda eventi e handler del
  canale sono statici: percorso diretto e fallback usano lo STESSO stato. La
  coda ha un tetto (32); gli eventi accodati durante l'avvio dell'isolate
  vengono scaricati su `GeofencingService.initialized`.
- **`ensureEngineStarted` valida l'handle PRIMA di creare l'engine.** Nel
  vecchio codice un handle stale lasciava un engine senza Dart e il check
  `engine == null` risultava per sempre falso: nessun evento veniva più
  consegnato. Non reintrodurre la creazione anticipata.
- **Main thread only** per `dispatchDirect`/`ensureEngineStarted` (creazione
  engine e canale). `onHandleWork` gira su worker thread e per questo usa solo
  `buildEventPayload` + `deliverOrQueue`.

### Perché il fallback esiste ancora

È un'assicurazione da rollout, non architettura permanente: protegge da
difetti ignoti del percorso nuovo su OEM non testati (il vecchio percorso è
lento ma collaudato nel "consegnare prima o poi") e smaltisce i job già
accodati da versioni precedenti al momento dell'update. Criterio di rimozione:
**0% di fallback sostenuto in telemetria per qualche settimana** (vedi sotto).

## Osservabilità

Payload del callback (8 elementi):

| # | Contenuto |
|---|---|
| 0-3 | handle, ids, [lat, lon], transition (invariati da sempre) |
| 4 | epoch ms del fix innescante (0 = assente) |
| 5 | `isInitialTrigger` (sintetico da (ri)registrazione) |
| 6 | `[receiver_entry, pre_enqueue, presa_in_carico]` epoch ms (0 = assente) |
| 7 | percorso di consegna (stringa, assente su iOS/payload vecchi) |

Lato Dart, static su `GeofencingManager` da leggere **in modo sincrono in cima
al callback** (vengono sovrascritti dall'evento successivo):
`lastEventWasInitialTrigger`, `lastEventDeliveryTimings`,
`lastEventDeliveryPath`.

Valori di `lastEventDeliveryPath`:

- `"direct"` — percorso primario. Su Android 1.3.4+ deve essere SEMPRE questo.
- `"job:rejected"` — il dispatch diretto non ha preso in carico (handle
  mancante/stale o evento malformato) → il fallback quasi certamente fallirà
  allo stesso modo, ma l'evento resta in coda finché un engine parte.
- `"job:exception"` — il percorso diretto ha lanciato: **bug da investigare**.
- `"job"` — lavoro accodato da una versione precedente del plugin (atteso solo
  a cavallo di un update dell'app).
- `null` — iOS, o plugin nativo pre-1.3.4.

## Storia e riferimenti

- Commit: `846a2d9` (dispatch diretto), `f7f4a7c` (delivery path), `6c02661`
  (ogni `startForegroundService` onorato), `5883330` (4 timbri + finestra
  initial 30 min), `2cbadd9` (stamp prima di addGeofences), `211c2e1`
  (JOB_ID stabile).
- Misure e storia del ragionamento: memoria workspace
  `geocam-geofence-delivery-not-sampling` e
  `geofencing_test_2/docs/probe-fold6.md`.
