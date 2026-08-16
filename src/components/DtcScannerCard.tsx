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
    <div className="telemetry-card flex flex-col gap-4">
      {/* Header Bar */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-[#1b2030] pb-3">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-xl bg-[#ff2a40]/15 text-[#ff2a40] border border-[#ff2a40]/30">
            <Search size={20} />
          </div>
          <div>
            <h2 className="text-base font-bold font-['Chakra_Petch'] text-[#f8fafc] tracking-wide">
              DIAGNOSTIC FAULT SCANNER (DTC)
            </h2>
            <p className="text-[11px] text-[#64748b]">Mode 03 (Current) • Mode 07 (Pending / No CEL) • Mode 0A (Permanent)</p>
          </div>
        </div>

        <div className="flex items-center gap-2">
          {report && allCodes.length > 0 && (
            <button
              onClick={() => setShowClearModal(true)}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-[#ff2a40]/15 text-[#ff6b7b] hover:bg-[#ff2a40]/25 border border-[#ff2a40]/30 text-xs font-bold font-['Chakra_Petch'] transition-all"
            >
              <Trash2 size={13} />
              Clear DTCs
            </button>
          )}

          <button
            onClick={handleScan}
            disabled={isScanning}
            className="flex items-center gap-1.5 px-4 py-1.5 rounded-xl bg-gradient-to-r from-[#ff2a40] to-[#d61c2f] hover:brightness-110 text-white text-xs font-bold font-['Chakra_Petch'] shadow-[0_0_15px_rgba(255,42,64,0.35)] transition-all disabled:opacity-50"
          >
            {isScanning ? (
              <>
                <RefreshCw size={14} className="animate-spin" />
                Scanning ECU...
              </>
            ) : (
              <>
                <Search size={14} />
                Run Diagnostic Scan
              </>
            )}
          </button>
        </div>
      </div>

      {/* Summary Status Strip */}
      <div className="grid grid-cols-1 sm:grid-cols-4 gap-2 text-xs">
        {/* CEL Status */}
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col justify-between">
          <span className="text-[10px] font-bold text-[#64748b] uppercase font-['Chakra_Petch']">
            Check Engine Light
          </span>
          <div className="flex items-center gap-2 mt-1">
            <div className={`w-2.5 h-2.5 rounded-full ${report?.milOn ? 'bg-[#ff2a40] shadow-[0_0_8px_#ff2a40]' : 'bg-[#00e676]'}`} />
            <span className={`text-sm font-bold font-['Chakra_Petch'] ${report?.milOn ? 'text-[#ff2a40]' : 'text-[#00e676]'}`}>
              {report?.milOn ? 'MIL ILLUMINATED' : 'MIL OFF (NORMAL)'}
            </span>
          </div>
          <span className="text-[9px] text-[#475569]">ECU Dash Warning Light</span>
        </div>

        {/* Pending Codes (The Key Feature!) */}
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col justify-between">
          <span className="text-[10px] font-bold text-[#00d2ff] uppercase font-['Chakra_Petch'] flex items-center gap-1">
            <Zap size={11} />
            Pending (No CEL)
          </span>
          <div className="flex items-baseline gap-1 mt-1">
            <span className={`text-2xl font-extrabold font-['Chakra_Petch'] ${pendingCount > 0 ? 'text-[#00d2ff] glow-cyan' : 'text-[#f8fafc]'}`}>
              {pendingCount}
            </span>
            <span className="text-[10px] text-[#64748b]">early warnings</span>
          </div>
          <span className="text-[9px] text-[#475569]">Non-triggering anomalies</span>
        </div>

        {/* Confirmed Codes */}
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col justify-between">
          <span className="text-[10px] font-bold text-[#ffaa00] uppercase font-['Chakra_Petch']">
            Confirmed Codes
          </span>
          <div className="flex items-baseline gap-1 mt-1">
            <span className={`text-2xl font-extrabold font-['Chakra_Petch'] ${confirmedCount > 0 ? 'text-[#ffaa00]' : 'text-[#f8fafc]'}`}>
              {confirmedCount}
            </span>
            <span className="text-[10px] text-[#64748b]">active faults</span>
          </div>
          <span className="text-[9px] text-[#475569]">Mode 03 verified</span>
        </div>

        {/* Permanent Codes */}
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-3 flex flex-col justify-between">
          <span className="text-[10px] font-bold text-[#64748b] uppercase font-['Chakra_Petch']">
            Permanent / Historic
          </span>
          <div className="flex items-baseline gap-1 mt-1">
            <span className="text-2xl font-extrabold text-[#94a3b8] font-['Chakra_Petch']">
              {permanentCount}
            </span>
            <span className="text-[10px] text-[#64748b]">in memory</span>
          </div>
          <span className="text-[9px] text-[#475569]">Mode 0A NVRAM history</span>
        </div>
      </div>

      {/* Explanatory Banner on Non-CEL Pending Codes */}
      <div className="bg-[#00d2ff]/10 border border-[#00d2ff]/25 rounded-xl p-3 flex items-start gap-2.5 text-xs text-[#94a3b8] leading-relaxed">
        <Zap size={16} className="text-[#00d2ff] shrink-0 mt-0.5" />
        <div>
          <strong className="text-[#00d2ff] font-['Chakra_Petch'] uppercase block">
            What Are "Pending" Codes That Don't Trigger a CEL?
          </strong>
          When your Honda ECU detects an intermittent sensor glitch, occasional misfire, or minor fuel trim drift during a single drive cycle, it saves it as a <strong>Pending Code (Mode 07)</strong>. The ECU will <em>NOT</em> illuminate your Check Engine Light until the fault repeats across 2–3 consecutive drive cycles. Scanning pending codes lets you catch problems (like cracked intake hoses, aging O2 sensors, or dirty MAF elements) weeks before a dashboard light appears!
        </div>
      </div>

      {/* Main Results / Code Cards View */}
      {!report && !isScanning ? (
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-8 flex flex-col items-center justify-center text-center gap-3">
          <Search size={32} className="text-[#475569]" />
          <div className="flex flex-col gap-1">
            <h3 className="text-sm font-bold font-['Chakra_Petch'] text-[#f8fafc]">
              No Diagnostic Scan Run Yet
            </h3>
            <p className="text-xs text-[#64748b] max-w-sm">
              Click <strong>Run Diagnostic Scan</strong> above to query Modes 03, 07, and 0A across your Civic ECU.
            </p>
          </div>
          <button
            onClick={handleScan}
            className="mt-2 px-4 py-2 rounded-xl bg-[#161a26] hover:bg-[#1f2638] text-[#00d2ff] text-xs font-bold font-['Chakra_Petch'] border border-[#00d2ff]/30 transition-all"
          >
            Start Scan Now
          </button>
        </div>
      ) : allCodes.length === 0 ? (
        <div className="bg-[#090b10] border border-[#161a26] rounded-xl p-8 flex flex-col items-center justify-center text-center gap-3">
          <div className="w-12 h-12 rounded-full bg-[#00e676]/15 border border-[#00e676]/30 flex items-center justify-center text-[#00e676]">
            <CheckCircle2 size={24} />
          </div>
          <h3 className="text-base font-bold font-['Chakra_Petch'] text-[#00e676]">
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
            <span className="text-[11px] font-bold text-[#64748b] uppercase font-['Chakra_Petch']">
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
                  className={`p-3 rounded-xl border text-left flex flex-col gap-1.5 transition-all ${
                    isSelected
                      ? 'bg-[#182030] border-[#00d2ff] shadow-[0_0_12px_rgba(0,210,255,0.25)]'
                      : 'bg-[#090b10] border-[#161a26] hover:border-[#252b3d]'
                  }`}
                >
                  <div className="flex items-center justify-between w-full">
                    <span className="text-sm font-black font-['Chakra_Petch'] text-[#f8fafc]">
                      {dtc.code}
                    </span>
                    <span
                      className={`badge-pill text-[9px] py-0.5 px-2 ${
                        isPending ? 'badge-cyan' : isConfirmed ? 'badge-red' : 'badge-amber'
                      }`}
                    >
                      {dtc.type.toUpperCase()}
                    </span>
                  </div>
                  <span className="text-xs font-semibold text-[#94a3b8] line-clamp-1">
                    {dtc.details.title}
                  </span>
                  <span className="text-[10px] text-[#64748b]">
                    {dtc.details.system}
                  </span>
                </button>
              );
            })}
          </div>

          {/* Right 2-Columns: Selected Code Deep Diagnosis & Freeze Frame */}
          {activeDtcDetail && (
            <div className="lg:col-span-2 bg-[#090b10] border border-[#161a26] rounded-xl p-4 flex flex-col gap-3.5 text-xs">
              <div className="flex items-start justify-between border-b border-[#161a26] pb-3">
                <div>
                  <div className="flex items-center gap-2">
                    <h3 className="text-lg font-black font-['Chakra_Petch'] text-[#f8fafc]">
                      {activeDtcDetail.code}
                    </h3>
                    <span className="badge-pill badge-red text-[10px]">
                      {activeDtcDetail.details.severity.toUpperCase()} SEVERITY
                    </span>
                  </div>
                  <h4 className="text-xs font-bold text-[#00d2ff] font-['Chakra_Petch'] mt-0.5">
                    {activeDtcDetail.details.title}
                  </h4>
                </div>

                <span className="text-[10px] text-[#64748b] bg-[#121622] px-2 py-1 rounded border border-[#1a2030]">
                  {activeDtcDetail.details.category}
                </span>
              </div>

              {/* Description */}
              <div className="flex flex-col gap-1">
                <span className="text-[10px] font-bold text-[#64748b] uppercase font-['Chakra_Petch']">
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
                    💡 2013 Honda Civic Specific Note:
                  </strong>
                  {activeDtcDetail.details.civicSpecificNotes}
                </div>
              )}

              {/* Symptoms & Possible Causes Grid */}
              <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 pt-1">
                {/* Potential Symptoms */}
                <div className="bg-[#0b0e16] border border-[#161a26] rounded-lg p-2.5 flex flex-col gap-1.5">
                  <span className="text-[10px] font-bold text-[#64748b] uppercase font-['Chakra_Petch'] flex items-center gap-1">
                    <AlertTriangle size={11} className="text-[#ffaa00]" />
                    Potential Symptoms
                  </span>
                  <ul className="list-disc list-inside space-y-1 text-[11px] text-[#94a3b8]">
                    {activeDtcDetail.details.symptoms.map((s, idx) => (
                      <li key={idx}>{s}</li>
                    ))}
                  </ul>
                </div>

                {/* Possible Causes */}
                <div className="bg-[#0b0e16] border border-[#161a26] rounded-lg p-2.5 flex flex-col gap-1.5">
                  <span className="text-[10px] font-bold text-[#64748b] uppercase font-['Chakra_Petch'] flex items-center gap-1">
                    <HelpCircle size={11} className="text-[#00d2ff]" />
                    Common Causes (Civic 1.8L)
                  </span>
                  <ul className="list-disc list-inside space-y-1 text-[11px] text-[#94a3b8]">
                    {activeDtcDetail.details.possibleCauses.map((c, idx) => (
                      <li key={idx}>{c}</li>
                    ))}
                  </ul>
                </div>
              </div>

              {/* Freeze Frame Data Snapshot if available */}
              {activeDtcDetail.freezeFrame && (
                <div className="bg-[#0b0e16] border border-[#161a26] rounded-lg p-2.5 flex flex-col gap-1.5">
                  <span className="text-[10px] font-bold text-[#00d2ff] uppercase font-['Chakra_Petch'] flex items-center gap-1">
                    <Clock size={11} />
                    Freeze Frame Snapshot (Sensor Data when Fault Occurred)
                  </span>
                  <div className="grid grid-cols-3 sm:grid-cols-6 gap-2 text-center text-[10px] pt-1">
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">RPM</span>
                      <strong className="text-[#f8fafc] font-['Chakra_Petch']">{activeDtcDetail.freezeFrame.rpm}</strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">SPEED</span>
                      <strong className="text-[#f8fafc] font-['Chakra_Petch']">{activeDtcDetail.freezeFrame.speedMph} mph</strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">COOLANT</span>
                      <strong className="text-[#f8fafc] font-['Chakra_Petch']">{activeDtcDetail.freezeFrame.coolantTempF}°F</strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">LOAD</span>
                      <strong className="text-[#f8fafc] font-['Chakra_Petch']">{activeDtcDetail.freezeFrame.calcLoad}%</strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">STFT</span>
                      <strong className="text-[#00e676] font-['Chakra_Petch']">
                        {activeDtcDetail.freezeFrame.fuelTrimSt >= 0 ? '+' : ''}{activeDtcDetail.freezeFrame.fuelTrimSt}%
                      </strong>
                    </div>
                    <div className="bg-[#121622] rounded p-1">
                      <span className="text-[#64748b] block">LTFT</span>
                      <strong className="text-[#00e676] font-['Chakra_Petch']">
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
        <div className="bg-[#08090d] border border-[#141722] rounded-xl p-3 flex flex-col gap-2">
          <div className="flex items-center justify-between text-xs font-['Chakra_Petch']">
            <span className="text-[#64748b] uppercase font-bold flex items-center gap-1.5">
              <ShieldCheck size={14} className="text-[#00e676]" />
              State Emissions I/M Readiness Monitors:
            </span>
            <span className="text-[10px] text-[#00e676]">All 8 Monitors Passed</span>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-4 md:grid-cols-8 gap-1.5 text-[10px] font-['Chakra_Petch']">
            {[
              { name: 'MISFIRE', status: report.monitors.misfire },
              { name: 'FUEL SYS', status: report.monitors.fuelSystem },
              { name: 'COMPONENTS', status: report.monitors.comprehensive },
              { name: 'CATALYST', status: report.monitors.catalyst },
              { name: 'EVAP SYS', status: report.monitors.evap },
              { name: 'O2 SENSOR', status: report.monitors.o2Sensor },
              { name: 'O2 HEATER', status: report.monitors.o2Heater },
              { name: 'EGR / VVT', status: report.monitors.egrVvt },
            ].map((m) => (
              <div
                key={m.name}
                className="bg-[#0e1118] border border-[#1a2030] rounded-lg p-1.5 flex flex-col items-center text-center"
              >
                <span className="text-[#64748b] text-[9px]">{m.name}</span>
                <span className="text-[#00e676] font-bold mt-0.5">{m.status}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Clear Codes Confirmation Modal */}
      {showClearModal && (
        <div className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4">
          <div className="bg-[#0e1118] border border-[#252b3d] rounded-2xl max-w-sm w-full p-5 shadow-2xl flex flex-col gap-4">
            <div className="flex items-center gap-2 text-[#ff2a40]">
              <AlertOctagon size={24} />
              <h3 className="text-base font-bold font-['Chakra_Petch'] text-[#f8fafc]">
                Clear All DTCs & Reset MIL?
              </h3>
            </div>
            <p className="text-xs text-[#94a3b8] leading-relaxed">
              This will send Mode 04 to your Honda ECU, clearing stored fault codes and turning off the Check Engine Light.
              <br /><br />
              <strong className="text-[#ffaa00]">Note:</strong> Clearing codes also resets I/M readiness emission self-test monitors until you complete several drive cycles.
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
                Confirm Clear Codes
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
