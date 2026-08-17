import React, { useState } from 'react';
import { Radar, AlertTriangle, CheckCircle2, Circle } from 'lucide-react';
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
export const PidDiscoveryCard: React.FC = () => {
  const [results, setResults] = useState<PidProbeResult[] | null>(null);
  const [isScanning, setIsScanning] = useState(false);
  const [progress, setProgress] = useState({ done: 0, total: 0 });
  const [error, setError] = useState<string | null>(null);

  const handleScan = async () => {
    setIsScanning(true);
    setError(null);
    setProgress({ done: 0, total: 0 });
    try {
      const found = await telemetryManager.runPidDiscovery((done, total) =>
        setProgress({ done, total })
      );
      setResults(found);
    } catch (err: any) {
      setError(err?.message || 'Discovery failed.');
    } finally {
      setIsScanning(false);
    }
  };

  const sensors = results?.filter((r) => !r.isBankMarker) ?? [];
  const answered = sensors.filter((r) => r.value !== null);
  const unused = answered.filter((r) => !r.inUse);

  return (
    <div className="telemetry-card flex flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Radar size={16} className="text-[#00d2ff]" />
          <div className="flex flex-col">
            <span className="text-[13px] font-bold font-['Chakra_Petch'] text-[#f8fafc]">
              Sensor Discovery
            </span>
            <span className="text-[10px] text-[#64748b]">What this car actually exposes</span>
          </div>
        </div>
        <button
          onClick={handleScan}
          disabled={isScanning}
          className="px-3 py-1.5 rounded-lg bg-[#00d2ff] disabled:bg-[#1e2534] disabled:text-[#64748b] text-black text-[11px] font-bold font-['Chakra_Petch'] transition-colors"
        >
          {isScanning ? `${progress.done}/${progress.total || '…'}` : results ? 'Rescan' : 'Scan'}
        </button>
      </div>

      {error && (
        <div className="flex items-start gap-2 rounded-lg border border-[#ff2a40]/40 bg-[#ff2a40]/10 p-2.5 text-[11px] leading-relaxed text-[#ff9aa5]">
          <AlertTriangle size={14} className="text-[#ff2a40] shrink-0 mt-0.5" />
          <span>{error}</span>
        </div>
      )}

      {!results && !error && !isScanning && (
        <p className="text-[11px] leading-relaxed text-[#94a3b8]">
          Reads the support list from the ECU, then asks for every sensor on it. Takes a few
          seconds and needs the engine running. The gauges keep updating while it runs, just
          more slowly — they share one connection to the adapter.
        </p>
      )}

      {results && (
        <>
          <div className="grid grid-cols-3 gap-1.5">
            {[
              { label: 'Supported', value: sensors.length, tone: 'text-[#f8fafc]' },
              { label: 'Answered', value: answered.length, tone: 'text-[#00e676]' },
              { label: 'Unused', value: unused.length, tone: 'text-[#ffaa00]' },
            ].map((s) => (
              <div
                key={s.label}
                className="flex flex-col items-center gap-0.5 py-2 rounded-lg bg-[#08090d] border border-[rgba(255,255,255,0.08)]"
              >
                <span className={`text-[16px] font-bold font-['Chakra_Petch'] tabular-nums ${s.tone}`}>
                  {s.value}
                </span>
                <span className="text-[9px] text-[#64748b] uppercase tracking-wide">{s.label}</span>
              </div>
            ))}
          </div>

          <div className="flex flex-col gap-1">
            {sensors.map((r) => (
              <div
                key={r.cmd}
                className="flex items-center gap-2 px-2 py-1.5 rounded-lg bg-[#08090d] border border-[rgba(255,255,255,0.06)]"
              >
                {r.inUse ? (
                  <CheckCircle2 size={12} className="text-[#00e676] shrink-0" />
                ) : (
                  <Circle size={12} className="text-[#475569] shrink-0" />
                )}
                <span className="font-mono text-[10px] text-[#00d2ff] shrink-0 w-9">
                  {r.cmd.slice(2)}
                </span>
                <span className="text-[11px] text-[#f8fafc] flex-1 truncate">{r.name}</span>
                <span
                  className={`text-[11px] font-bold tabular-nums shrink-0 ${
                    r.value !== null ? 'text-[#f8fafc]' : 'text-[#64748b] font-mono text-[10px]'
                  }`}
                >
                  {/* No formula in the catalogue: show the hex rather than invent a number. */}
                  {r.value ?? (r.raw || 'no reply')}
                </span>
              </div>
            ))}
          </div>

          <p className="text-[10px] leading-relaxed text-[#64748b]">
            <CheckCircle2 size={10} className="inline text-[#00e676] mr-1" />
            already drives a gauge ·{' '}
            <Circle size={10} className="inline text-[#475569] mr-1" />
            available but unused. A row showing hex instead of a value means the car answered
            but this app has no formula for it yet.
          </p>
        </>
      )}
    </div>
  );
};
