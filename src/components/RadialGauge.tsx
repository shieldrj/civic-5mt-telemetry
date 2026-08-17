import React from 'react';

/*
 * A dial, stripped back to the parts that carry a reading.
 *
 * What came off: an SVG Gaussian glow filter on the value arc, a two-stop gradient along
 * it, an outer bezel circle, a white pointer bead ringed in the accent colour, five tick
 * labels, and a bordered chip around the sub-value. None of it encoded anything - the
 * angle of the arc was already the whole message, and every added mark competed with the
 * numeral in the middle, which is what a driver actually reads.
 *
 * What stayed: a hairline track, a solid arc, three ticks, and a short radial mark at the
 * value. The mark is a watch hand rather than a bead - it points at the scale instead of
 * sitting on top of it.
 */

interface RadialGaugeProps {
  value: number;
  min: number;
  max: number;
  title: string;
  unit: string;
  /** Sits under the numeral as plain text. No chip, no border. */
  subValue?: string | number;
  /** Replaces the numeral entirely - for states that are not a reading. See `state` below. */
  overrideValue?: string;
  /** Colour of the arc and mark. Defaults to the ink colour: a normal value is not coloured. */
  accentColor?: string;
  /** Where the scale stops being normal. Drawn as a hairline segment, not a filled zone. */
  redlineStart?: number;
  ticks?: number[];
  size?: number;
  isHero?: boolean;
}

const INK = '#eef0f2';
const INK_2 = '#9aa1a9';
const INK_3 = '#6b727a';

export const RadialGauge: React.FC<RadialGaugeProps> = ({
  value,
  min,
  max,
  title,
  unit,
  subValue,
  overrideValue,
  accentColor = INK,
  redlineStart,
  ticks = [],
  size = 220,
  isHero = false,
}) => {
  const clampedValue = Math.max(min, Math.min(max, value));

  // 250-degree sweep from 145 (bottom-left) to 395 (bottom-right).
  const startAngle = 145;
  const endAngle = 395;
  const totalSweep = endAngle - startAngle;

  const valueRatio = Math.max(0, Math.min(1, (clampedValue - min) / (max - min)));
  const currentAngle = startAngle + valueRatio * totalSweep;

  const cx = 100;
  const cy = 100;
  const radius = 74;

  // Thin. The old gauge used 9px on the hero, which at 344px across is a band rather than
  // a line, and it forced everything else to shout to keep up.
  const strokeWidth = isHero ? 3 : 2.5;

  const degToRad = (deg: number) => (deg * Math.PI) / 180;

  const getCoord = (r: number, deg: number) => {
    const rad = degToRad(deg);
    return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) };
  };

  const createArc = (startDeg: number, endDeg: number, r: number) => {
    const p1 = getCoord(r, startDeg);
    const p2 = getCoord(r, endDeg);
    const largeArc = Math.abs(endDeg - startDeg) > 180 ? 1 : 0;
    return `M ${p1.x.toFixed(2)} ${p1.y.toFixed(2)} A ${r} ${r} 0 ${largeArc} 1 ${p2.x.toFixed(
      2
    )} ${p2.y.toFixed(2)}`;
  };

  const displayValue =
    overrideValue !== undefined
      ? overrideValue
      : unit === '%' || unit === '°F' || value >= 100
      ? Math.round(value)
      : value.toFixed(1);

  const markStart = getCoord(radius - 8, currentAngle);
  const markEnd = getCoord(radius + 2, currentAngle);

  return (
    <div
      className="relative flex flex-col items-center justify-center select-none"
      style={{ width: size, height: size }}
    >
      <svg viewBox="0 0 200 200" className="w-full h-full overflow-visible">
        {/* Track */}
        <path
          d={createArc(startAngle, endAngle, radius)}
          fill="none"
          stroke="rgba(255,255,255,0.09)"
          strokeWidth="1.5"
        />

        {/* Redline. A hairline in the alert colour rather than a translucent filled band -
            it marks where the scale changes meaning without occupying the scale. */}
        {redlineStart !== undefined && redlineStart < max && (
          <path
            d={createArc(
              startAngle + ((redlineStart - min) / (max - min)) * totalSweep,
              endAngle,
              radius
            )}
            fill="none"
            stroke="#d8453b"
            strokeWidth="1.5"
          />
        )}

        {/* Value */}
        {valueRatio > 0.004 && (
          <path
            d={createArc(startAngle, currentAngle, radius)}
            fill="none"
            stroke={accentColor}
            strokeWidth={strokeWidth}
            strokeLinecap="butt"
            style={{ transition: 'stroke 240ms ease' }}
          />
        )}

        {/* Three ticks. The ends and the middle - enough to read the scale, and no more.
            Unlabelled: the numeral in the centre is the reading, and five small numbers
            around the rim were competing with it. */}
        {ticks.map((t) => {
          const tAngle = startAngle + ((t - min) / (max - min)) * totalSweep;
          const p1 = getCoord(radius + 6, tAngle);
          const p2 = getCoord(radius + 12, tAngle);
          return (
            <line
              key={t}
              x1={p1.x.toFixed(2)}
              y1={p1.y.toFixed(2)}
              x2={p2.x.toFixed(2)}
              y2={p2.y.toFixed(2)}
              stroke="rgba(255,255,255,0.2)"
              strokeWidth="1"
            />
          );
        })}

        {/* The hand. Crosses the track so it reads as pointing at a scale. */}
        {overrideValue === undefined && (
          <line
            x1={markStart.x.toFixed(2)}
            y1={markStart.y.toFixed(2)}
            x2={markEnd.x.toFixed(2)}
            y2={markEnd.y.toFixed(2)}
            stroke={accentColor}
            strokeWidth={isHero ? 2.25 : 2}
            strokeLinecap="butt"
            style={{ transition: 'stroke 240ms ease' }}
          />
        )}
      </svg>

      {/* Centre readout. Sizes derive from `size` because the cockpit fits the gauge to the
          measured viewport - fixed type would be lost on a tall screen and oversized on a
          short one. */}
      <div className="absolute inset-0 flex flex-col items-center justify-center pointer-events-none text-center px-4">
        {/* The title and the sub-value are clamped at the top as well as the bottom. They
            scale with the dial so a small one stays legible, but a 380px dial was giving
            its 10px label a 17px rendering - a label the size of a value stops reading as
            a label. The numeral is the only thing that should grow without a ceiling. */}
        <span
          className="t-label"
          style={{
            fontSize: Math.min(12, Math.max(9, size * 0.045)),
            marginBottom: size * 0.03,
          }}
        >
          {title}
        </span>

        <div className="flex items-baseline justify-center" style={{ gap: size * 0.012 }}>
          <span className="t-hero" style={{ fontSize: Math.max(30, size * 0.24) }}>
            {displayValue}
          </span>
          {overrideValue === undefined && (
            <span className="t-hero t-unit" style={{ fontSize: Math.max(11, size * 0.072) }}>
              {unit}
            </span>
          )}
        </div>

        {subValue !== undefined && (
          <span
            style={{
              fontSize: Math.min(13, Math.max(10, size * 0.05)),
              marginTop: size * 0.045,
              color: INK_2,
              letterSpacing: '0.01em',
              lineHeight: 1.3,
            }}
          >
            {subValue}
          </span>
        )}
      </div>
    </div>
  );
};

export { INK, INK_2, INK_3 };
