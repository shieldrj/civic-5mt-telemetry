import React, { useState } from 'react';
import { AlertTriangle, AlertOctagon, Clock, HelpCircle, ShieldCheck } from 'lucide-react';
import { DtcScanReport, ScannedDtc } from '../services/obd2/dtcScanner';
import { telemetryManager } from '../services/telemetryManager';

export const DtcScannerCard: React.FC = () => {
  const [report, setReport] = useState<DtcScanReport | null>(telemetryManager.latestDtcReport);
  const [isScanning, setIsScanning] = useState(false);
  const [showClearModal, setShowClearModal] = useState(false);
  const [activeDtcDetail, setActiveDtcDetail] = useState<ScannedDtc | null>(null);

  const handleScan = async () => {
    setIsScanning(true);
    try {
      const res = await telemetryManager.runDtcScan();
      setReport(res);
      if (res.pendingCodes.length > 0) {
        setActiveDtcDetail(res.pendingCodes[0]);
      } else if (res.confirmedCodes.length > 0) {
        setActiveDtcDetail(res.confirmedCodes[0]);
      }
    } catch (err) {
      console.error('Scan failed:', err);
    } finally {
      setIsScanning(false);
    }
  };

  const handleClear = async () => {
    setShowClearModal(false);
    setIsScanning(true);
    try {
      await telemetryManager.clearDtcCodes();
      setReport(telemetryManager.latestDtcReport);
      setActiveDtcDetail(null);
    } finally {
      setIsScanning(false);
    }
  };

  const allCodes = report ? [...report.pendingCodes, ...report.confirmedCodes, ...report.permanentCodes] : [];
  const pendingCount = report?.pendingCodes.length || 0;
  const confirmedCount = report?.confirmedCodes.length || 0;
  const permanentCount = report?.permanentCodes.length || 0;

  return (
    <div className="flex flex-col gap-5">
      {/* Scanning is the one real verb on this screen, so it keeps a button shape - but a
          bordered one. A solid red fill made the loudest thing on the tab an action you
          take a couple of times a year. */}
      <div className="flex flex-wrap items-baseline justify-between gap-3">
        <div>
          <h2 style={{ fontSize: 15, fontWeight: 500, letterSpacing: '-0.01em', color: '#eef0f2' }}>
            Trouble codes
          </h2>
          <p style={{ fontSize: 11.5, color: '#6b727a', marginTop: 3 }}>
            Modes 03, 07 and 0A — current, pending and permanent
          </p>
        </div>

        <div className="flex items-center gap-5 shrink-0">
          {report && allCodes.length > 0 && (
            <button
              onClick={() => setShowClearModal(true)}
              className="transition-colors"
              style={{ fontSize: 12.5, color: '#d8453b' }}
            >
              Clear codes
            </button>
          )}

          <button
            onClick={handleScan}
            disabled={isScanning}
            className="px-4 py-2 transition-colors disabled:opacity-50"
            style={{
              fontSize: 12.5,
              color: '#eef0f2',
              border: '1px solid var(--hairline-strong)',
              borderRadius: 8,
            }}
          >
            {isScanning ? 'Scanning…' : 'Run scan'}
          </button>
        </div>
      </div>

      {/* Four counts as rows. As tiles they were four filled boxes in a grid, each with a
          label, a value, a unit and a footnote in four different sizes - and the values
          are zero almost every time you look. */}
      <div className="flex flex-col">
        <div className="stat-row">
          <span className="t-key">Check engine light</span>
          <span className="t-value" style={{ color: report?.milOn ? '#d8453b' : '#eef0f2' }}>
            {report?.milOn ? 'On' : 'Off'}
          </span>
        </div>
        <div className="stat-row">
          <span className="t-key">Confirmed</span>
          <span
            className="t-value tabular-nums"
            style={{ color: confirmedCount > 0 ? '#c8952e' : '#eef0f2' }}
          >
            {confirmedCount}
            <span className="ml-2.5" style={{ fontSize: 11.5, color: '#6b727a' }}>
              active faults
            </span>
          </span>
        </div>
        <div className="stat-row">
          <span className="t-key">Pending</span>
          <span className="t-value tabular-nums">
            {pendingCount}
            <span className="ml-2.5" style={{ fontSize: 11.5, color: '#6b727a' }}>
              no dash light yet
            </span>
          </span>
        </div>
        <div className="stat-row">
          <span className="t-key">Permanent</span>
          <span className="t-value tabular-nums">
            {permanentCount}
            <span className="ml-2.5" style={{ fontSize: 11.5, color: '#6b727a' }}>
              in ECU memory
            </span>
          </span>
        </div>
      </div>

      <div className="flex flex-col gap-2 pt-4" style={{ borderTop: '1px solid var(--hairline)' }}>
        <h3 style={{ fontSize: 13, fontWeight: 500, color: '#eef0f2' }}>
          What a pending code means
        </h3>
        <p style={{ fontSize: 12.5, color: '#6b727a', lineHeight: 1.6 }}>
          When the ECU sees an intermittent sensor reading or a single-trip misfire it logs the
          fault without lighting the dash. The warning light only comes on if the same fault
          repeats across two or three drive cycles — so a pending code is the early warning.
        </p>
      </div>

      {/* Main Results */}
      {!report && !isScanning ? (
        <div
          className="flex flex-col items-center text-center gap-2 py-10"
          style={{ borderTop: '1px solid var(--hairline)' }}
        >
          <p style={{ fontSize: 13, color: '#6b727a', maxWidth: '32ch', lineHeight: 1.6 }}>
            Nothing scanned yet. Run a scan to read modes 03, 07 and 0A off the ECU.
          </p>
        </div>
      ) : allCodes.length === 0 ? (
        <div
          className="flex flex-col items-center text-center gap-2 py-10"
          style={{ borderTop: '1px solid var(--hairline)' }}
        >
          <h3 style={{ fontSize: 15, fontWeight: 500, color: '#eef0f2' }}>No codes</h3>
          <p style={{ fontSize: 13, color: '#6b727a', maxWidth: '38ch', lineHeight: 1.6 }}>
            Nothing confirmed, pending or stored in memory.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-3 items-start">
          {/* Left Column: Code List */}
          <div className="flex flex-col gap-2">
            <span className="text-[10px] font-bold text-[#6b727a] uppercase">
              Detected Trouble Codes ({allCodes.length})
            </span>
            {allCodes.map((dtc) => {
              const isSelected = activeDtcDetail?.code === dtc.code;
              const isPending = dtc.type === 'Pending';
              const isConfirmed = dtc.type === 'Confirmed';

              return (
                <button
                  key={`${dtc.code}-${dtc.type}`}
                  onClick={() => setActiveDtcDetail(dtc)}
                  className={`p-2.5 rounded-xl border text-left flex flex-col gap-1 transition-all ${
                    isSelected
                      ? 'bg-[#182030] border-[#9aa1a9]'
                      : 'bg-[#101215] border-[rgba(255,255,255,0.06)] hover:border-[rgba(255,255,255,0.12)]'
                  }`}
                >
                  <div className="flex items-center justify-between w-full">
                    <span className="text-xs font-medium text-[#eef0f2]">
                      {dtc.code}
                    </span>
                    <span
                      className={`badge-pill text-[8px] py-0.5 px-1.5 ${
                        isPending ? '' : isConfirmed ? 'badge-alert' : 'badge-warn'
                      }`}
                    >
                      {dtc.type.toUpperCase()}
                    </span>
                  </div>
                  <span className="text-[11px] font-medium text-[#9aa1a9] line-clamp-1">
                    {dtc.details.title}
                  </span>
                  <span className="text-[9px] text-[#6b727a]">
                    {dtc.details.system}
                  </span>
                </button>
              );
            })}
          </div>

          {/* Right 2-Columns: Selected Code Details */}
          {activeDtcDetail && (
            <div className="lg:col-span-2 telemetry-card-subtle p-3.5 flex flex-col gap-3 text-xs">
              <div className="flex items-start justify-between border-b border-[rgba(255,255,255,0.06)] pb-2">
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="text-base font-medium text-[#eef0f2]">
                      {activeDtcDetail.code}
                    </h3>
                    <span className="badge-pill badge-alert text-[9px]">
                      {activeDtcDetail.details.severity.toUpperCase()} SEVERITY
                    </span>
                  </div>
                  <h4 className="text-xs font-bold text-[#9aa1a9] mt-0.5">
                    {activeDtcDetail.details.title}
                  </h4>
                </div>

                <span className="text-[9px] text-[#6b727a] bg-[#101215] px-2 py-0.5 rounded border border-[rgba(255,255,255,0.06)]">
                  {activeDtcDetail.details.category}
                </span>
              </div>

              {/* Description */}
              <div className="flex flex-col gap-1">
                <span className="text-[9px] font-bold text-[#6b727a] uppercase">
                  ECU Fault Description
                </span>
                <p className="text-xs text-[#9aa1a9] leading-relaxed">
                  {activeDtcDetail.details.description}
                </p>
              </div>

              {/* 2013 Civic Specific Tech Notes */}
              {activeDtcDetail.details.civicSpecificNotes && (
                <div className="bg-[#1f2328] border border-[#c8952e]/30 rounded-lg p-2.5 text-[#c8952e] text-[11px] leading-relaxed">
                  <strong className="block text-[#c8952e]">
                    💡 2013 Civic LX 1.8L Note:
                  </strong>
                  {activeDtcDetail.details.civicSpecificNotes}
                </div>
              )}

              {/* Symptoms & Possible Causes */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 pt-1">
                <div className="bg-[#101215] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col gap-1">
                  <span className="text-[9px] font-bold text-[#6b727a] uppercase flex items-center gap-1">
                    <AlertTriangle size={10} className="text-[#c8952e]" />
                    Symptoms
                  </span>
                  <ul className="list-disc list-inside space-y-0.5 text-[10px] text-[#9aa1a9]">
                    {activeDtcDetail.details.symptoms.map((s, idx) => (
                      <li key={idx}>{s}</li>
                    ))}
                  </ul>
                </div>

                <div className="bg-[#101215] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col gap-1">
                  <span className="text-[9px] font-bold text-[#6b727a] uppercase flex items-center gap-1">
                    <HelpCircle size={10} className="text-[#9aa1a9]" />
                    Common Causes
                  </span>
                  <ul className="list-disc list-inside space-y-0.5 text-[10px] text-[#9aa1a9]">
                    {activeDtcDetail.details.possibleCauses.map((c, idx) => (
                      <li key={idx}>{c}</li>
                    ))}
                  </ul>
                </div>
              </div>

              {/* Freeze Frame */}
              {activeDtcDetail.freezeFrame && (
                <div className="bg-[#101215] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col gap-1">
                  <span className="text-[9px] font-bold text-[#9aa1a9] uppercase flex items-center gap-1">
                    <Clock size={10} />
                    Freeze Frame Snapshot
                  </span>
                  <div className="grid grid-cols-3 sm:grid-cols-6 gap-1.5 text-center text-[9px] pt-0.5">
                    <div className="bg-[#1f2328] rounded p-1">
                      <span className="text-[#6b727a] block">RPM</span>
                      <strong className="text-[#eef0f2] tabular-nums">{activeDtcDetail.freezeFrame.rpm}</strong>
                    </div>
                    <div className="bg-[#1f2328] rounded p-1">
                      <span className="text-[#6b727a] block">SPEED</span>
                      <strong className="text-[#eef0f2] tabular-nums">{activeDtcDetail.freezeFrame.speedMph} mph</strong>
                    </div>
                    <div className="bg-[#1f2328] rounded p-1">
                      <span className="text-[#6b727a] block">COOLANT</span>
                      <strong className="text-[#eef0f2] tabular-nums">{activeDtcDetail.freezeFrame.coolantTempF}°F</strong>
                    </div>
                    <div className="bg-[#1f2328] rounded p-1">
                      <span className="text-[#6b727a] block">LOAD</span>
                      <strong className="text-[#eef0f2] tabular-nums">{activeDtcDetail.freezeFrame.calcLoad}%</strong>
                    </div>
                    <div className="bg-[#1f2328] rounded p-1">
                      <span className="text-[#6b727a] block">STFT</span>
                      <strong className="text-[#eef0f2] tabular-nums">
                        {activeDtcDetail.freezeFrame.fuelTrimSt >= 0 ? '+' : ''}{activeDtcDetail.freezeFrame.fuelTrimSt}%
                      </strong>
                    </div>
                    <div className="bg-[#1f2328] rounded p-1">
                      <span className="text-[#6b727a] block">LTFT</span>
                      <strong className="text-[#eef0f2] tabular-nums">
                        {activeDtcDetail.freezeFrame.fuelTrimLt >= 0 ? '+' : ''}{activeDtcDetail.freezeFrame.fuelTrimLt}%
                      </strong>
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* I/M Emission Readiness Monitors */}
      {report && (
        <div className="telemetry-card-subtle p-2.5 flex flex-col gap-1.5">
          {(() => {
            const monitorList = [
              { name: 'MISFIRE', status: report.monitors.misfire },
              { name: 'FUEL SYS', status: report.monitors.fuelSystem },
              { name: 'COMP', status: report.monitors.comprehensive },
              { name: 'CATALYST', status: report.monitors.catalyst },
              { name: 'EVAP', status: report.monitors.evap },
              { name: 'O2 SENS', status: report.monitors.o2Sensor },
              { name: 'O2 HTR', status: report.monitors.o2Heater },
              { name: 'EGR/VVT', status: report.monitors.egrVvt },
            ];
            const ready = monitorList.filter((m) => m.status === 'Ready').length;
            const notReady = monitorList.filter((m) => m.status === 'Not Ready').length;
            const na = monitorList.filter((m) => m.status === 'N/A').length;

            return (
              <>
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[#6b727a] uppercase font-bold flex items-center gap-1.5 text-[11px]">
                    <ShieldCheck size={13} className={notReady > 0 ? 'text-[#c8952e]' : 'text-[#eef0f2]'} />
                    Emissions I/M Readiness
                  </span>
                  {/* Reports what the ECU actually returned. This used to read
                      "All 8 Passed" unconditionally, which is the answer someone
                      would rely on right before a smog test. */}
                  <span className="text-[11px] text-right">
                    <span className="text-[#eef0f2] font-bold">{ready} ready</span>
                    {notReady > 0 && (
                      <span className="text-[#c8952e] font-bold"> · {notReady} not ready</span>
                    )}
                    {na > 0 && <span className="text-[#6b727a]"> · {na} n/a</span>}
                  </span>
                </div>

                <div className="grid grid-cols-4 sm:grid-cols-8 gap-1 text-[10px]">
                  {monitorList.map((m) => (
                    <div
                      key={m.name}
                      className="bg-[#101215] border border-[rgba(255,255,255,0.06)] rounded p-1.5 flex flex-col items-center text-center"
                    >
                      <span className="text-[#6b727a] text-[9px]">{m.name}</span>
                      <span
                        className={`font-bold mt-0.5 ${
                          m.status === 'Ready'
                            ? 'text-[#eef0f2]'
                            : m.status === 'Not Ready'
                            ? 'text-[#c8952e]'
                            : 'text-[#464c53]'
                        }`}
                      >
                        {m.status}
                      </span>
                    </div>
                  ))}
                </div>
              </>
            );
          })()}
        </div>
      )}

      {/* Clear Codes Modal */}
      {showClearModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-xs flex items-center justify-center p-4">
          <div className="bg-[#181b20] border border-[rgba(255,255,255,0.12)] rounded-2xl max-w-sm w-full p-5 shadow-2xl flex flex-col gap-3.5">
            <div className="flex items-center gap-2 text-[#d8453b]">
              <AlertOctagon size={20} />
              <h3 className="text-sm font-bold text-[#eef0f2]">
                Clear All DTCs & Reset MIL?
              </h3>
            </div>
            <p className="text-xs text-[#9aa1a9] leading-relaxed">
              Send Mode 04 to your Honda ECU, clearing stored fault codes and turning off the Check Engine Light.
            </p>
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setShowClearModal(false)}
                className="px-3 py-1.5 rounded-lg bg-[#1f2328] text-[#9aa1a9] hover:text-[#eef0f2] text-xs"
              >
                Cancel
              </button>
              <button
                onClick={handleClear}
                className="px-4 py-1.5 rounded-lg bg-[#d8453b] hover:bg-[#d61c2f] text-white text-xs font-bold transition-colors"
              >
                Confirm Clear
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
