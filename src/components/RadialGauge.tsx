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
  size = 230,
}) => {
  const filterId = 'glow-' + title.replace(/\s+/g, '-').toLowerCase();
  const clampedValue = Math.max(min, Math.min(max, value));
  
  // 240-degree sweep from bottom-left (210°) clockwise to bottom-right (450° / 90°)
  // Angle convention with 0° at top (12 o'clock):
  // 220° is bottom-left (~7:20), 0° / 360° is top (12:00), 140° / 500° is bottom-right (~4:40)
  const startAngle = 215;
  const endAngle = 505;
  const totalSweep = endAngle - startAngle; // 290 degrees

  const valueRatio = (clampedValue - min) / (max - min);
  const currentAngle = startAngle + valueRatio * totalSweep;

  const center = size / 2;
  const radius = size * 0.38;
  const strokeWidth = 8;

  // Convert angle (0° = Top/12 o'clock, clockwise) to Cartesian (x, y)
  const angleToCoord = (cx: number, cy: number, r: number, angleDeg: number) => {
    const rad = ((angleDeg - 90) * Math.PI) / 180.0;
    return {
      x: cx + r * Math.cos(rad),
      y: cy + r * Math.sin(rad),
    };
  };

  // Draw clockwise SVG arc
  const createArc = (startDeg: number, endDeg: number, r: number) => {
    const p1 = angleToCoord(center, center, r, startDeg);
    const p2 = angleToCoord(center, center, r, endDeg);
    const largeArc = Math.abs(endDeg - startDeg) > 180 ? 1 : 0;
    return `M ${p1.x} ${p1.y} A ${r} ${r} 0 ${largeArc} 1 ${p2.x} ${p2.y}`;
  };

  // Base needle points pointing straight up (0° / 12 o'clock)
  const needleLength = radius * 0.90;
  const isCompact = size < 200;

  return (
    <div className="relative flex flex-col items-center justify-center select-none" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="overflow-visible">
        <defs>
          <filter id={filterId} x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="3" result="blur" />
            <feComposite in="SourceGraphic" in2="blur" operator="over" />
          </filter>
        </defs>

        {/* Background Track Arc */}
        <path
          d={createArc(startAngle, endAngle, radius)}
          fill="none"
          stroke="#141824"
          strokeWidth={strokeWidth}
          strokeLinecap="round"
        />

        {/* Redline zone if defined */}
        {redlineStart && redlineStart < max && (
          <path
            d={createArc(
              startAngle + ((redlineStart - min) / (max - min)) * totalSweep,
              endAngle,
              radius
            )}
            fill="none"
            stroke="rgba(255, 42, 64, 0.4)"
            strokeWidth={strokeWidth}
            strokeLinecap="round"
          />
        )}

        {/* Active Arc */}
        {valueRatio > 0.005 && (
          <path
            d={createArc(startAngle, currentAngle, radius)}
            fill="none"
            stroke={accentColor}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
            filter={`url(#${filterId})`}
          />
        )}

        {/* Ticks & Numeric Labels */}
        {ticks.map((t) => {
          const tRatio = (t - min) / (max - min);
          const tAngle = startAngle + tRatio * totalSweep;
          const pOuter = angleToCoord(center, center, radius + (isCompact ? 8 : 11), tAngle);
          const pInner = angleToCoord(center, center, radius + 3, tAngle);
          const pText = angleToCoord(center, center, radius - (isCompact ? 11 : 15), tAngle);
          const isRed = redlineStart && t >= redlineStart;

          // Format tick display (e.g. 7000 -> 7k if large)
          const tickLabel = t >= 1000 ? `${t / 1000}k` : `${t}`;

          return (
            <g key={t}>
              <line
                x1={pInner.x}
                y1={pInner.y}
                x2={pOuter.x}
                y2={pOuter.y}
                stroke={isRed ? '#ff2a40' : '#475569'}
                strokeWidth={isRed ? 2 : 1.2}
              />
              <text
                x={pText.x}
                y={pText.y + (isCompact ? 2.5 : 3.5)}
                fill={isRed ? '#ff6b7b' : '#64748b'}
                fontSize={isCompact ? "8" : "9"}
                fontFamily="'Rajdhani', sans-serif"
                fontWeight="700"
                textAnchor="middle"
              >
                {tickLabel}
              </text>
            </g>
          );
        })}

        {/* Hardware Accelerated Smooth Rotating Needle */}
        <g
          style={{
            transform: `rotate(${currentAngle}deg)`,
            transformOrigin: `${center}px ${center}px`,
            transition: 'transform 0.09s cubic-bezier(0.1, 0.9, 0.2, 1)',
          }}
        >
          <polygon
            points={`${center},${center - needleLength} ${center - (isCompact ? 4.5 : 6)},${center} ${center + (isCompact ? 4.5 : 6)},${center}`}
            fill={accentColor}
            filter={`url(#${filterId})`}
          />
        </g>

        {/* Center Cap */}
        <circle cx={center} cy={center} r={isCompact ? 9 : 12} fill="#0d0f17" stroke="#252b3d" strokeWidth={isCompact ? 2 : 2.5} />
        <circle cx={center} cy={center} r={isCompact ? 3.5 : 5} fill={accentColor} />
      </svg>

      {/* Digital Inset Readout - Placed clearly at bottom open section */}
      <div
        className="absolute flex flex-col items-center justify-center text-center pointer-events-none"
        style={{ bottom: size * (isCompact ? 0.06 : 0.08) }}
      >
        <span
          className={`font-bold uppercase tracking-wider text-[#64748b] font-['Chakra_Petch'] ${
            isCompact ? 'text-[9px]' : 'text-[10px]'
          }`}
        >
          {title}
        </span>
        <div className="flex items-baseline gap-1 mt-[-2px]">
          <span
            className={`font-extrabold tracking-tight text-[#f8fafc] font-['Chakra_Petch'] ${
              isCompact ? 'text-xl' : 'text-2xl'
            }`}
            style={{ textShadow: `0 0 12px ${accentColor}50` }}
          >
            {typeof value === 'number' ? (unit === '%' || unit === '°F' || value >= 100 ? Math.round(value) : value.toFixed(1)) : value}
          </span>
          <span
            className={`font-bold text-[#64748b] font-['Chakra_Petch'] ${
              isCompact ? 'text-[9px]' : 'text-[10px]'
            }`}
          >
            {unit}
          </span>
        </div>
        {subValue !== undefined && (
          <span
            className={`text-[#475569] font-medium font-['Inter'] mt-[-2px] ${
              isCompact ? 'text-[9px]' : 'text-[10px]'
            }`}
          >
            {subLabel}: <strong className="text-[#94a3b8]">{subValue}</strong>
          </span>
        )}
      </div>
    </div>
  );
};
