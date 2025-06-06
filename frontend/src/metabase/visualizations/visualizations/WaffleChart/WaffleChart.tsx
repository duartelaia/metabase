import { t } from "ttag";

import { colors } from "metabase/lib/colors";
import { columnSettings } from "metabase/visualizations/lib/settings/column";
import {
  dimensionSetting,
  metricSetting,
} from "metabase/visualizations/lib/settings/utils";
import type { VisualizationProps } from "metabase/visualizations/types";
import { isDimension, isMetric } from "metabase-lib/v1/types/utils/isa";
import type { RawSeries } from "metabase-types/api";

const WAFFLE_DIMENSION = "waffle.dimension";
const WAFFLE_METRIC = "waffle.metric";
const WAFFLE_ROWS = "waffle.rows";
const WAFFLE_COLUMNS = "waffle.columns";
const WAFFLE_COLOR = "waffle.color";

function getDefaultDimension(series: RawSeries) {
  return (
    series[0].data.cols.filter(isDimension).map((col) => col.name)[0] || null
  );
}
function getDefaultMetric(series: RawSeries) {
  return series[0].data.cols.filter(isMetric).map((col) => col.name)[0] || null;
}

Object.assign(WaffleChart, {
  getUiName: () => "Waffle",
  identifier: "waffle",
  iconName: "grid",
  minSize: { width: 3, height: 3 },
  defaultSize: { width: 6, height: 6 },
  isSensible: ({ cols, rows }) => cols.length >= 1 && rows.length > 0,
  checkRenderable: (
    [
      {
        data: { rows },
      },
    ],
    settings,
  ) => {
    if (rows.length < 1) {
      throw new Error("Waffle chart requires at least one row.");
    }
    if (!settings[WAFFLE_METRIC]) {
      throw new Error("Which value do you want to use?");
    }
  },
  hasEmptyState: true,
  settings: {
    ...columnSettings({ hidden: true }),
    ...dimensionSetting(WAFFLE_DIMENSION, {
      section: "Data",
      title: "Category",
      showColumnSetting: true,
      getDefault: getDefaultDimension,
    }),
    ...metricSetting(WAFFLE_METRIC, {
      section: "Data",
      title: "Value",
      showColumnSetting: true,
      getDefault: getDefaultMetric,
    }),
    [WAFFLE_ROWS]: {
      section: "Display",
      title: "Rows",
      widget: "number",
      getDefault: () => 10,
    },
    [WAFFLE_COLUMNS]: {
      section: "Display",
      title: "Columns",
      widget: "number",
      getDefault: () => 10,
    },
    [WAFFLE_COLOR]: {
      section: "Display",
      title: "Color",
      widget: "color",
      getDefault: () => colors["accent-gray"],
    },
  },
});

function getColor(idx: number, total: number) {
  const hue = Math.round((idx * 360) / total);
  return `hsl(${hue}, 60%, 55%)`;
}

