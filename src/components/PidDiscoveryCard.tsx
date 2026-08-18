import React, { useEffect, useState } from 'react';
import { AlertTriangle, CheckCircle2, Circle, Copy, Check } from 'lucide-react';
import { telemetryManager } from '../services/telemetryManager';
import type { PidProbeResult } from '../services/bluetooth/obdlinkBluetooth';

/**
 * Lists every PID the car reports it supports, with what it answered when asked.
 *
 * The point of this screen is that it measures rather than assumes. Which sensors an ECU
 * exposes varies by manufacturer, market and trim, so a list derived from "2013 Civic LX"
 * is a guess - and a guess here produces a gauge that displays a confident wrong number.
 * Everything below comes from the car.
 */

/**
 * The last scan, kept so it survives leaving the tab.
 *
 * A scan needs the engine running, takes a few seconds, and is the only thing in the app
 * that says what this specific ECU exposes - and it used to live in component state alone,
 * so switching tabs destroyed it. That made the app's most considered measurement its least
 * durable one. Keeping it also means a scan can be read back later, away from the car, which
 * is when anyone actually wants to compare two of them.
 */
const STORAGE_KEY = 'civic.pidDiscovery.lastScan.v1';

interface StoredScan {
  at: number;
  results: PidProbeResult[];
}

function loadStoredScan(): StoredScan | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredScan;
    // A stored shape from an older build is discarded rather than half-rendered.
    if (!parsed || typeof parsed.at !== 'number' || !Array.isArray(parsed.results)) return null;
    return parsed;
  } catch {
    return null;
  }
}

function storeScan(scan: StoredScan): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(scan));
  } catch {
    // A full or disabled store is not worth interrupting the scan over.
  }
}

/** Plain text, because the point is to paste it somewhere - a note, an email, a mechanic. */
function formatScanReport(scan: StoredScan): string {
  const sensors = scan.results.filter((r) => !r.isBankMarker);
  const lines = [
    '2013 Honda Civic LX 5MT — OBD-II sensor discovery',
    'Scanned ' + new Date(scan.at).toLocaleString(),
    sensors.length + ' PIDs supported',
    '',
  ];
  for (const r of sensors) {
    const mark = r.inUse ? '*' : ' ';
    const reading = r.value ?? (r.payload ? 'hex ' + r.payload : 'no reply');
    lines.push(mark + ' ' + r.cmd.slice(2).padEnd(4) + r.name.padEnd(36) + reading);
  }
  lines.push('');
  lines.push('* = already drives a gauge');
  return lines.join('\n');
}

/** A restored scan must not read as a live one, so every rendering of it carries its time. */
function formatScanTime(at: number): string {
  const scanned = new Date(at);
  const time = scanned.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  const isToday = scanned.toDateString() === new Date().toDateString();
  return isToday ? 'Scanned ' + time : 'Scanned ' + scanned.toLocaleDateString() + ', ' + time;
}

