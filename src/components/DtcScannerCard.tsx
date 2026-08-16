import React, { useState } from 'react';
import {
  Search,
  AlertTriangle,
  CheckCircle2,
  AlertOctagon,
  Clock,
  Trash2,
  RefreshCw,
  HelpCircle,
  Zap,
  ShieldCheck
} from 'lucide-react';
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
    <div className="telemetry-card flex flex-col gap-3.5">
      {/* Header Bar */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[rgba(255,255,255,0.06)] pb-3">
        <div className="flex items-center gap-2.5">
          <div className="p-1.5 rounded-lg bg-[rgba(255,255,255,0.05)] text-[#ff2a40] border border-[rgba(255,255,255,0.08)]">
            <Search size={18} />
          </div>
          <div>
            <h2 className="text-sm font-bold font-['Chakra_Petch'] text-[#f8fafc] tracking-wide">
              DIAGNOSTIC TROUBLE SCANNER (DTC)
            </h2>
            <p className="text-[10px] text-[#64748b]">Mode 03 (Current) • Mode 07 (Pending / No CEL) • Mode 0A (Permanent)</p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {report && allCodes.length > 0 && (
            <button
              onClick={() => setShowClearModal(true)}
              className="flex items-center gap-1 px-2.5 py-1.5 rounded-lg bg-[#ff2a40]/15 text-[#ff6b7b] hover:bg-[#ff2a40]/25 border border-[#ff2a40]/30 text-xs font-bold font-['Chakra_Petch'] transition-all"
            >
              <Trash2 size={12} />
              Clear DTCs
            </button>
          )}

          <button
            onClick={handleScan}
            disabled={isScanning}
            className="flex items-center gap-1.5 px-3.5 py-1.5 rounded-lg bg-[#ff2a40] hover:bg-[#d61c2f] text-white text-xs font-bold font-['Chakra_Petch'] transition-all disabled:opacity-50"
          >
            {isScanning ? (
              <>
                <RefreshCw size={13} className="animate-spin" />
                Scanning ECU...
              </>
            ) : (
              <>
                <Search size={13} />
                Run Diagnostic Scan
              </>
            )}
          </button>
        </div>
      </div>

      {/* Summary Status Strip */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2 text-xs">
        {/* CEL Status */}
        <div className="telemetry-card-subtle flex flex-col justify-between">
          <span className="text-[9px] font-bold text-[#64748b] uppercase font-['Chakra_Petch']">
            Check Engine Light
          </span>
          <div className="flex items-center gap-1.5 mt-1">
            <div className={`w-2 h-2 rounded-full ${report?.milOn ? 'bg-[#ff2a40]' : 'bg-[#00e676]'}`} />
            <span className={`text-xs font-bold font-['Chakra_Petch'] ${report?.milOn ? 'text-[#ff2a40]' : 'text-[#00e676]'}`}>
              {report?.milOn ? 'MIL ON' : 'MIL OFF (NORMAL)'}
            </span>
          </div>
          <span className="text-[8px] text-[#64748b]">Dash Warning Light</span>
        </div>

        {/* Pending Codes */}
        <div className="telemetry-card-subtle flex flex-col justify-between">
          <span className="text-[9px] font-bold text-[#00d2ff] uppercase font-['Chakra_Petch'] flex items-center gap-1">
            <Zap size={10} />
            Pending (No CEL)
          </span>
          <div className="flex items-baseline gap-1 mt-1">
            <span className={`text-xl font-black font-['Chakra_Petch'] tabular-nums ${pendingCount > 0 ? 'text-[#00d2ff]' : 'text-[#f8fafc]'}`}>
              {pendingCount}
            </span>
            <span className="text-[9px] text-[#64748b]">early warnings</span>
          </div>
          <span className="text-[8px] text-[#64748b]">Intermittent faults</span>
        </div>

        {/* Confirmed Codes */}
        <div className="telemetry-card-subtle flex flex-col justify-between">
          <span className="text-[9px] font-bold text-[#ffaa00] uppercase font-['Chakra_Petch']">
            Confirmed Codes
          </span>
          <div className="flex items-baseline gap-1 mt-1">
            <span className={`text-xl font-black font-['Chakra_Petch'] tabular-nums ${confirmedCount > 0 ? 'text-[#ffaa00]' : 'text-[#f8fafc]'}`}>
              {confirmedCount}
            </span>
            <span className="text-[9px] text-[#64748b]">active faults</span>
          </div>
          <span className="text-[8px] text-[#64748b]">Mode 03 verified</span>
        </div>

        {/* Permanent Codes */}
        <div className="telemetry-card-subtle flex flex-col justify-between">
          <span className="text-[9px] font-bold text-[#64748b] uppercase font-['Chakra_Petch']">
            Permanent / NVRAM
          </span>
          <div className="flex items-baseline gap-1 mt-1">
            <span className="text-xl font-black text-[#94a3b8] font-['Chakra_Petch'] tabular-nums">
              {permanentCount}
            </span>
            <span className="text-[9px] text-[#64748b]">in memory</span>
          </div>
          <span className="text-[8px] text-[#64748b]">Mode 0A NVRAM history</span>
        </div>
      </div>

      {/* Explanatory Banner */}
      <div className="bg-[#00d2ff]/10 border border-[#00d2ff]/20 rounded-xl p-3 flex items-start gap-2.5 text-xs text-[#94a3b8] leading-relaxed">
        <Zap size={15} className="text-[#00d2ff] shrink-0 mt-0.5" />
        <div>
          <strong className="text-[#00d2ff] font-['Chakra_Petch'] uppercase block text-[11px]">
            Mode 07 Pending Codes (Catch Issues Before a Dashboard CEL)
          </strong>
          When the Civic ECU detects an intermittent sensor anomaly or single-trip misfire, it logs a <strong>Pending Code</strong>. The dashboard CEL will only turn on after repeating across 2–3 drive cycles.
        </div>
      </div>

      {/* Main Results */}
      {!report && !isScanning ? (
        <div className="telemetry-card-subtle p-8 flex flex-col items-center justify-center text-center gap-3">
          <Search size={28} className="text-[#475569]" />
          <div className="flex flex-col gap-1">
            <h3 className="text-xs font-bold font-['Chakra_Petch'] text-[#f8fafc]">
              No Diagnostic Scan Run Yet
            </h3>
            <p className="text-[11px] text-[#64748b] max-w-sm">
              Click <strong>Run Diagnostic Scan</strong> above to query Modes 03, 07, and 0A across your Civic ECU.
            </p>
          </div>
          <button
            onClick={handleScan}
            className="mt-1 px-3 py-1.5 rounded-lg bg-[#161a26] hover:bg-[#1f2638] text-[#00d2ff] text-xs font-bold font-['Chakra_Petch'] border border-[#00d2ff]/30 transition-all"
          >
            Start Scan Now
          </button>
        </div>
      ) : allCodes.length === 0 ? (
        <div className="telemetry-card-subtle p-8 flex flex-col items-center justify-center text-center gap-2">
          <div className="w-10 h-10 rounded-full bg-[#00e676]/15 border border-[#00e676]/30 flex items-center justify-center text-[#00e676]">
            <CheckCircle2 size={20} />
          </div>
          <h3 className="text-sm font-bold font-['Chakra_Petch'] text-[#00e676]">
            Zero Fault Codes Found!
          </h3>
          <p className="text-xs text-[#94a3b8] max-w-md">
            No confirmed, pending, or permanent diagnostic trouble codes exist in your 2013 Civic ECU memory. All systems are operating within factory tolerances.
          </p>
        </div>
      ) : (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-3 items-start">
          {/* Left Column: Code List */}
          <div className="flex flex-col gap-2">
            <span className="text-[10px] font-bold text-[#64748b] uppercase font-['Chakra_Petch']">
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
                      ? 'bg-[#182030] border-[#00d2ff]'
                      : 'bg-[#08090d] border-[rgba(255,255,255,0.06)] hover:border-[rgba(255,255,255,0.12)]'
                  }`}
                >
                  <div className="flex items-center justify-between w-full">
                    <span className="text-xs font-black font-['Chakra_Petch'] text-[#f8fafc]">
                      {dtc.code}
                    </span>
                    <span
                      className={`badge-pill text-[8px] py-0.5 px-1.5 ${
                        isPending ? 'badge-cyan' : isConfirmed ? 'badge-red' : 'badge-amber'
                      }`}
                    >
                      {dtc.type.toUpperCase()}
                    </span>
                  </div>
                  <span className="text-[11px] font-medium text-[#94a3b8] line-clamp-1">
                    {dtc.details.title}
                  </span>
                  <span className="text-[9px] text-[#64748b]">
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
                    <h3 className="text-base font-black font-['Chakra_Petch'] text-[#f8fafc]">
                      {activeDtcDetail.code}
                    </h3>
                    <span className="badge-pill badge-red text-[9px]">
                      {activeDtcDetail.details.severity.toUpperCase()} SEVERITY
                    </span>
                  </div>
                  <h4 className="text-xs font-bold text-[#00d2ff] font-['Chakra_Petch'] mt-0.5">
                    {activeDtcDetail.details.title}
                  </h4>
                </div>

                <span className="text-[9px] text-[#64748b] bg-[#08090d] px-2 py-0.5 rounded border border-[rgba(255,255,255,0.06)]">
                  {activeDtcDetail.details.category}
                </span>
              </div>

              {/* Description */}
              <div className="flex flex-col gap-1">
                <span className="text-[9px] font-bold text-[#64748b] uppercase font-['Chakra_Petch']">
                  ECU Fault Description
                </span>
                <p className="text-xs text-[#94a3b8] leading-relaxed">
                  {activeDtcDetail.details.description}
                </p>
              </div>

              {/* 2013 Civic Specific Tech Notes */}
              {activeDtcDetail.details.civicSpecificNotes && (
                <div className="bg-[#121622] border border-[#ffaa00]/30 rounded-lg p-2.5 text-[#ffc966] text-[11px] leading-relaxed">
                  <strong className="font-['Chakra_Petch'] block text-[#ffaa00]">
                    💡 2013 Civic LX 1.8L Note:
                  </strong>
                  {activeDtcDetail.details.civicSpecificNotes}
                </div>
              )}

              {/* Symptoms & Possible Causes */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 pt-1">
                <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col gap-1">
                  <span className="text-[9px] font-bold text-[#64748b] uppercase font-['Chakra_Petch'] flex items-center gap-1">
                    <AlertTriangle size={10} className="text-[#ffaa00]" />
                    Symptoms
                  </span>
                  <ul className="list-disc list-inside space-y-0.5 text-[10px] text-[#94a3b8]">
                    {activeDtcDetail.details.symptoms.map((s, idx) => (
                      <li key={idx}>{s}</li>
                    ))}
                  </ul>
                </div>

                <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col gap-1">
                  <span className="text-[9px] font-bold text-[#64748b] uppercase font-['Chakra_Petch'] flex items-center gap-1">
                    <HelpCircle size={10} className="text-[#00d2ff]" />
                    Common Causes
                  </span>
                  <ul className="list-disc list-inside space-y-0.5 text-[10px] text-[#94a3b8]">
                    {activeDtcDetail.details.possibleCauses.map((c, idx) => (
                      <li key={idx}>{c}</li>
                    ))}
                  </ul>
                </div>
              </div>

              {/* Freeze Frame */}
              {activeDtcDetail.freezeFrame && (
                <div className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded-lg p-2 flex flex-col gap-1">
                  <span className="text-[9px] font-bold text-[#00d2ff] uppercase font-['Chakra_Petch'] flex items-center gap-1">
                    <Clock size={10} />
                    Freeze Frame Snapshot
                  </span>
                  <div className="grid grid-cols-3 sm:grid-cols-6 gap-1.5 text-center text-[9px] pt-0.5">
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">RPM</span>
                      <strong className="text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">{activeDtcDetail.freezeFrame.rpm}</strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">SPEED</span>
                      <strong className="text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">{activeDtcDetail.freezeFrame.speedMph} mph</strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">COOLANT</span>
                      <strong className="text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">{activeDtcDetail.freezeFrame.coolantTempF}°F</strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">LOAD</span>
                      <strong className="text-[#f8fafc] font-['Chakra_Petch'] tabular-nums">{activeDtcDetail.freezeFrame.calcLoad}%</strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">STFT</span>
                      <strong className="text-[#00e676] font-['Chakra_Petch'] tabular-nums">
                        {activeDtcDetail.freezeFrame.fuelTrimSt >= 0 ? '+' : ''}{activeDtcDetail.freezeFrame.fuelTrimSt}%
                      </strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">LTFT</span>
                      <strong className="text-[#00e676] font-['Chakra_Petch'] tabular-nums">
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
                <div className="flex items-center justify-between gap-2 font-['Chakra_Petch']">
                  <span className="text-[#64748b] uppercase font-bold flex items-center gap-1.5 text-[11px]">
                    <ShieldCheck size={13} className={notReady > 0 ? 'text-[#ffaa00]' : 'text-[#00e676]'} />
                    Emissions I/M Readiness
                  </span>
                  {/* Reports what the ECU actually returned. This used to read
                      "All 8 Passed" unconditionally, which is the answer someone
                      would rely on right before a smog test. */}
                  <span className="text-[11px] text-right">
                    <span className="text-[#00e676] font-bold">{ready} ready</span>
                    {notReady > 0 && (
                      <span className="text-[#ffaa00] font-bold"> · {notReady} not ready</span>
                    )}
                    {na > 0 && <span className="text-[#64748b]"> · {na} n/a</span>}
                  </span>
                </div>

                <div className="grid grid-cols-4 sm:grid-cols-8 gap-1 text-[10px] font-['Chakra_Petch']">
                  {monitorList.map((m) => (
                    <div
                      key={m.name}
                      className="bg-[#08090d] border border-[rgba(255,255,255,0.06)] rounded p-1.5 flex flex-col items-center text-center"
                    >
                      <span className="text-[#64748b] text-[9px]">{m.name}</span>
                      <span
                        className={`font-bold mt-0.5 ${
                          m.status === 'Ready'
                            ? 'text-[#00e676]'
                            : m.status === 'Not Ready'
                            ? 'text-[#ffaa00]'
                            : 'text-[#475569]'
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
          <div className="bg-[#0e111a] border border-[rgba(255,255,255,0.12)] rounded-2xl max-w-sm w-full p-5 shadow-2xl flex flex-col gap-3.5">
            <div className="flex items-center gap-2 text-[#ff2a40]">
              <AlertOctagon size={20} />
              <h3 className="text-sm font-bold font-['Chakra_Petch'] text-[#f8fafc]">
                Clear All DTCs & Reset MIL?
              </h3>
            </div>
            <p className="text-xs text-[#94a3b8] leading-relaxed">
              Send Mode 04 to your Honda ECU, clearing stored fault codes and turning off the Check Engine Light.
            </p>
            <div className="flex justify-end gap-2 pt-2">
              <button
                onClick={() => setShowClearModal(false)}
                className="px-3 py-1.5 rounded-lg bg-[#161a26] text-[#94a3b8] hover:text-[#f8fafc] text-xs font-['Chakra_Petch']"
              >
                Cancel
              </button>
              <button
                onClick={handleClear}
                className="px-4 py-1.5 rounded-lg bg-[#ff2a40] hover:bg-[#d61c2f] text-white text-xs font-bold font-['Chakra_Petch'] transition-colors"
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
