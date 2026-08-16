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
  size = 200,
}) => {
  const clampedValue = Math.max(min, Math.min(max, value));
  
  // 260-degree sweep from 140° (bottom-left) to 400° (bottom-right)
  const startAngle = 140;
  const endAngle = 400;
  const totalSweep = endAngle - startAngle; // 260°

  const valueRatio = Math.max(0, Math.min(1, (clampedValue - min) / (max - min)));
  const currentAngle = startAngle + valueRatio * totalSweep;

  const cx = 100;
  const cy = 100;
  const radius = 64;
  const strokeWidth = 5;

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
            <stop offset="0%" stopColor={accentColor} stopOpacity="0.4" />
            <stop offset="100%" stopColor={accentColor} stopOpacity="1" />
          </linearGradient>
        </defs>

        {/* Subtle Outer Dial Ring */}
        <circle
          cx={cx}
          cy={cy}
          r={92}
          fill="none"
          stroke="rgba(255, 255, 255, 0.03)"
          strokeWidth="1"
        />

        {/* Background Track Arc */}
        <path
          d={createArc(startAngle, endAngle, radius)}
          fill="none"
          stroke="#151924"
          strokeWidth={strokeWidth}
          strokeLinecap="round"
        />

        {/* Redline Zone Track */}
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

        {/* Active Value Arc */}
        {valueRatio > 0.005 && (
          <path
            d={createArc(startAngle, currentAngle, radius)}
            fill="none"
            stroke={`url(#${gradientId})`}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
          />
        )}

        {/* Clean Outer Ticks & Labels */}
        {ticks.map((t) => {
          const tRatio = (t - min) / (max - min);
          const tAngle = startAngle + tRatio * totalSweep;
          const p1 = getCoord(radius + 4, tAngle);
          const p2 = getCoord(radius + 10, tAngle);
          const pText = getCoord(radius + 21, tAngle);
          const isRed = redlineStart && t >= redlineStart;

          const tickLabel = t >= 1000 ? `${t / 1000}k` : `${t}`;

          return (
            <g key={t}>
              <line
                x1={p1.x.toFixed(2)}
                y1={p1.y.toFixed(2)}
                x2={p2.x.toFixed(2)}
                y2={p2.y.toFixed(2)}
                stroke={isRed ? '#ff2a40' : 'rgba(255, 255, 255, 0.22)'}
                strokeWidth={isRed ? '1.5' : '1'}
              />
              <text
                x={pText.x.toFixed(2)}
                y={(pText.y + 3).toFixed(2)}
                fill={isRed ? '#ff6b7b' : 'rgba(255, 255, 255, 0.4)'}
                fontSize="8.5"
                fontFamily="'Chakra Petch', monospace"
                fontWeight="600"
                textAnchor="middle"
              >
                {tickLabel}
              </text>
            </g>
          );
        })}

        {/* Precision Needle: Minimalist indicator blade */}
        <g
          style={{
            transform: `rotate(${currentAngle}deg)`,
            transformOrigin: `${cx}px ${cy}px`,
            transition: 'transform 0.08s cubic-bezier(0.1, 0.9, 0.2, 1)',
          }}
        >
          <line
            x1={cx}
            y1={cy}
            x2={cx + radius - 3}
            y2={cy}
            stroke={accentColor}
            strokeWidth="2"
            strokeLinecap="round"
          />
          <circle
            cx={cx + radius - 3}
            cy={cy}
            r="2.25"
            fill={accentColor}
          />
        </g>

        {/* Minimal Hub Center Cap */}
        <circle
          cx={cx}
          cy={cy}
          r={7}
          fill="#0a0c12"
          stroke="rgba(255, 255, 255, 0.15)"
          strokeWidth="1.5"
        />
        <circle
          cx={cx}
          cy={cy}
          r={2.5}
          fill={accentColor}
        />
      </svg>

      {/* Floating Center Digital Readout */}
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none text-center pt-8">
        <span className="text-[8.5px] font-bold tracking-wider text-[#64748b] uppercase font-['Inter']">
          {title}
        </span>
        <div className="flex items-baseline justify-center gap-1 mt-[-2px]">
          <span className="text-xl sm:text-2xl font-black text-[#f8fafc] font-['Chakra_Petch'] tabular-nums tracking-tight">
            {displayValue}
          </span>
          <span className="text-[9px] font-bold text-[#64748b] font-['Chakra_Petch']">
            {unit}
          </span>
        </div>
        {subValue !== undefined && (
          <span className="text-[8.5px] text-[#94a3b8] font-medium font-['Inter'] mt-[-1px]">
            {subLabel ? `${subLabel}: ` : ''}<strong className="text-[#cbd5e1] font-semibold">{subValue}</strong>
          </span>
        )}
      </div>
    </div>
  );
};