export const PidDiscoveryCard: React.FC = () => {
  const [scan, setScan] = useState<StoredScan | null>(null);
  const [isScanning, setIsScanning] = useState(false);
  const [progress, setProgress] = useState({ done: 0, total: 0 });
  const [error, setError] = useState<string | null>(null);
  const [didCopy, setDidCopy] = useState(false);

  useEffect(() => {
    setScan(loadStoredScan());
  }, []);

  const handleScan = async () => {
    setIsScanning(true);
    setError(null);
    setProgress({ done: 0, total: 0 });
    try {
      const found = await telemetryManager.runPidDiscovery((done, total) =>
        setProgress({ done, total })
      );
      const fresh: StoredScan = { at: Date.now(), results: found };
      setScan(fresh);
      storeScan(fresh);
    } catch (err: any) {
      setError(err?.message || 'Discovery failed.');
    } finally {
      setIsScanning(false);
    }
  };

  const handleCopy = async () => {
    if (!scan) return;
    try {
      await navigator.clipboard.writeText(formatScanReport(scan));
      setDidCopy(true);
      window.setTimeout(() => setDidCopy(false), 2000);
    } catch {
      setError('Could not reach the clipboard.');
    }
  };

  const results = scan?.results ?? null;
  const sensors = results?.filter((r) => !r.isBankMarker) ?? [];

  /*
   * Three separate facts, which the old tiles conflated into two.
   *
   * "Answered" was computed as `value !== null`, which is whether this app has a formula -
   * not whether the car replied. On this Civic six PIDs replied with bytes nobody had
   * written a formula for, so a scan where all 38 answered was reported as 32, on the one
   * screen whose entire purpose is honest measurement. `payload` now carries whether bytes
   * came back, so the two questions are counted separately.
   */
  const answered = sensors.filter((r) => r.payload !== null);
  const undecoded = answered.filter((r) => r.value === null);
  const unused = answered.filter((r) => !r.inUse);

  return (
    <div className="flex flex-col gap-4 pt-4" style={{ borderTop: '1px solid var(--hairline)' }}>
      <div className="flex items-baseline justify-between gap-3">
        <div>
          <h3 style={{ fontSize: 15, fontWeight: 500, letterSpacing: '-0.01em', color: '#eef0f2' }}>
            Sensor discovery
          </h3>
          <p style={{ fontSize: 11.5, color: '#6b727a', marginTop: 3 }}>
            {scan ? formatScanTime(scan.at) : 'What this car actually exposes'}
          </p>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {/* Copies the scan out as plain text. The reading is often wanted somewhere other
              than this screen, hours later and nowhere near the car. */}
          {scan && !isScanning && (
            <button
              onClick={handleCopy}
              className="px-3 py-2 transition-colors inline-flex items-center gap-1.5"
              style={{
                fontSize: 12.5,
                color: didCopy ? '#eef0f2' : '#9aa1a9',
                border: '1px solid var(--hairline)',
                borderRadius: 8,
              }}
              aria-label="Copy scan as text"
            >
              {didCopy ? <Check size={13} /> : <Copy size={13} />}
              {didCopy ? 'Copied' : 'Copy'}
            </button>
          )}
          {/* Was a solid white pill with black text: the brightest element anywhere in the
              app, on a secondary action nested inside a secondary tab. */}
          <button
            onClick={handleScan}
            disabled={isScanning}
            className="px-4 py-2 transition-colors disabled:opacity-50 shrink-0"
            style={{
              fontSize: 12.5,
              color: '#eef0f2',
              border: '1px solid var(--hairline-strong)',
              borderRadius: 8,
            }}
          >
            {isScanning
              ? `${progress.done}/${progress.total || '…'}`
              : results
              ? 'Rescan'
              : 'Scan'}
          </button>
        </div>
      </div>

      {error && (
        <div
          className="flex items-start gap-2"
          style={{ fontSize: 12.5, color: '#d8453b', lineHeight: 1.6 }}
        >
          <AlertTriangle size={14} className="shrink-0 mt-0.5" />
          <span>{error}</span>
        </div>
      )}

      {!results && !error && !isScanning && (
        <p className="text-[11px] leading-relaxed text-[#9aa1a9]">
          Reads the support list from the ECU, then asks for every sensor on it. Takes a few
          seconds and needs the engine running. The gauges keep updating while it runs, just
          more slowly — they share one connection to the adapter.
        </p>
      )}

      {results && (
        <>
          <div className="grid grid-cols-3 gap-1.5">
            {[
              { label: 'Supported', value: sensors.length, tone: 'text-[#eef0f2]' },
              { label: 'Answered', value: answered.length, tone: 'text-[#eef0f2]' },
              { label: 'Unused', value: unused.length, tone: 'text-[#c8952e]' },
            ].map((s) => (
              <div
                key={s.label}
                className="flex flex-col items-center gap-0.5 py-2 rounded-lg bg-[#101215] border border-[rgba(255,255,255,0.08)]"
              >
                <span className={`text-[16px] font-bold tabular-nums ${s.tone}`}>
                  {s.value}
                </span>
                <span className="text-[9px] text-[#6b727a] uppercase tracking-wide">{s.label}</span>
              </div>
            ))}
          </div>

          <div className="flex flex-col gap-1">
            {sensors.map((r) => (
              <div
                key={r.cmd}
                className="flex items-center gap-2 px-2 py-1.5 rounded-lg bg-[#101215] border border-[rgba(255,255,255,0.06)]"
              >
                {r.inUse ? (
                  <CheckCircle2 size={12} className="text-[#eef0f2] shrink-0" />
                ) : (
                  <Circle size={12} className="text-[#464c53] shrink-0" />
                )}
                <span className="font-mono text-[10px] text-[#9aa1a9] shrink-0 w-9">
                  {r.cmd.slice(2)}
                </span>
                <span className="text-[11px] text-[#eef0f2] flex-1 truncate">{r.name}</span>
                <span
                  className={`text-[11px] font-bold tabular-nums shrink-0 ${
                    r.value !== null ? 'text-[#eef0f2]' : 'text-[#6b727a] font-mono text-[10px]'
                  }`}
                >
                  {/* No formula in the catalogue: show the hex rather than invent a number. */}
                  {r.value ?? (r.raw || 'no reply')}
                </span>
              </div>
            ))}
          </div>

          <p className="text-[10px] leading-relaxed text-[#6b727a]">
            <CheckCircle2 size={10} className="inline text-[#eef0f2] mr-1" />
            already drives a gauge ·{' '}
            <Circle size={10} className="inline text-[#464c53] mr-1" />
            available but unused.
            {undecoded.length > 0 &&
              ` ${undecoded.length} answered with bytes this app has no formula for, shown as hex.`}
          </p>
        </>
      )}
    </div>
  );
};
