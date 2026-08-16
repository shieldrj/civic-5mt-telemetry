import React from 'react';

interface RadialGaugeProps {
  value: number;
  min: number;
  max: number;
  title: string;
  unit: string;
  subValue?: string | number;
  subLabel?: string;
  accentColor?: string;
  redlineStart?: number;
  ticks?: number[];
  size?: number;
  isHero?: boolean;
}

export const RadialGauge: React.FC<RadialGaugeProps> = ({
  value,
  min,
  max,
  title,
  unit,
  subValue,
  subLabel,
  accentColor = '#ff2a40',
  redlineStart,
  ticks = [],
  size = 220,
  isHero = false,
}) => {
  const clampedValue = Math.max(min, Math.min(max, value));
  
  // 250-degree sweep from 145° (bottom-left) to 395° (bottom-right)
  const startAngle = 145;
  const endAngle = 395;
  const totalSweep = endAngle - startAngle; // 250°

  const valueRatio = Math.max(0, Math.min(1, (clampedValue - min) / (max - min)));
  const currentAngle = startAngle + valueRatio * totalSweep;

  const cx = 100;
  const cy = 100;
  const radius = 70;
  const strokeWidth = isHero ? 9 : 7;

  const degToRad = (deg: number) => (deg * Math.PI) / 180;

  const getCoord = (r: number, deg: number) => {
    const rad = degToRad(deg);
    return {
      x: cx + r * Math.cos(rad),
      y: cy + r * Math.sin(rad),
    };
  };

  const createArc = (startDeg: number, endDeg: number, r: number) => {
    const p1 = getCoord(r, startDeg);
    const p2 = getCoord(r, endDeg);
    const largeArc = Math.abs(endDeg - startDeg) > 180 ? 1 : 0;
    return `M ${p1.x.toFixed(2)} ${p1.y.toFixed(2)} A ${r} ${r} 0 ${largeArc} 1 ${p2.x.toFixed(2)} ${p2.y.toFixed(2)}`;
  };

  const gradientId = `gauge-grad-${title.replace(/[^a-zA-Z0-9]/g, '-').toLowerCase()}`;

  // Formatted main value
  const displayValue = typeof value === 'number'
    ? (unit === '%' || unit === '°F' || value >= 100 ? Math.round(value) : value.toFixed(1))
    : value;

  return (
    <div
      className="relative flex flex-col items-center justify-center select-none"
      style={{ width: size, height: size }}
    >
      <svg
        viewBox="0 0 200 200"
        className="w-full h-full overflow-visible"
      >
        <defs>
          <linearGradient id={gradientId} x1="0%" y1="100%" x2="100%" y2="0%">
            <stop offset="0%" stopColor={accentColor} stopOpacity="0.5" />
            <stop offset="100%" stopColor={accentColor} stopOpacity="1" />
          </linearGradient>
          {/* Subtle Outer Glow */}
          <filter id={`glow-${gradientId}`} x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="2.5" result="blur" />
            <feComposite in="SourceGraphic" in2="blur" operator="over" />
          </filter>
        </defs>

        {/* Outer Bezel */}
        <circle
          cx={cx}
          cy={cy}
          r={94}
          fill="none"
          stroke="rgba(255, 255, 255, 0.05)"
          strokeWidth="1.5"
        />

        {/* Background Track Arc */}
        <path
          d={createArc(startAngle, endAngle, radius)}
          fill="none"
          stroke="#161b28"
          strokeWidth={strokeWidth}
          strokeLinecap="round"
        />

        {/* Redline Warning Zone */}
        {redlineStart && redlineStart < max && (
          <path
            d={createArc(
              startAngle + ((redlineStart - min) / (max - min)) * totalSweep,
              endAngle,
              radius
            )}
            fill="none"
            stroke="rgba(255, 42, 64, 0.35)"
            strokeWidth={strokeWidth}
            strokeLinecap="round"
          />
        )}

        {/* Active Value Progress Arc */}
        {valueRatio > 0.005 && (
          <path
            d={createArc(startAngle, currentAngle, radius)}
            fill="none"
            stroke={`url(#${gradientId})`}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
            filter={`url(#glow-${gradientId})`}
          />
        )}

        {/* Outer Calibrated Ticks & High-Contrast Labels */}
        {ticks.map((t) => {
          const tRatio = (t - min) / (max - min);
          const tAngle = startAngle + tRatio * totalSweep;
          const p1 = getCoord(radius + 7, tAngle);
          const p2 = getCoord(radius + 14, tAngle);
          const pText = getCoord(radius + 22, tAngle);
          const isRed = redlineStart && t >= redlineStart;

          const tickLabel = t >= 1000 ? `${t / 1000}k` : `${t}`;

          return (
            <g key={t}>
              <line
                x1={p1.x.toFixed(2)}
                y1={p1.y.toFixed(2)}
                x2={p2.x.toFixed(2)}
                y2={p2.y.toFixed(2)}
                stroke={isRed ? '#ff2a40' : 'rgba(255, 255, 255, 0.4)'}
                strokeWidth={isRed ? '2' : '1.5'}
              />
              <text
                x={pText.x.toFixed(2)}
                y={(pText.y + 3.5).toFixed(2)}
                fill={isRed ? '#ff6b7b' : 'rgba(255, 255, 255, 0.6)'}
                fontSize={isHero ? "10" : "9"}
                fontFamily="'Chakra Petch', monospace"
                fontWeight="700"
                textAnchor="middle"
              >
                {tickLabel}
              </text>
            </g>
          );
        })}

        {/* High-Luminance Perimeter Pointer (Sweeps along outer arc without blocking central numbers) */}
        <g
          style={{
            transform: `rotate(${currentAngle}deg)`,
            transformOrigin: `${cx}px ${cy}px`,
            transition: 'transform 0.08s cubic-bezier(0.1, 0.9, 0.2, 1)',
          }}
        >
          {/* Outer Pointer Bead */}
          <circle
            cx={cx + radius}
            cy={cy}
            r={isHero ? 5.5 : 4.5}
            fill="#ffffff"
            stroke={accentColor}
            strokeWidth="2.5"
          />
        </g>
      </svg>

      {/* Center Digital Readout (Unobstructed, Maximum Scale & Contrast).
          Font sizes are derived from `size` rather than fixed, because the cockpit sizes
          these gauges from the viewport height to guarantee it fits without scrolling -
          fixed type would look oversized on a short screen and lost on a tall one. */}
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none text-center px-4">
        {/* Title */}
        <span
          className="font-bold tracking-widest text-[#94a3b8] uppercase font-['Inter'] leading-none"
          style={{ fontSize: Math.max(10, size * 0.052), marginBottom: size * 0.02 }}
        >
          {title}
        </span>

        {/* Hero Number & Unit */}
        <div className="flex items-baseline justify-center gap-1">
          <span
            className="font-black text-[#ffffff] font-['Chakra_Petch'] tabular-nums tracking-tight leading-none"
            style={{ fontSize: Math.max(30, size * 0.21) }}
          >
            {displayValue}
          </span>
          <span
            className="font-extrabold text-[#94a3b8] font-['Chakra_Petch'] leading-none"
            style={{ fontSize: Math.max(11, size * 0.058) }}
          >
            {unit}
          </span>
        </div>

        {/* Status / Sub-label */}
        {subValue !== undefined && (
          <div
            className="font-semibold font-['Inter'] px-2 py-0.5 rounded-md bg-[#121622] border border-[rgba(255,255,255,0.06)] text-[#cbd5e1] leading-tight"
            style={{ fontSize: Math.max(10, size * 0.05), marginTop: size * 0.035 }}
          >
            {subLabel ? <span className="text-[#64748b] mr-1">{subLabel}:</span> : null}
            <strong className="text-[#f8fafc] font-bold">{subValue}</strong>
          </div>
        )}
      </div>
    </div>
  );
};