export function WaffleChart(props: VisualizationProps) {
  const { rawSeries, settings } = props;

  const rows = rawSeries?.[0]?.data?.rows || [];
  const cols = rawSeries?.[0]?.data?.cols || [];
  const metricCol = settings[WAFFLE_METRIC];
  const dimensionCol = settings[WAFFLE_DIMENSION];

  const metricIndex = cols.findIndex((col) => col.name === metricCol);
  const dimensionIndex = cols.findIndex((col) => col.name === dimensionCol);

  const numRows = Number(settings[WAFFLE_ROWS]) || 10;
  const numCols = Number(settings[WAFFLE_COLUMNS]) || 10;
  const totalCells = numRows * numCols;

  let categories: { label: string; value: number }[] = [];
  if (dimensionIndex >= 0 && metricIndex >= 0) {
    const map = new Map<string, number>();
    for (const row of rows) {
      const label = row[dimensionIndex]?.toString() || "";
      const value = Number(row[metricIndex]) || 0;
      map.set(label, (map.get(label) || 0) + value);
    }
    categories = Array.from(map.entries()).map(([label, value]) => ({
      label,
      value,
    }));
  } else if (metricIndex >= 0 && rows.length > 0) {
    categories = [{ label: metricCol, value: Number(rows[0][metricIndex]) }];
  }

  const totalValue = categories.reduce((acc, c) => acc + c.value, 0);
  const visible: { label: string; value: number; percent: number }[] = [];
  let otherValue = 0;
  categories.forEach((cat) => {
    const percent = totalValue > 0 ? (cat.value / totalValue) * 100 : 0;
    if (percent > 1) {
      visible.push({ ...cat, percent });
    } else {
      otherValue += cat.value;
    }
  });
  if (otherValue > 0) {
    visible.push({
      label: "Other",
      value: otherValue,
      percent: (otherValue / totalValue) * 100,
    });
  }

  const cellsPerCategory = visible.map((c) =>
    totalValue > 0 ? Math.round((c.value / totalValue) * totalCells) : 0,
  );

  let diff = totalCells - cellsPerCategory.reduce((a, b) => a + b, 0);
  const sortedIdx = visible
    .map((cat, idx) => [cat.value, idx] as [number, number])
    .sort((a, b) => b[0] - a[0])
    .map(([, idx]) => idx);

  let i = 0;
  while (diff !== 0 && sortedIdx.length > 0) {
    const idx = sortedIdx[i % sortedIdx.length];
    if (diff > 0) {
      cellsPerCategory[idx]++;
      diff--;
    } else if (diff < 0 && cellsPerCategory[idx] > 0) {
      cellsPerCategory[idx]--;
      diff++;
    }
    i++;
  }

  let cellColors: string[] = [];
  visible.forEach((cat, idx) => {
    const color =
      cat.label === "Other"
        ? colors["accent-gray-light"]
        : getColor(
            idx,
            visible.length - (visible.some((c) => c.label === "Other") ? 1 : 0),
          );
    cellColors = cellColors.concat(Array(cellsPerCategory[idx]).fill(color));
  });
  cellColors = cellColors.concat(
    Array(totalCells - cellColors.length).fill(colors["accent-gray"]),
  );
  cellColors = cellColors.slice(0, totalCells);

  const cellTooltips: string[] = [];
  let cellIdx = 0;
  visible.forEach((cat, idx) => {
    const percent =
      totalValue > 0 ? Math.round((cat.value / totalValue) * 100) : 0;
    for (let j = 0; j < cellsPerCategory[idx]; j++) {
      cellTooltips[cellIdx++] = `${cat.label}: ${percent}%`;
    }
  });
  while (cellTooltips.length < totalCells) {
    cellTooltips.push("");
  }

  const CELL_SIZE = 22;
  const CELL_GAP = 2;

  return (
    <div
      className={props.className}
      title={t`${totalValue} total`}
      style={{
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        minHeight: numRows * (CELL_SIZE + CELL_GAP) + 32,
        width: "100%",
        lineHeight: 0,
        userSelect: "none",
      }}
    >
      <svg
        width={numCols * (CELL_SIZE + CELL_GAP)}
        height={numRows * (CELL_SIZE + CELL_GAP)}
        style={{ display: "block" }}
      >
        {Array.from({ length: totalCells }).map((_, i) => {
          const col = Math.floor(i / numRows);
          const row = i % numRows;
          return (
            <rect
              key={i}
              x={col * (CELL_SIZE + CELL_GAP)}
              y={row * (CELL_SIZE + CELL_GAP)}
              width={CELL_SIZE}
              height={CELL_SIZE}
              rx={4}
              fill={cellColors[i]}
              stroke={colors["accent-gray-dark"]}
              strokeWidth={1}
            >
              {cellTooltips[i] && <title>{cellTooltips[i]}</title>}
            </rect>
          );
        })}
      </svg>
    </div>
  );
}
