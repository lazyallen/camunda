import React from 'react';

import {DurationHeatmapModal} from './DurationHeatmap';
import {ProgressBarModal} from './ProgressBar';
import {ChartModal} from './Chart';

import {isSingleNumber, isDurationHeatmap, isChart} from './service';

export default function TargetValueModal(props) {
  if (isSingleNumber(props.reportResult)) {
    return (
      <ProgressBarModal
        {...props}
        type={props.reportResult.data.view.property === 'frequency' ? 'number' : 'duration'}
      />
    );
  } else if (isDurationHeatmap(props.reportResult)) {
    return <DurationHeatmapModal {...props} />;
  } else if (isChart(props.reportResult)) {
    return <ChartModal {...props} />;
  }

  return null;
}
